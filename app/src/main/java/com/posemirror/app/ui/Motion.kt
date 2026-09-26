package com.posemirror.app.ui

import android.content.Context
import android.provider.Settings

/**
 * Motion discipline: 1-2 animated elements per view, 150-300ms, ease-out.
 * All animation in the app gates on [enabled] so users who disabled
 * system animations get instant state changes instead.
 */
object Motion {
    const val SHORT = 150L
    const val MEDIUM = 250L
    const val STAGGER = 35L
    const val SUCCESS_HOLD = 900L

    /** False when the user turned animations off system-wide. */
    fun enabled(ctx: Context): Boolean =
        Settings.Global.getFloat(
            ctx.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
}
