package com.posemirror.app.index

import java.io.File

/**
 * Enforces the user's retention policy on a local index directory:
 * 1. delete unpinned entries older than [ttlDays]
 * 2. then, while the entry count exceeds [cap], delete the oldest unpinned
 *    entries first
 *
 * Pinned (favorite) entries are never deleted; if the pinned count alone
 * exceeds the cap, the directory is allowed to stay over the cap.
 *
 * Runs on app start and at the end of every [com.posemirror.app.work.UpdateWorker]
 * run.
 */
object IndexSweeper {

    data class SweepResult(val deleted: List<String>)

    fun sweep(dir: File, ttlDays: Int, cap: Int): SweepResult {
        val mgr = IndexManager(dir)
        val deleted = ArrayList<String>()

        // 1. TTL: drop expired, unpinned entries
        val now = System.currentTimeMillis() / 1000
        val ttlSec = ttlDays.toLong() * 86400
        val expired = mgr.loadMeta()
            .filter { !it.value.pinned && it.value.addedAt < now - ttlSec }
            .keys
        if (expired.isNotEmpty()) {
            mgr.purge(expired)
            deleted.addAll(expired)
        }

        // 2. capacity: drop oldest unpinned entries until under the cap
        val meta = mgr.loadMeta()
        if (meta.size > cap) {
            val victims = meta
                .filter { !it.value.pinned }
                .entries
                .sortedBy { it.value.addedAt }
                .take((meta.size - cap).coerceAtLeast(0))
                .map { it.key }
            if (victims.isNotEmpty()) {
                mgr.purge(victims.toSet())
                deleted.addAll(victims)
            }
        }
        return SweepResult(deleted)
    }
}
