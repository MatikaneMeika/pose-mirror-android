package com.posemirror.app.ui

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.posemirror.app.R
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.prefs.NetworkCondition
import com.posemirror.app.prefs.UpdateFrequency

/**
 * The five user-chosen preset groups, shared by the first-launch dialog and
 * the settings screen. Segmented Material toggle buttons instead of
 * platform radio buttons: one glance shows the current choice, one tap
 * changes it. All choices are presets — nothing here invents a number.
 */
class PrefsForm(context: Context) {

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private var suppressListener = false
    private var listener: (() -> Unit)? = null

    private val freqValues = listOf(
        UpdateFrequency.DAILY, UpdateFrequency.EVERY_3_DAYS,
        UpdateFrequency.WEEKLY, UpdateFrequency.OFF
    )
    private val freqLabels = listOf("每天", "每3天", "每周", "关闭")

    private val batchValues = listOf(20, 50, 100)
    private val ttlValues = listOf(7, 30, 90)
    private val capValues = listOf(500, 2000, 5000)

    private val netValues = listOf(NetworkCondition.WIFI, NetworkCondition.WIFI_CHARGING)
    private val netLabels = listOf("仅 WiFi", "WiFi + 充电时")

    private val freqGroup: MaterialButtonToggleGroup
    private val batchGroup: MaterialButtonToggleGroup
    private val ttlGroup: MaterialButtonToggleGroup
    private val capGroup: MaterialButtonToggleGroup
    private val netGroup: MaterialButtonToggleGroup

    init {
        freqGroup = addGroup("更新频率", freqLabels)
        batchGroup = addGroup("每次新增图片", batchValues.map { "$it 张" })
        ttlGroup = addGroup("未收藏图片自动删除", ttlValues.map { "$it 天" })
        capGroup = addGroup("图库数量上限", capValues.map { "$it 张" })
        netGroup = addGroup("更新网络条件", netLabels)
    }

    private fun addGroup(title: String, labels: List<String>): MaterialButtonToggleGroup {
        val ctx = root.context
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(ContextCompat.getColor(ctx, R.color.faint))
            setPadding(0, 28, 0, 8)
        })
        val group = MaterialButtonToggleGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        labels.forEach { label ->
            group.addView(
                MaterialButton(
                    ctx, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = label
                    textSize = 14f
                    id = View.generateViewId()
                    minimumWidth = 0
                    setPadding(36, 0, 36, 0)
                }
            )
        }
        group.addOnButtonCheckedListener { _, _, _ ->
            if (!suppressListener) listener?.invoke()
        }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        })
        return group
    }

    /** Fires when the user changes any preset (not when we set them programmatically). */
    fun setOnChangedListener(l: () -> Unit) {
        listener = l
    }

    fun applySettings(s: AppPrefs.Settings) {
        suppressListener = true
        try {
            checkAt(freqGroup, indexOr(freqValues, s.frequency, AppPrefs.DEF_FREQUENCY))
            checkAt(batchGroup, indexOr(batchValues, s.batchCount, AppPrefs.DEF_BATCH))
            checkAt(ttlGroup, indexOr(ttlValues, s.ttlDays, AppPrefs.DEF_TTL))
            checkAt(capGroup, indexOr(capValues, s.indexCap, AppPrefs.DEF_CAP))
            checkAt(netGroup, indexOr(netValues, s.network, AppPrefs.DEF_NETWORK))
        } finally {
            suppressListener = false
        }
    }

    fun readSettings(): AppPrefs.Settings {
        fun <T> selected(group: MaterialButtonToggleGroup, values: List<T>, fallback: T): T {
            val checkedId = group.checkedButtonId
            val idx = (0 until group.childCount)
                .indexOfFirst { group.getChildAt(it).id == checkedId }
            return if (idx in values.indices) values[idx] else fallback
        }
        return AppPrefs.Settings(
            frequency = selected(freqGroup, freqValues, AppPrefs.DEF_FREQUENCY),
            batchCount = selected(batchGroup, batchValues, AppPrefs.DEF_BATCH),
            ttlDays = selected(ttlGroup, ttlValues, AppPrefs.DEF_TTL),
            indexCap = selected(capGroup, capValues, AppPrefs.DEF_CAP),
            network = selected(netGroup, netValues, AppPrefs.DEF_NETWORK),
        )
    }

    companion object {
        private fun checkAt(group: MaterialButtonToggleGroup, index: Int) {
            if (index in 0 until group.childCount) {
                group.check(group.getChildAt(index).id)
            }
        }

        private fun <T> indexOr(values: List<T>, value: T, fallback: T): Int {
            val i = values.indexOf(value)
            return if (i >= 0) i else values.indexOf(fallback).coerceAtLeast(0)
        }
    }
}
