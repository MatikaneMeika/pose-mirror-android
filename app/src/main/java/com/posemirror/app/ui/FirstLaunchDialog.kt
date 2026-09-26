package com.posemirror.app.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.posemirror.app.R
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.prefs.UpdateFrequency
import com.posemirror.app.work.UpdateScheduler
import kotlinx.coroutines.launch

/**
 * Shown once on first launch: the five update preset groups with the
 * middle default pre-selected, so one tap confirms everything. "跳过" keeps
 * the defaults — the dialog never traps the user (ux-guideline: onboarding
 * must offer Skip).
 */
class FirstLaunchDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val form = PrefsForm(ctx)
        form.applySettings(AppPrefs.Settings()) // defaults pre-selected

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
            addView(TextView(ctx).apply {
                text = getString(R.string.firstlaunch_intro)
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.muted))
                setPadding(0, 0, 0, 8)
            })
            addView(form.root)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.firstlaunch_title)
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton(R.string.start, null)
            .setNegativeButton(R.string.skip, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                persist(ctx, form.readSettings()) { dismiss() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                // Skip: keep the defaults, never trap the user.
                persist(ctx, AppPrefs.Settings()) { dismiss() }
            }
        }
        return dialog
    }

    private fun persist(
        ctx: android.content.Context,
        s: AppPrefs.Settings,
        done: () -> Unit
    ) {
        lifecycleScope.launch {
            AppPrefs.save(ctx, s)
            AppPrefs.setFirstRunDone(ctx)
            if (s.frequency == UpdateFrequency.OFF) {
                UpdateScheduler.cancel(ctx)
            } else {
                UpdateScheduler.schedule(ctx, s.frequency, s.batchCount, s.network)
            }
            done()
        }
    }
}
