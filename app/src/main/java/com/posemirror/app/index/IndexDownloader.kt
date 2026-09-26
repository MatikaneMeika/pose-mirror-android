package com.posemirror.app.index

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * First-run index bootstrap: downloads the portable index zip
 * (see README: `python -m posemirror.export_portable` output zipped)
 * and extracts it into [destDir].
 */
object IndexDownloader {

    /**
     * Starter index hosted on this repo's releases. Downloaded automatically
     * on first launch (with a progress UI); the manual-URL input only appears
     * if this fails.
     *
     * NOTE: this release asset does not exist yet — upload a starter bundle
     * (e.g. a few hundred pose images exported with
     * `python -m posemirror.export_portable`, zipped as index-v1.zip) to a
     * release tagged `index-v1` before shipping, otherwise first launch falls
     * back to the manual URL input.
     */
    const val DEFAULT_INDEX_URL =
        "https://github.com/MatikaneMeika/pose-mirror-android/releases/download/index-v1/index-v1.zip"

    fun download(
        context: Context,
        url: String,
        destDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                destDir.mkdirs()
                val zipFile = File(context.cacheDir, "index-v1.zip")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.coerceAtLeast(-1L)
                var downloaded = 0L
                conn.inputStream.use { input ->
                    zipFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
                unzip(zipFile, destDir)
                zipFile.delete()
                onDone()
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }.start()
    }

    /**
     * Handles both layouts:
     * - exporter zips a top-level folder: index-v1/format.json ...
     * - flat zip: format.json at the root.
     * Guards against zip-slip via canonical-path containment.
     */
    private fun unzip(zipFile: File, destDir: File) {
        val destCanonical = destDir.canonicalPath + File.separator
        // First pass: does every entry share one common top-level dir?
        val names = ArrayList<String>()
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.isNotEmpty()) names.add(e.name)
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val tops = names.map { it.substringBefore('/') }.toSet()
        val stripTop = tops.size == 1 && names.any { it.contains('/') }

        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                var rel = entry.name
                if (stripTop) rel = rel.substringAfter('/', "")
                if (rel.isNotEmpty() && !entry.isDirectory) {
                    val out = File(destDir, rel)
                    // zip-slip guard
                    if (!out.canonicalPath.startsWith(destCanonical)) {
                        throw SecurityException("zip entry escapes dest: ${entry.name}")
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
