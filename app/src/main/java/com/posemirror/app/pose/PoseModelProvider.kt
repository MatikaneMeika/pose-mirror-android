package com.posemirror.app.pose

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Resolves the pose model bundle, in order of preference:
 * 1. APK assets (bundled at build time via tools/download_model.sh)
 * 2. Previously downloaded copy in the app's private files dir
 * 3. Download from the official MediaPipe model bucket (verified URL)
 *
 * Call off the main thread — step 3 does network I/O. Returns null when the
 * model cannot be obtained (no bundled asset, no cache, no network).
 */
object PoseModelProvider {
    const val MODEL_ASSET = "pose_landmarker_full.task"

    /** Pinned release; verified HTTP 200 on 2026-09-26 (use instead of /latest/). */
    const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task"

    sealed interface ModelRef {
        /** Load from APK assets via BaseOptions.setModelAssetPath. */
        data class Asset(val assetPath: String) : ModelRef

        /**
         * Load from a memory-mapped file via BaseOptions.setModelAssetBuffer.
         * tasks-vision 0.10.14 accepts a direct or mapped ByteBuffer here.
         * Keep this instance alive while the PoseLandmarker uses it, and call
         * [close] only after the landmarker itself is closed.
         */
        class Mapped(val file: File) : ModelRef {
            private val raf = RandomAccessFile(file, "r")
            val buffer: MappedByteBuffer =
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            fun close() { runCatching { raf.close() } }
        }
    }

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_ASSET)

    fun resolve(context: Context): ModelRef? {
        // 1. bundled in the APK
        try {
            context.assets.open(MODEL_ASSET).close()
            return ModelRef.Asset(MODEL_ASSET)
        } catch (_: Exception) { /* not bundled — keep going */ }
        // 2. cached from an earlier run
        val f = modelFile(context)
        if (f.exists() && f.length() > 1_000_000) return ModelRef.Mapped(f)
        // 3. download once, then memory-map
        return try {
            download(f)
            ModelRef.Mapped(f)
        } catch (e: Exception) {
            null
        }
    }

    private fun download(dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("User-Agent", "pose-mirror-android/0.1")
        conn.connect()
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("model download HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() < 1_000_000) {
            tmp.delete()
            throw IllegalStateException("model download too small (${tmp.length()} bytes)")
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("could not move model into place")
        }
    }
}
