package com.posemirror.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.posemirror.app.index.IndexDownloader
import com.posemirror.app.index.IndexManager
import com.posemirror.app.index.IndexSweeper
import com.posemirror.app.index.PoseIndex
import com.posemirror.app.pose.PoseLandmarkerHelper
import com.posemirror.app.pose.PoseMath
import com.posemirror.app.pose.PoseModelProvider
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.ui.FirstLaunchDialog
import com.posemirror.app.ui.ResultsAdapter
import com.posemirror.app.ui.SettingsActivity
import com.posemirror.app.work.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Single-screen app: camera preview on top, live top-k matches below.
 *
 * Pipeline (search is always offline after first-run setup):
 * CameraX (front camera) -> PoseLandmarker (GPU/CPU) -> PoseMath.normalize
 * -> PoseIndex.search over the memory-mapped portable index bundle.
 *
 * First run: the starter index auto-downloads from the release URL (with a
 * progress UI); the manual-URL fallback only appears if that fails. The pose
 * model is bundled in the APK when possible, otherwise downloaded once to
 * the app's private files dir. A one-time dialog collects the five update
 * presets; the background worker then keeps the gallery rolling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var mirrorToggle: CheckBox
    private lateinit var resultsGrid: RecyclerView
    private lateinit var setupCard: View
    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadStatus: TextView

    private val adapter = ResultsAdapter()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var landmarker: PoseLandmarkerHelper? = null
    private var poseIndex: PoseIndex? = null
    private var lastHits: List<com.posemirror.app.index.SearchHit> = emptyList()
    private var lastUiUpdate = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else statusText.text = getString(R.string.need_camera)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        mirrorToggle = findViewById(R.id.mirrorToggle)
        resultsGrid = findViewById(R.id.resultsGrid)
        setupCard = findViewById(R.id.setupCard)
        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        downloadProgress = findViewById(R.id.downloadProgress)
        downloadStatus = findViewById(R.id.downloadStatus)

        resultsGrid.layoutManager = GridLayoutManager(this, 3)
        resultsGrid.adapter = adapter
        adapter.onTogglePin = { id -> togglePin(id) }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        downloadButton.setOnClickListener { startManualDownload() }

        bootstrapIndex()
        resolveModelAndStartCamera()
    }

    // ---------- index bootstrap ----------

    private fun indexDir(): File = File(filesDir, UpdateWorker.INDEX_DIR)

    private fun bootstrapIndex() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { tryLoadIndex() }
            when {
                loaded -> {
                    setupCard.visibility = View.GONE
                    onIndexReady()
                }
                !AppPrefs.isAutoIndexTried(this@MainActivity) -> {
                    AppPrefs.setAutoIndexTried(this@MainActivity)
                    startAutoDownload()
                }
                else -> showManualDownload()
            }
        }
    }

    /** Returns true when a usable index was loaded. */
    private fun tryLoadIndex(): Boolean {
        val dir = indexDir()
        if (!File(dir, "format.json").exists()) return false
        val result = PoseIndex.load(dir)
        result.onSuccess {
            poseIndex = it
            runOnUiThread {
                statusText.text = getString(R.string.index_ready, it.size)
            }
        }.onFailure { e ->
            runOnUiThread {
                statusText.text = getString(R.string.index_bad, e.message)
            }
        }
        return result.isSuccess
    }

    private fun startAutoDownload() {
        setupCard.visibility = View.VISIBLE
        urlInput.visibility = View.GONE
        downloadButton.visibility = View.GONE
        downloadProgress.visibility = View.VISIBLE
        downloadProgress.isIndeterminate = true
        downloadStatus.text = getString(R.string.dl_auto)
        doDownload(
            url = IndexDownloader.DEFAULT_INDEX_URL,
            onDone = {
                if (tryLoadIndex()) {
                    setupCard.visibility = View.GONE
                    onIndexReady()
                } else {
                    showManualDownload()
                }
            },
            onError = { showManualDownload() }
        )
    }

    private fun showManualDownload() {
        setupCard.visibility = View.VISIBLE
        urlInput.visibility = View.VISIBLE
        downloadButton.visibility = View.VISIBLE
        downloadButton.isEnabled = true
        downloadProgress.visibility = View.GONE
        downloadStatus.text = getString(R.string.dl_auto_failed)
    }

    private fun startManualDownload() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            downloadStatus.text = getString(R.string.url_empty)
            return
        }
        downloadButton.isEnabled = false
        downloadProgress.visibility = View.VISIBLE
        doDownload(
            url = url,
            onDone = {
                downloadProgress.visibility = View.GONE
                downloadButton.isEnabled = true
                if (tryLoadIndex()) {
                    setupCard.visibility = View.GONE
                    onIndexReady()
                } else {
                    downloadStatus.text = getString(R.string.index_bad, "downloaded")
                }
            },
            onError = { msg ->
                downloadProgress.visibility = View.GONE
                downloadButton.isEnabled = true
                downloadStatus.text = getString(R.string.dl_failed, msg)
            }
        )
    }

    private fun doDownload(url: String, onDone: () -> Unit, onError: (String) -> Unit) {
        IndexDownloader.download(
            context = this,
            url = url,
            destDir = indexDir(),
            onProgress = { done, total ->
                runOnUiThread {
                    if (total > 0) {
                        downloadProgress.isIndeterminate = false
                        downloadProgress.progress = (done * 100 / total).toInt()
                        downloadStatus.text =
                            getString(R.string.dl_progress, done / 1024, total / 1024)
                    } else {
                        downloadProgress.isIndeterminate = true
                        downloadStatus.text =
                            getString(R.string.dl_progress_unknown, done / 1024)
                    }
                }
            },
            onDone = { runOnUiThread { onDone() } },
            onError = { msg -> runOnUiThread { onError(msg) } }
        )
    }

    /** Called once a usable index is in place (first run or later). */
    private fun onIndexReady() {
        refreshPinnedIds()
        lifecycleScope.launch {
            val s = AppPrefs.load(this@MainActivity)
            if (!AppPrefs.isFirstRunDone(this@MainActivity)) {
                FirstLaunchDialog().show(supportFragmentManager, "firstlaunch")
            }
            // Retention sweep on every app start (background thread).
            Thread {
                val mgr = IndexManager(indexDir())
                mgr.ensureMetaFor(poseIndex?.ids ?: emptyList())
                val swept = IndexSweeper.sweep(indexDir(), s.ttlDays, s.indexCap)
                if (swept.deleted.isNotEmpty()) {
                    tryLoadIndex()
                    refreshPinnedIds()
                }
            }.start()
        }
    }

    // ---------- favorites ----------

    private fun refreshPinnedIds() {
        Thread {
            val pinned = IndexManager(indexDir()).pinnedIds()
            runOnUiThread {
                adapter.pinnedIds = pinned
                adapter.submitList(lastHits)
            }
        }.start()
    }

    private fun togglePin(id: String) {
        Thread {
            val mgr = IndexManager(indexDir())
            val cur = mgr.loadMeta()[id]?.pinned == true
            mgr.setPinned(id, !cur)
            val pinned = mgr.pinnedIds()
            runOnUiThread {
                adapter.pinnedIds = pinned
                adapter.submitList(lastHits)
            }
        }.start()
    }

    // ---------- pose model ----------

    /** Resolves the model off the main thread (may download it once). */
    private fun resolveModelAndStartCamera() {
        statusText.text = getString(R.string.model_loading)
        Thread {
            val model = PoseModelProvider.resolve(this)
            runOnUiThread {
                if (model == null) {
                    statusText.text = getString(R.string.model_failed)
                    return@runOnUiThread
                }
                val err = setupLandmarker(model)
                if (err != null) {
                    statusText.text = err
                    return@runOnUiThread
                }
                requestCamera()
            }
        }.start()
    }

    private fun setupLandmarker(model: PoseModelProvider.ModelRef): String? {
        val helper = PoseLandmarkerHelper(
            context = this,
            onResult = { landmarks -> onPoseResult(landmarks) },
            onError = { msg ->
                runOnUiThread { statusText.text = getString(R.string.pose_error, msg) }
            }
        )
        val err = helper.setup(model)
        if (err == null) landmarker = helper
        return err
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Runs on a MediaPipe background thread. */
    private fun onPoseResult(landmarks: List<PoseMath.Landmark>) {
        val vec = if (landmarks.isEmpty()) null else PoseMath.normalize(landmarks)
        val index = poseIndex
        if (vec == null || index == null) {
            throttledUiUpdate(emptyList(), poseFound = vec != null)
            return
        }
        val hits = index.search(vec, k = 9, mirror = mirrorToggle.isChecked)
        throttledUiUpdate(hits, poseFound = true)
    }

    private fun throttledUiUpdate(
        hits: List<com.posemirror.app.index.SearchHit>,
        poseFound: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 350 && hits.isNotEmpty()) return
        lastUiUpdate = now
        runOnUiThread {
            lastHits = hits
            adapter.submitList(hits)
            val n = poseIndex?.size ?: 0
            statusText.text = getString(
                R.string.status,
                n,
                if (poseFound) getString(R.string.yes) else getString(R.string.no)
            )
        }
    }

    // ---------- camera ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val bmp = rgbaToBitmap(image)
                    landmarker?.detect(bmp)
                } catch (e: Exception) {
                    // never kill the analyzer
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    /** ImageProxy (RGBA_8888, single plane) -> Bitmap, stride-safe. */
    private fun rgbaToBitmap(image: androidx.camera.core.ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val rowStride = plane.rowStride
        val full = Bitmap.createBitmap(
            rowStride / 4, image.height, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(buffer)
        return if (full.width == image.width) full
        else Bitmap.createBitmap(full, 0, 0, image.width, image.height)
    }

    override fun onDestroy() {
        super.onDestroy()
        landmarker?.close()
        cameraExecutor.shutdown()
    }
}
