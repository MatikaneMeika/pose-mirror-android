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

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                // The exporter zips a top-level folder (index-v1/...); drop it.
                val rel = entry.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val out = File(destDir, rel)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
