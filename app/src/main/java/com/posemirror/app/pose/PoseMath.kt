package com.posemirror.app.pose

import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pose normalization + matching math.
 *
 * Line-by-line Kotlin port of the pseudocode in docs/INDEX_FORMAT.md
 * (PC repo) and of `normalize_pose` in `src/posemirror/pose.py`.
 * Any change here must be mirrored there and requires an index version bump.
 */
object PoseMath {
    const val N_LANDMARKS = 33
    const val VECTOR_DIM = 66
    const val INDEX_VERSION = 1
    const val VISIBILITY_THRESHOLD = 0.5f

    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24

    private const val SCALE_EPS = 1e-6
    private const val NORM_EPS = 1e-9

    data class Landmark(val x: Double, val y: Double, val visibility: Float)

    /**
     * Normalize 33 (x, y, visibility) landmarks into a 66-dim unit vector.
     * Returns null when fewer than 50% of joints are visible or the pose is
     * degenerate. Uses Double intermediates, like the NumPy implementation.
     */
    fun normalize(landmarks: List<Landmark>): FloatArray? {
        if (landmarks.size != N_LANDMARKS) return null
        val visible = landmarks.count { it.visibility >= VISIBILITY_THRESHOLD }
        // Python: mean(vis >= 0.5) < 0.5  <=>  visible * 2 < 33
        if (visible * 2 < N_LANDMARKS) return null

        val midHipX = (landmarks[LEFT_HIP].x + landmarks[RIGHT_HIP].x) / 2.0
        val midHipY = (landmarks[LEFT_HIP].y + landmarks[RIGHT_HIP].y) / 2.0
        val midShoulderX = (landmarks[LEFT_SHOULDER].x + landmarks[RIGHT_SHOULDER].x) / 2.0
        val midShoulderY = (landmarks[LEFT_SHOULDER].y + landmarks[RIGHT_SHOULDER].y) / 2.0

        var scale = hypot(midShoulderX - midHipX, midShoulderY - midHipY)
        if (scale < SCALE_EPS) {
            // Fallback: largest distance between any two joints.
            var maxD = 0.0
            for (i in 0 until N_LANDMARKS) {
                for (j in i + 1 until N_LANDMARKS) {
                    val d = hypot(
                        landmarks[i].x - landmarks[j].x,
                        landmarks[i].y - landmarks[j].y
                    )
                    if (d > maxD) maxD = d
                }
            }
            scale = maxD
        }
        if (scale < SCALE_EPS) return null

        val q = DoubleArray(VECTOR_DIM)
        for (i in 0 until N_LANDMARKS) {
            q[i * 2] = (landmarks[i].x - midHipX) / scale
            q[i * 2 + 1] = (landmarks[i].y - midHipY) / scale
        }
        var sumSq = 0.0
        for (v in q) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm < NORM_EPS) return null

        return FloatArray(VECTOR_DIM) { (q[it] / norm).toFloat() }
    }

    /**
     * Mirror toggle: negate every x component (even indices) of the
     * *normalized* query vector. Exact (see INDEX_FORMAT.md), no
     * re-normalization needed.
     */
    fun mirrorX(vec: FloatArray): FloatArray {
        val out = vec.copyOf()
        for (i in out.indices step 2) out[i] = -out[i]
        return out
    }

    /**
     * Cosine top-k over row-major unit vectors in [vectors] (n rows).
     * Returns (rowIndex, similarity in [-1, 1]) sorted by similarity desc.
     */
    fun topK(query: FloatArray, vectors: FloatBuffer, n: Int, k: Int): List<Pair<Int, Float>> {
        require(query.size == VECTOR_DIM)
        val scored = ArrayList<Pair<Int, Float>>(n)
        for (row in 0 until n) {
            val base = row * VECTOR_DIM
            var dot = 0f
            for (d in 0 until VECTOR_DIM) {
                dot += query[d] * vectors.get(base + d)
            }
            scored.add(row to dot.coerceIn(-1f, 1f))
        }
        scored.sortByDescending { it.second }
        return scored.take(k.coerceAtLeast(1))
    }
}
