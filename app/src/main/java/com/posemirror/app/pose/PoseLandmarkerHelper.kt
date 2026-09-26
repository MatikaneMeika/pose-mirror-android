package com.posemirror.app.pose

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Wraps MediaPipe Tasks PoseLandmarker in LIVE_STREAM mode.
 *
 * The model file must be in `src/main/assets/` (see tools/download_model.sh).
 * Tries the GPU delegate first, falls back to CPU.
 * Result callbacks arrive on a MediaPipe background thread.
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val onResult: (List<PoseMath.Landmark>) -> Unit,
    private val onError: (String) -> Unit
) {
    private var landmarker: PoseLandmarker? = null

    /** Returns null on success, or a human-readable error. */
    fun setup(): String? {
        try {
            context.assets.open(MODEL_ASSET).close()
        } catch (e: Exception) {
            return "pose model missing from assets — run tools/download_model.sh, then rebuild"
        }
        var lastError: Exception? = null
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setResultListener { result, _ -> handleResult(result) }
                    .setErrorListener { e -> onError(e.message ?: e.toString()) }
                    .build()
                landmarker = PoseLandmarker.createFromOptions(context, options)
                return null
            } catch (e: Exception) {
                lastError = e
            }
        }
        return "could not start PoseLandmarker: ${lastError?.message}"
    }

    fun detect(bitmap: Bitmap) {
        val lm = landmarker ?: return
        val mpImage = BitmapImageBuilder(bitmap).build()
        lm.detectAsync(mpImage, SystemClock.uptimeMillis())
    }

    private fun handleResult(result: PoseLandmarkerResult) {
        val poses = result.landmarks()
        if (poses.isEmpty()) {
            onResult(emptyList())
            return
        }
        onResult(poses[0].map {
            PoseMath.Landmark(
                it.x().toDouble(),
                it.y().toDouble(),
                it.visibility().orElse(0f)
            )
        })
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }

    companion object {
        const val MODEL_ASSET = "pose_landmarker_full.task"
    }
}
