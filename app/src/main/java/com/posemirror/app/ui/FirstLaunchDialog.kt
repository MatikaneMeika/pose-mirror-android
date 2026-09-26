package com.posemirror.app.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.prefs.UpdateFrequency
import com.posemirror.app.work.UpdateScheduler
import kotlinx.coroutines.launch

/**
 * Shown once on first launch: the five update preset groups with the
 * middle default pre-selected, so one tap confirms everything.
 */
class FirstLaunchDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val form = PrefsForm(ctx)
        form.applySettings(AppPrefs.Settings()) // defaults pre-selected

        val intro = TextView(ctx).apply {
            text = "选择图库后台更新的方式（随时可在设置里更改）："
            textSize = 14f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, 0, 0, 8)
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(intro)
            addView(form.root)
        }
        val scroll = ScrollView(ctx).apply { addView(body) }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(scroll)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("图库更新设置")
            .setView(container)
            .setPositiveButton("开始使用", null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = form.readSettings()
                lifecycleScope.launch {
                    AppPrefs.save(ctx, s)
                    AppPrefs.setFirstRunDone(ctx)
                    if (s.frequency == UpdateFrequency.OFF) {
                        UpdateScheduler.cancel(ctx)
                    } else {
                        UpdateScheduler.schedule(ctx, s.frequency, s.batchCount, s.network)
                    }
                    dismiss()
                }
            }
        }
        return dialog
    }
}
