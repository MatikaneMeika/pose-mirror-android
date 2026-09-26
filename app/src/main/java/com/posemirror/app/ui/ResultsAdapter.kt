package com.posemirror.app.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.posemirror.app.R
import com.posemirror.app.index.SearchHit

/**
 * Live top-k results. The grid updates a few times per second while the
 * camera runs, so motion is deliberately restrained: a staggered entry plays
 * exactly once per fresh population ([playIntro]); every later update just
 * rebinds. Tap opens the detail sheet, long-press toggles the favorite.
 */
class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.Holder>() {

    private var hits: List<SearchHit> = emptyList()

    /** Ids the user pinned (favorites); shown with a star, never auto-deleted. */
    var pinnedIds: Set<String> = emptySet()

    var onTogglePin: ((String) -> Unit)? = null
    var onOpenDetail: ((SearchHit) -> Unit)? = null

    private var introArmed = false
    private val introPlayed = mutableSetOf<Int>()

    fun submitList(newHits: List<SearchHit>) {
        hits = newHits
        notifyDataSetChanged()
    }

    /** Arm a one-time staggered entry animation for the next bind pass. */
    fun playIntro() {
        introArmed = true
        introPlayed.clear()
    }

    override fun getItemCount(): Int = hits.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = hits[position]
        val bmp = BitmapFactory.decodeFile(hit.thumbFile.absolutePath)
        if (bmp != null) holder.thumb.setImageBitmap(bmp)
        else holder.thumb.setImageDrawable(null)

        holder.score.text = (hit.score.coerceIn(0f, 1f) * 100).toInt().toString()
        holder.title.text = hit.entry?.title ?: hit.id
        holder.title.contentDescription =
            "${holder.title.text}, author ${hit.entry?.author ?: "unknown"}, " +
                "license ${hit.entry?.license ?: "unknown"}"
        val pinned = hit.id in pinnedIds
        holder.star.visibility = if (pinned) View.VISIBLE else View.GONE
        // Instant favorite feedback: a quick pop only on the pin transition,
        // never on routine rebinds (the grid refreshes several times/second).
        val ctx = holder.itemView.context
        if (pinned && !holder.wasPinned && Motion.enabled(ctx)) {
            holder.star.scaleX = 0.6f
            holder.star.scaleY = 0.6f
            holder.star.animate().scaleX(1f).scaleY(1f)
                .setDuration(Motion.SHORT)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        holder.wasPinned = pinned

        holder.itemView.setOnClickListener { onOpenDetail?.invoke(hit) }
        holder.itemView.setOnLongClickListener {
            onTogglePin?.invoke(hit.id)
            true
        }

        if (introArmed && position !in introPlayed && Motion.enabled(ctx)) {
            introPlayed.add(position)
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 24f
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(position * Motion.STAGGER)
                .setDuration(Motion.MEDIUM)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    holder.itemView.alpha = 1f
                    holder.itemView.translationY = 0f
                }
                .start()
            if (introPlayed.size >= hits.size) introArmed = false
        } else {
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val score: TextView = v.findViewById(R.id.score)
        val title: TextView = v.findViewById(R.id.title)
        val star: ImageView = v.findViewById(R.id.star)

        /** Last bound pin state, so the pop animation fires on transition only. */
        var wasPinned: Boolean = false
    }
}
