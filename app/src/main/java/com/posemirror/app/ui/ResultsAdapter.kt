package com.posemirror.app.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.posemirror.app.R
import com.posemirror.app.index.SearchHit
import java.util.Locale

class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.Holder>() {

    private var hits: List<SearchHit> = emptyList()

    /** Ids the user pinned (favorites); shown with a star, never auto-deleted. */
    var pinnedIds: Set<String> = emptySet()

    /** Long-press on a thumbnail toggles the favorite state. */
    var onTogglePin: ((String) -> Unit)? = null

    fun submitList(newHits: List<SearchHit>) {
        hits = newHits
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = hits.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = hits[position]
        // Thumbnails are small (<=320px); decode on the UI thread is fine here.
        val bmp = BitmapFactory.decodeFile(hit.thumbFile.absolutePath)
        if (bmp != null) {
            holder.thumb.setImageBitmap(bmp)
        } else {
            holder.thumb.setImageDrawable(null)
        }
        val pct = (hit.score.coerceIn(-1f, 1f) * 100f)
        holder.score.text = String.format(Locale.US, "%.1f", pct)
        holder.title.text = hit.entry?.title ?: hit.id
        holder.title.contentDescription =
            "${holder.title.text}, author ${hit.entry?.author ?: "unknown"}, " +
                "license ${hit.entry?.license ?: "unknown"}"
        val pinned = hit.id in pinnedIds
        holder.star.visibility = if (pinned) View.VISIBLE else View.GONE
        holder.thumb.setOnLongClickListener {
            onTogglePin?.invoke(hit.id)
            true
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val score: TextView = v.findViewById(R.id.score)
        val title: TextView = v.findViewById(R.id.title)
        val star: TextView = v.findViewById(R.id.star)
    }
}
