package com.posemirror.app.work

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.posemirror.app.index.IndexManager
import com.posemirror.app.index.IndexSweeper
import com.posemirror.app.index.NewIndexEntry
import com.posemirror.app.pose.PoseBatchDetector
import com.posemirror.app.pose.PoseModelProvider
import com.posemirror.app.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Periodic background job: fetch fresh human-pose images from Wikimedia
 * Commons, run the pose model over them on-device, and merge the ones with
 * a usable pose into the local index. Runs under the user's chosen
 * frequency, batch size, network condition, TTL and cap (see [AppPrefs]);
 * [IndexSweeper] enforces the retention policy at the end of each run.
 *
 * Deduplication: every attempted source URL (page URL) is recorded in the
 * sidecar's seen_urls list, which survives purges, so images are never
 * re-downloaded.
 */
class UpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            withContext(Dispatchers.IO) { runUpdate() }
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun runUpdate(): Result {
        val ctx = applicationContext
        val settings = AppPrefs.load(ctx)
        val batchCount = inputData.getInt(KEY_BATCH, settings.batchCount).coerceIn(1, 200)
        val dir = File(ctx.filesDir, INDEX_DIR)
        if (!File(dir, "format.json").exists()) {
            // Starter index not present yet — nothing to append to.
            return Result.success()
        }
        val model = PoseModelProvider.resolve(ctx) ?: return Result.retry()

        val mgr = IndexManager(dir)
        val seen = mgr.loadSeenUrls()
        val usedIds = mgr.readIds().toHashSet()

        val detector = try {
            PoseBatchDetector(ctx, model)
        } catch (_: Exception) {
            return Result.failure()
        }

        var added = 0
        val tmpFiles = ArrayList<File>()
        try {
            val theme = THEMES.random()
            val candidates = searchCommons(theme, (batchCount + 25).coerceAtMost(125))
            val batch = ArrayList<NewIndexEntry>()
            for (c in candidates) {
                if (batch.size >= batchCount) break
                if (c.pageUrl.isBlank() || c.pageUrl in seen) continue
                seen.add(c.pageUrl)
                val tmp = downloadThumb(ctx, c.thumbUrl) ?: continue
                tmpFiles.add(tmp)
                val bmp = BitmapFactory.decodeFile(tmp.absolutePath) ?: continue
                val vec = try { detector.detect(bmp) } catch (_: Exception) { null }
                    ?: continue // no usable human pose — skip
                val id = mgr.newIdFor(c.pageUrl, usedIds)
                usedIds.add(id)
                batch.add(
                    NewIndexEntry(
                        id = id,
                        vector = vec,
                        title = c.title,
                        author = c.author,
                        license = c.license,
                        source = c.pageUrl,
                        thumbFile = tmp,
                        sourceUrl = c.pageUrl
                    )
                )
            }
            if (batch.isNotEmpty()) {
                mgr.append(batch)
                added = batch.size
            }
            // Persist seen URLs even when nothing was added (avoids refetching duds).
            mgr.saveMeta(mgr.loadMeta(), seen)
        } finally {
            for (f in tmpFiles) runCatching { f.delete() }
            detector.close()
        }

        IndexSweeper.sweep(dir, settings.ttlDays, settings.indexCap)
        return Result.success(workDataOf("added" to added))
    }

    // ---------- Wikimedia Commons ----------

    private data class CommonsHit(
        val pageUrl: String,
        val thumbUrl: String,
        val title: String,
        val author: String,
        val license: String
    )

    private fun searchCommons(theme: String, limit: Int): List<CommonsHit> {
        val q = URLEncoder.encode("$theme filetype:bitmap", "UTF-8")
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
            "&formatversion=2&generator=search&gsrsearch=$q&gsrnamespace=6" +
            "&gsrlimit=$limit&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=320"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) return emptyList()
            val pages = JSONObject(conn.inputStream.bufferedReader().readText())
                .optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
            val out = ArrayList<CommonsHit>(pages.length())
            for (i in 0 until pages.length()) {
                val p = pages.getJSONObject(i)
                val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val thumb = ii.optString("thumburl")
                val pageUrl = ii.optString("descriptionurl")
                if (thumb.isBlank() || pageUrl.isBlank()) continue
                val ext = ii.optJSONObject("extmetadata")
                val artist = stripHtml(ext?.optJSONObject("Artist")?.optString("value").orEmpty())
                val lic = ext?.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
                val title = p.optString("title").removePrefix("File:").substringBeforeLast(".")
                out.add(CommonsHit(pageUrl, thumb, title, artist, lic))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadThumb(ctx: Context, url: String): File? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) return null
                val tmp = File.createTempFile("wmthumb", ".jpg", ctx.cacheDir)
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                }
                if (tmp.length() < 1024) { tmp.delete(); return null }
                tmp
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val KEY_BATCH = "batch_count"
        const val INDEX_DIR = "posemirror-index"
        private const val USER_AGENT =
            "pose-mirror-android/0.1 (https://github.com/MatikaneMeika/pose-mirror-android; index updater)"

        /** Pose-heavy search themes, rotated randomly each run. */
        private val THEMES = listOf(
            "dancing", "ballet dancer", "yoga pose", "running",
            "jumping", "gymnastics", "martial arts", "skateboarding",
            "rock climbing", "playing guitar", "tennis player", "swimming"
        )

        private fun stripHtml(s: String): String =
            s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
