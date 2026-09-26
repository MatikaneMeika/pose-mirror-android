package com.posemirror.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.posemirror.app.R
import java.util.Locale

/**
 * Detail view for one result: large preview, match score, and the full
 * attribution (author / license / source) every image carries. The favorite
 * toggle here mirrors the long-press action on the grid cell.
 */
class ResultDetailSheet : BottomSheetDialogFragment() {

    /** Set by the host before show(); not retained across config changes. */
    var onTogglePin: ((String) -> Unit)? = null

    private lateinit var pinBtn: MaterialButton

    /** The result id this sheet is showing (read-only for the host). */
    var sheetId: String = ""
        private set
    private var pinned: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_result_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val a = requireArguments()
        sheetId = a.getString(ARG_ID, "")
        pinned = a.getBoolean(ARG_PINNED, false)
        val thumbPath = a.getString(ARG_THUMB, "")
        val title = a.getString(ARG_TITLE, "")
        val author = a.getString(ARG_AUTHOR, "")
        val license = a.getString(ARG_LICENSE, "")
        val source = a.getString(ARG_SOURCE, "")
        val score = a.getFloat(ARG_SCORE, 0f)

        val bmp = BitmapFactory.decodeFile(thumbPath)
        if (bmp != null) {
            view.findViewById<ImageView>(R.id.detailThumb).setImageBitmap(bmp)
        }
        view.findViewById<TextView>(R.id.detailTitle).text = title
        view.findViewById<TextView>(R.id.detailScore).text =
            String.format(Locale.US, "%d", (score.coerceIn(0f, 1f) * 100).toInt())
        view.findViewById<TextView>(R.id.detailMeta).text =
            getString(R.string.detail_meta, author, license)

        pinBtn = view.findViewById(R.id.pinBtn)
        renderPin()
        pinBtn.setOnClickListener { onTogglePin?.invoke(sheetId) }

        val sourceBtn = view.findViewById<MaterialButton>(R.id.sourceBtn)
        if (source.isBlank()) {
            sourceBtn.visibility = View.GONE
        } else {
            sourceBtn.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source)))
            }
        }
    }

    /** Refresh the pin button after the host toggles the state. */
    fun setPinned(nowPinned: Boolean) {
        pinned = nowPinned
        if (::pinBtn.isInitialized) renderPin()
    }

    private fun renderPin() {
        pinBtn.text = getString(if (pinned) R.string.pinned_label else R.string.pin_label)
        pinBtn.iconTint =
            ContextCompat.getColorStateList(
                requireContext(),
                if (pinned) R.color.gold else R.color.muted
            )
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_PINNED = "pinned"
        private const val ARG_THUMB = "thumb"
        private const val ARG_TITLE = "title"
        private const val ARG_AUTHOR = "author"
        private const val ARG_LICENSE = "license"
        private const val ARG_SOURCE = "source"
        private const val ARG_SCORE = "score"

        fun new(
            id: String,
            pinned: Boolean,
            thumbPath: String,
            title: String,
            author: String,
            license: String,
            source: String,
            score: Float
        ): ResultDetailSheet = ResultDetailSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, id)
                putBoolean(ARG_PINNED, pinned)
                putString(ARG_THUMB, thumbPath)
                putString(ARG_TITLE, title)
                putString(ARG_AUTHOR, author)
                putString(ARG_LICENSE, license)
                putString(ARG_SOURCE, source)
                putFloat(ARG_SCORE, score)
            }
        }
    }
}
