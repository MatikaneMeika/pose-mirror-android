package com.posemirror.app

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
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
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.TextView
import com.posemirror.app.index.IndexDownloader
import com.posemirror.app.index.IndexManager
import com.posemirror.app.index.IndexSweeper
import com.posemirror.app.index.PoseIndex
import com.posemirror.app.pose.PoseLandmarkerHelper
import com.posemirror.app.pose.PoseMath
import com.posemirror.app.pose.PoseModelProvider
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.ui.FirstLaunchDialog
import com.posemirror.app.ui.Motion
import com.posemirror.app.ui.ResultDetailSheet
import com.posemirror.app.ui.ResultsAdapter
import com.posemirror.app.ui.SettingsActivity
import com.posemirror.app.work.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Single-screen app: camera viewfinder on top, live top-k matches below.
 *
 * Pipeline (search is always offline after first-run setup):
 * CameraX (front camera) -> PoseLandmarker (GPU/CPU) -> PoseMath.normalize
 * -> PoseIndex.search over the memory-mapped portable index bundle.
 *
 * First run: the starter index auto-downloads from the release URL (setup
 * overlay with progress); the manual-URL fallback only appears if that fails.
 * The pose model is bundled in the APK when possible, otherwise downloaded
 * once to the app's private files dir. A one-time dialog collects the five
 * update presets; the background worker then keeps the gallery rolling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var mirrorBtn: MaterialButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var resultsGrid: RecyclerView
    private lateinit var skeletonView: View
    private var skeletonPulse: ObjectAnimator? = null

    // Setup overlay
    private lateinit var setupScrim: View
    private lateinit var setupLottie: LottieAnimationView
    private lateinit var setupTitle: TextView
    private lateinit var setupBody: TextView
    private lateinit var setupProgress: LinearProgressIndicator
    private lateinit var setupStatus: TextView
    private lateinit var urlField: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var setupPrimaryBtn: MaterialButton
    private lateinit var setupSecondaryBtn: MaterialButton

    private val adapter = ResultsAdapter()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var landmarker: PoseLandmarkerHelper? = null
    private var poseIndex: PoseIndex? = null
    private var lastHits: List<com.posemirror.app.index.SearchHit> = emptyList()
    private var lastUiUpdate = 0L
    private var firstResultsShown = false
    private var mirrorOn = false

    private enum class SetupMode { HIDDEN, AUTO, MANUAL, ERROR, SUCCESS }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                statusText.text = getString(R.string.need_camera)
                statusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.error)
                showSkeleton(false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        mirrorBtn = findViewById(R.id.mirrorBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        resultsGrid = findViewById(R.id.resultsGrid)
        skeletonView = findViewById(R.id.skeletonView)
        setupScrim = findViewById(R.id.setupScrim)
        setupLottie = findViewById(R.id.setupLottie)
        setupTitle = findViewById(R.id.setupTitle)
        setupBody = findViewById(R.id.setupBody)
        setupProgress = findViewById(R.id.setupProgress)
        setupStatus = findViewById(R.id.setupStatus)
        urlField = findViewById(R.id.urlField)
        urlInput = findViewById(R.id.urlInput)
        setupPrimaryBtn = findViewById(R.id.setupPrimaryBtn)
        setupSecondaryBtn = findViewById(R.id.setupSecondaryBtn)

        resultsGrid.layoutManager = GridLayoutManager(this, 3)
        resultsGrid.adapter = adapter
        adapter.onTogglePin = { id -> togglePin(id) }
        adapter.onOpenDetail = { hit -> openDetail(hit) }

        mirrorBtn.setOnClickListener { mirrorOn = mirrorBtn.isChecked }
        settingsBtn.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        setupPrimaryBtn.setOnClickListener { onSetupPrimary() }
        setupSecondaryBtn.setOnClickListener { showSetup(SetupMode.MANUAL) }

        statusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.faint)

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
                    showSetup(SetupMode.HIDDEN)
                    onIndexReady()
                }
                !AppPrefs.isAutoIndexTried(this@MainActivity) -> {
                    AppPrefs.setAutoIndexTried(this@MainActivity)
                    showSetup(SetupMode.AUTO)
                    doDownload(
                        url = IndexDownloader.DEFAULT_INDEX_URL,
                        onDone = {
                            if (tryLoadIndex()) showSetup(SetupMode.SUCCESS)
                            else showSetup(SetupMode.MANUAL)
                        },
                        onError = { showSetup(SetupMode.MANUAL) }
                    )
                }
                else -> showSetup(SetupMode.MANUAL)
            }
        }
    }

    /** Returns true when a usable index was loaded. */
    private fun tryLoadIndex(): Boolean {
        val dir = indexDir()
        if (!File(dir, "format.json").exists()) return false
        val result = PoseIndex.load(dir)
        result.onSuccess { poseIndex = it }
        return result.isSuccess
    }

    private fun showSetup(mode: SetupMode) {
        val animate = Motion.enabled(this)
        val wasVisible = setupScrim.visibility == View.VISIBLE
        when (mode) {
            SetupMode.HIDDEN -> {
                setupLottie.cancelAnimation()
                setupScrim.visibility = View.GONE
            }
            SetupMode.AUTO -> {
                setupScrim.visibility = View.VISIBLE
                setupTitle.setText(R.string.setup_title)
                setupBody.setText(R.string.setup_body)
                playLottie(R.raw.dl_arrow, loop = true)
                setupProgress.visibility = View.VISIBLE
                setupProgress.isIndeterminate = true
                setupStatus.setText(R.string.dl_auto)
                setupStatus.setTextColor(ContextCompat.getColor(this, R.color.muted))
                urlField.visibility = View.GONE
                setupPrimaryBtn.visibility = View.GONE
                setupSecondaryBtn.visibility = View.GONE
            }
            SetupMode.MANUAL -> {
                setupScrim.visibility = View.VISIBLE
                setupTitle.setText(R.string.setup_title)
                setupBody.setText(R.string.setup_body_manual)
                playLottie(R.raw.empty_frame, loop = true)
                setupProgress.visibility = View.GONE
                setupStatus.text = ""
                urlField.visibility = View.VISIBLE
                setupPrimaryBtn.visibility = View.VISIBLE
                setupPrimaryBtn.setText(R.string.download)
                setupSecondaryBtn.visibility = View.GONE
            }
            SetupMode.ERROR -> {
                setupScrim.visibility = View.VISIBLE
                playLottie(R.raw.empty_frame, loop = true)
                setupProgress.visibility = View.GONE
                urlField.visibility = View.GONE
                setupPrimaryBtn.visibility = View.VISIBLE
                setupPrimaryBtn.setText(R.string.retry)
                setupSecondaryBtn.visibility = View.VISIBLE
                setupSecondaryBtn.setText(R.string.setup_manual)
            }
            SetupMode.SUCCESS -> {
                setupScrim.visibility = View.VISIBLE
                setupTitle.setText(R.string.setup_success)
                setupBody.text = ""
                playLottie(R.raw.success_check, loop = false)
                setupProgress.visibility = View.GONE
                setupStatus.text = ""
                urlField.visibility = View.GONE
                setupPrimaryBtn.visibility = View.GONE
                setupSecondaryBtn.visibility = View.GONE
                val hold = if (animate) Motion.SUCCESS_HOLD else 200L
                setupScrim.postDelayed(
                    {
                        if (isFinishing || isDestroyed) return@postDelayed
                        showSetup(SetupMode.HIDDEN)
                        onIndexReady()
                    },
                    hold
                )
            }
        }
        if (mode != SetupMode.HIDDEN && animate && !wasVisible) {
            setupScrim.alpha = 0f
            setupScrim.animate().alpha(1f).setDuration(Motion.SHORT)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            setupScrim.alpha = 1f
        }
    }

    private fun playLottie(rawRes: Int, loop: Boolean) {
        if (!Motion.enabled(this)) {
            setupLottie.cancelAnimation()
            setupLottie.setAnimation(rawRes)
            setupLottie.progress = 0.35f
            return
        }
        setupLottie.repeatCount = if (loop) ObjectAnimator.INFINITE else 0
        setupLottie.setAnimation(rawRes)
        setupLottie.playAnimation()
    }

    private fun onSetupPrimary() {
        when {
            setupPrimaryBtn.text == getString(R.string.retry) -> {
                showSetup(SetupMode.AUTO)
                doDownload(
                    url = IndexDownloader.DEFAULT_INDEX_URL,
                    onDone = {
                        if (tryLoadIndex()) showSetup(SetupMode.SUCCESS)
                        else showSetup(SetupMode.ERROR).also {
                            setupStatus.setText(R.string.index_bad_simple)
                            setupStatus.setTextColor(
                                ContextCompat.getColor(this, R.color.error)
                            )
                        }
                    },
                    onError = { msg ->
                        showSetup(SetupMode.ERROR)
                        setupStatus.text = getString(R.string.dl_failed, msg)
                        setupStatus.setTextColor(
                            ContextCompat.getColor(this, R.color.error)
                        )
                    }
                )
            }
            else -> startManualDownload()
        }
    }

    private fun startManualDownload() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            setupStatus.setText(R.string.url_empty)
            setupStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            return
        }
        setupPrimaryBtn.isEnabled = false
        setupProgress.visibility = View.VISIBLE
        setupProgress.isIndeterminate = true
        doDownload(
            url = url,
            onDone = {
                setupProgress.visibility = View.GONE
                setupPrimaryBtn.isEnabled = true
                if (tryLoadIndex()) showSetup(SetupMode.SUCCESS)
                else {
                    showSetup(SetupMode.ERROR)
                    setupStatus.setText(R.string.index_bad_simple)
                    setupStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
                }
            },
            onError = { msg ->
                setupProgress.visibility = View.GONE
                setupPrimaryBtn.isEnabled = true
                showSetup(SetupMode.ERROR)
                setupStatus.text = getString(R.string.dl_failed, msg)
                setupStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
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
                        setupProgress.isIndeterminate = false
                        setupProgress.setProgressCompat(
                            (done * 100 / total).toInt(), true
                        )
                        setupStatus.text =
                            getString(R.string.dl_progress, done / 1024, total / 1024)
                    } else {
                        setupProgress.isIndeterminate = true
                        setupStatus.text =
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
        val n = poseIndex?.size ?: 0
        statusText.text = resources.getQuantityString(R.plurals.status_idle, n, n)
        refreshPinnedIds()
        adapter.playIntro()
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
                (supportFragmentManager.findFragmentByTag("detail") as? ResultDetailSheet)
                    ?.takeIf { it.sheetId == id }
                    ?.setPinned(id in pinned)
            }
        }.start()
    }

    private fun openDetail(hit: com.posemirror.app.index.SearchHit) {
        val e = hit.entry
        ResultDetailSheet.new(
            id = hit.id,
            pinned = hit.id in adapter.pinnedIds,
            thumbPath = hit.thumbFile.absolutePath,
            title = e?.title ?: hit.id,
            author = e?.author ?: getString(R.string.unknown),
            license = e?.license ?: getString(R.string.unknown),
            source = e?.source ?: "",
            score = hit.score
        ).apply {
            onTogglePin = { id -> togglePin(id) }
        }.show(supportFragmentManager, "detail")
    }

    // ---------- pose model ----------

    /** Resolves the model off the main thread (may download it once). */
    private fun resolveModelAndStartCamera() {
        statusText.setText(R.string.model_loading)
        showSkeleton(true)
        Thread {
            val model = PoseModelProvider.resolve(this)
            runOnUiThread {
                if (model == null) {
                    statusText.setText(R.string.model_failed)
                    statusDot.backgroundTintList =
                        ContextCompat.getColorStateList(this, R.color.error)
                    showSkeleton(false)
                    return@runOnUiThread
                }
                val err = setupLandmarker(model)
                if (err != null) {
                    statusText.text = err
                    showSkeleton(false)
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
        val hits = index.search(vec, k = 9, mirror = mirrorOn)
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
            if (!firstResultsShown) {
                firstResultsShown = true
                showSkeleton(false)
            }
            val n = poseIndex?.size ?: 0
            if (poseFound) {
                statusText.text = resources.getQuantityString(R.plurals.status_live, n, n)
                statusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.gold)
            } else {
                statusText.setText(R.string.pose_hint)
                statusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.faint)
            }
        }
    }

    private fun showSkeleton(show: Boolean) {
        if (show) {
            skeletonView.visibility = View.VISIBLE
            skeletonView.alpha = 1f
            if (Motion.enabled(this) && skeletonPulse == null) {
                skeletonPulse = ObjectAnimator.ofFloat(skeletonView, "alpha", 1f, 0.45f, 1f)
                    .apply {
                        duration = 900
                        repeatCount = ObjectAnimator.INFINITE
                        start()
                    }
            }
        } else {
            skeletonPulse?.cancel()
            skeletonPulse = null
            if (skeletonView.visibility != View.VISIBLE) return
            if (Motion.enabled(this)) {
                skeletonView.animate().alpha(0f).setDuration(Motion.SHORT)
                    .withEndAction {
                        skeletonView.visibility = View.GONE
                        skeletonView.alpha = 1f
                    }.start()
            } else {
                skeletonView.visibility = View.GONE
            }
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
        skeletonPulse?.cancel()
        landmarker?.close()
        cameraExecutor.shutdown()
    }
}
