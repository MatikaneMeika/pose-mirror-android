package com.posemirror.app.pose

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * Synchronous pose detection for the background index-update worker.
 *
 * Uses RunningMode.IMAGE with the CPU delegate (safe and deterministic off
 * the UI thread). Shares the model resolved by [PoseModelProvider] — no
 * second copy is downloaded. Create one instance per worker run, [close] it
 * when done.
 */
class PoseBatchDetector(context: Context, model: PoseModelProvider.ModelRef) {
    private val modelRef = model

    private val landmarker: PoseLandmarker = run {
        val baseBuilder = BaseOptions.builder().setDelegate(Delegate.CPU)
        when (model) {
            is PoseModelProvider.ModelRef.Asset ->
                baseBuilder.setModelAssetPath(model.assetPath)
            is PoseModelProvider.ModelRef.Mapped ->
                baseBuilder.setModelAssetBuffer(model.buffer)
        }
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseBuilder.build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .build()
        PoseLandmarker.createFromOptions(context, options)
    }

    /**
     * Returns the normalized 66-dim pose vector (same math as [PoseMath.normalize]
     * used for live search), or null when no usable human pose is found.
     */
    fun detect(bitmap: Bitmap): FloatArray? {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val poses = landmarker.detect(mpImage).landmarks()
        if (poses.isEmpty()) return null
        return PoseMath.normalize(poses[0].map {
            PoseMath.Landmark(
                it.x().toDouble(),
                it.y().toDouble(),
                it.visibility().orElse(0f)
            )
        })
    }

    fun close() {
        runCatching { landmarker.close() }
        (modelRef as? PoseModelProvider.ModelRef.Mapped)?.close()
    }
}
