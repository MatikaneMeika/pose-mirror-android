package com.posemirror.app.index

import com.posemirror.app.pose.PoseMath
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/** One entry of manifest.json. */
data class ManifestEntry(
    val id: String,
    val title: String,
    val author: String,
    val license: String,
    val source: String
)

/** One search hit for the UI. */
data class SearchHit(
    val id: String,
    val score: Float, // cosine similarity in [-1, 1]
    val entry: ManifestEntry?,
    val thumbFile: File
)

/**
 * Reads the portable index bundle produced by
 * `python -m posemirror.export_portable` (see docs/INDEX_FORMAT.md):
 * format.json, ids.json, vectors.f32le.bin, manifest.json, thumbs/.
 *
 * Vectors are memory-mapped (not heap-loaded) so large indexes stay cheap.
 */
class PoseIndex private constructor(
    val dir: File,
    val ids: List<String>,
    val manifest: Map<String, ManifestEntry>,
    private val vectors: FloatBuffer,
    val indexVersion: Int
) {
    val size: Int get() = ids.size

    fun thumbFile(id: String): File = File(dir, "thumbs/$id.jpg")

    fun search(query: FloatArray, k: Int, mirror: Boolean): List<SearchHit> {
        val q = if (mirror) PoseMath.mirrorX(query) else query
        return PoseMath.topK(q, vectors, size, k).map { (row, score) ->
            val id = ids[row]
            SearchHit(id, score, manifest[id], thumbFile(id))
        }
    }

    companion object {
        fun load(dir: File): Result<PoseIndex> {
            try {
                val format = JSONObject(File(dir, "format.json").readText())
                val version = format.optInt("index_version", -1)
                if (version != PoseMath.INDEX_VERSION) {
                    return Result.failure(
                        IllegalStateException(
                            "unsupported index_version=$version " +
                                "(app supports ${PoseMath.INDEX_VERSION})"
                        )
                    )
                }
                val dim = format.optInt("vector_dim", -1)
                if (dim != PoseMath.VECTOR_DIM) {
                    return Result.failure(
                        IllegalStateException("unsupported vector_dim=$dim")
                    )
                }

                val idsJson = File(dir, "ids.json").readText()
                val idsArr = org.json.JSONArray(idsJson)
                val ids = ArrayList<String>(idsArr.length())
                for (i in 0 until idsArr.length()) ids.add(idsArr.getString(i))

                val binFile = File(dir, "vectors.f32le.bin")
                val expected = ids.size.toLong() * PoseMath.VECTOR_DIM * 4L
                if (binFile.length() != expected) {
                    return Result.failure(
                        IllegalStateException(
                            "vectors.f32le.bin size ${binFile.length()} " +
                                "!= expected $expected for ${ids.size} ids"
                        )
                    )
                }
                val raf = RandomAccessFile(binFile, "r")
                val mapped = raf.channel.map(
                    FileChannel.MapMode.READ_ONLY, 0, binFile.length()
                )
                raf.close()
                mapped.order(ByteOrder.LITTLE_ENDIAN)
                val vectors = mapped.asFloatBuffer()

                val manifestJson = JSONObject(File(dir, "manifest.json").readText())
                val manifest = HashMap<String, ManifestEntry>()
                for (id in ids) {
                    val o = manifestJson.optJSONObject(id) ?: continue
                    manifest[id] = ManifestEntry(
                        id = id,
                        title = o.optString("title", id),
                        author = stripHtml(o.optString("author", "")),
                        license = o.optString("license", ""),
                        source = o.optString("source", "")
                    )
                }

                return Result.success(PoseIndex(dir, ids, manifest, vectors, version))
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }

        /** Commons metadata sometimes embeds HTML in author; strip tags. */
        private fun stripHtml(s: String): String =
            s.replace(Regex("<[^>]*>"), "").trim()
    }
}
