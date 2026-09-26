package com.posemirror.app

import android.Manifest
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.posemirror.app.index.IndexDownloader
import com.posemirror.app.index.PoseIndex
import com.posemirror.app.pose.PoseLandmarkerHelper
import com.posemirror.app.pose.PoseMath
import com.posemirror.app.ui.ResultsAdapter
import java.io.File
import java.util.concurrent.Executors

/**
 * Single-screen app: camera preview on top, live top-k matches below.
 *
 * Pipeline (all offline after first-run setup):
 * CameraX (front camera) -> PoseLandmarker (GPU/CPU) -> PoseMath.normalize
 * -> PoseIndex.search over the memory-mapped portable index bundle.
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

        downloadButton.setOnClickListener { startIndexDownload() }

        if (tryLoadIndex()) {
            setupCard.visibility = View.GONE
        } else {
            setupCard.visibility = View.VISIBLE
        }

        val err = setupLandmarker()
        if (err != null) {
            statusText.text = err
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- index ----------

    private fun indexDir(): File = File(filesDir, "posemirror-index")

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

    private fun startIndexDownload() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            downloadStatus.text = getString(R.string.url_empty)
            return
        }
        downloadButton.isEnabled = false
        downloadProgress.visibility = View.VISIBLE
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
            onDone = {
                runOnUiThread {
                    downloadProgress.visibility = View.GONE
                    downloadButton.isEnabled = true
                    if (tryLoadIndex()) setupCard.visibility = View.GONE
                    else downloadStatus.text = getString(R.string.index_bad, "downloaded")
                }
            },
            onError = { msg ->
                runOnUiThread {
                    downloadProgress.visibility = View.GONE
                    downloadButton.isEnabled = true
                    downloadStatus.text = getString(R.string.dl_failed, msg)
                }
            }
        )
    }

    // ---------- pose ----------

    private fun setupLandmarker(): String? {
        val helper = PoseLandmarkerHelper(
            context = this,
            onResult = { landmarks -> onPoseResult(landmarks) },
            onError = { msg ->
                runOnUiThread { statusText.text = getString(R.string.pose_error, msg) }
            }
        )
        val err = helper.setup()
        if (err == null) landmarker = helper
        return err
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
