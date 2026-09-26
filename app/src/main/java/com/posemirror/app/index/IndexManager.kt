package com.posemirror.app.index

import com.posemirror.app.pose.PoseMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Sidecar entry for one indexed image, stored in index_meta.json. */
data class MetaEntry(
    val addedAt: Long,      // epoch seconds
    val pinned: Boolean,    // favorites: never auto-deleted
    val sourceUrl: String   // canonical source URL, used for dedup
)

/** A new candidate image validated by the update worker. */
data class NewIndexEntry(
    val id: String,
    val vector: FloatArray, // 66-dim, already L2-normalized
    val title: String,
    val author: String,
    val license: String,
    val source: String,     // page URL shown in the results list
    val thumbFile: File,    // local temp file copied into thumbs/
    val sourceUrl: String   // canonical URL for dedup (usually == source)
)

data class IndexStats(val count: Int, val pinned: Int, val bytes: Long)

/**
 * Mutates a portable index directory (format.json / ids.json /
 * vectors.f32le.bin / manifest.json / thumbs/{id}.jpg) in place and keeps
 * the sidecar index_meta.json in sync.
 *
 * Callers must serialize mutations (the update worker is a unique work, the
 * sweeper runs in the same flow, and UI pin toggles are short).
 */
class IndexManager(val dir: File) {

    // ---------- sidecar ----------

    private val metaFile get() = File(dir, "index_meta.json")

    fun loadMeta(): MutableMap<String, MetaEntry> {
        val out = LinkedHashMap<String, MetaEntry>()
        val f = metaFile
        if (!f.exists()) return out
        val root = try { JSONObject(f.readText()) } catch (_: Exception) { return out }
        val ids = root.optJSONObject("ids") ?: return out
        val keys = ids.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val o = ids.getJSONObject(id)
            out[id] = MetaEntry(
                addedAt = o.optLong("added_at", 0),
                pinned = o.optBoolean("pinned", false),
                sourceUrl = o.optString("source_url", "")
            )
        }
        return out
    }

    fun loadSeenUrls(): MutableSet<String> {
        val f = metaFile
        if (!f.exists()) return LinkedHashSet()
        val arr = try { JSONObject(f.readText()).optJSONArray("seen_urls") }
            catch (_: Exception) { return LinkedHashSet() }
        val out = LinkedHashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    fun saveMeta(meta: Map<String, MetaEntry>, seenUrls: Set<String>? = null) {
        val keepSeen = seenUrls ?: loadSeenUrls()
        val idsObj = JSONObject()
        for ((id, e) in meta) {
            val o = JSONObject()
            o.put("added_at", e.addedAt)
            o.put("pinned", e.pinned)
            o.put("source_url", e.sourceUrl)
            idsObj.put(id, o)
        }
        val root = JSONObject()
        root.put("ids", idsObj)
        root.put("seen_urls", JSONArray(keepSeen.toList()))
        fWriteAtomic(metaFile, root.toString())
    }

    /**
     * Give every id of a freshly downloaded starter bundle a sidecar entry
     * (added_at = install time, unpinned). Idempotent.
     */
    fun ensureMetaFor(ids: List<String>) {
        val meta = loadMeta()
        var changed = false
        val now = System.currentTimeMillis() / 1000
        for (id in ids) {
            if (id !in meta) {
                meta[id] = MetaEntry(now, false, "")
                changed = true
            }
        }
        if (changed) saveMeta(meta)
    }

    /** Deterministic, collision-free id derived from the source URL. */
    fun newIdFor(sourceUrl: String, existing: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var n = 0
        while (true) {
            val hash = digest.digest("$sourceUrl#$n".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val id = "wm_" + hash.take(12)
            if (id !in existing) return id
            n++
        }
    }

    // ---------- mutation ----------

    fun readIds(): List<String> {
        val f = File(dir, "ids.json")
        val arr = JSONArray(f.readText())
        return List(arr.length()) { arr.getString(it) }
    }

    fun append(entries: List<NewIndexEntry>) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        // vectors: append 66-float little-endian rows
        val bin = File(dir, "vectors.f32le.bin")
        RandomAccessFile(bin, "rw").use { raf ->
            raf.seek(raf.length())
            val buf = ByteBuffer
                .allocate(entries.size * PoseMath.VECTOR_DIM * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (e in entries) {
                require(e.vector.size == PoseMath.VECTOR_DIM) { "bad vector dim" }
                for (v in e.vector) buf.putFloat(v)
            }
            buf.flip()
            raf.channel.write(buf)
        }
        val ids = readIds().toMutableList()
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        val meta = loadMeta()
        val seen = loadSeenUrls()
        for (e in entries) {
            ids.add(e.id)
            val o = JSONObject()
            o.put("title", e.title)
            o.put("author", e.author)
            o.put("license", e.license)
            o.put("source", e.source)
            manifest.put(e.id, o)
            e.thumbFile.copyTo(File(dir, "thumbs/${e.id}.jpg"), overwrite = true)
            meta[e.id] = MetaEntry(now, false, e.sourceUrl)
            seen.add(e.sourceUrl)
        }
        fWriteAtomic(File(dir, "ids.json"), JSONArray(ids).toString())
        fWriteAtomic(File(dir, "manifest.json"), manifest.toString())
        saveMeta(meta, seen)
    }

    /** Remove ids and compact vectors/ids/manifest/thumbs/sidecar. */
    fun purge(idsToDelete: Set<String>) {
        if (idsToDelete.isEmpty()) return
        val ids = readIds()
        val keepRows = ids.mapIndexedNotNull { row, id ->
            if (id in idsToDelete) null else row
        }
        if (keepRows.size == ids.size) return
        val rowSize = PoseMath.VECTOR_DIM * 4
        val oldBin = File(dir, "vectors.f32le.bin")
        val newBin = File(dir, "vectors.f32le.bin.new")
        val rowBuf = ByteArray(rowSize)
        RandomAccessFile(oldBin, "r").use { rin ->
            RandomAccessFile(newBin, "rw").use { wout ->
                for (row in keepRows) {
                    rin.seek(row.toLong() * rowSize)
                    rin.readFully(rowBuf)
                    wout.write(rowBuf)
                }
            }
        }
        oldBin.delete()
        newBin.renameTo(oldBin)
        val keepIds = keepRows.map { ids[it] }
        fWriteAtomic(File(dir, "ids.json"), JSONArray(keepIds).toString())
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        for (id in idsToDelete) {
            manifest.remove(id)
            File(dir, "thumbs/$id.jpg").delete()
        }
        fWriteAtomic(File(dir, "manifest.json"), manifest.toString())
        val meta = loadMeta()
        for (id in idsToDelete) meta.remove(id)
        saveMeta(meta) // seen_urls survive purges so we never re-download
    }

    // ---------- favorites / stats ----------

    fun setPinned(id: String, pinned: Boolean) {
        val meta = loadMeta()
        val e = meta[id] ?: return
        meta[id] = e.copy(pinned = pinned)
        saveMeta(meta)
    }

    fun pinnedIds(): Set<String> = loadMeta().filterValues { it.pinned }.keys

    fun stats(): IndexStats {
        val meta = loadMeta()
        var bytes = 0L
        for (name in listOf("vectors.f32le.bin", "ids.json", "manifest.json",
            "format.json", "index_meta.json")) {
            val f = File(dir, name)
            if (f.exists()) bytes += f.length()
        }
        bytes += File(dir, "thumbs").listFiles()?.sumOf { it.length() } ?: 0
        return IndexStats(meta.size, meta.count { it.value.pinned }, bytes)
    }

    companion object {
        private fun fWriteAtomic(target: File, text: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                // fall back to a direct write rather than losing data
                tmp.delete()
                target.writeText(text)
            }
        }
    }
}
