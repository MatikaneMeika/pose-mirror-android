package com.posemirror.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.prefs.NetworkCondition
import com.posemirror.app.prefs.UpdateFrequency

/**
 * The five user-chosen preset groups, built programmatically so the
 * first-launch dialog and the settings screen share one implementation.
 *
 * All choices are presets — nothing here invents a number for the user.
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

    private val freqGroup: RadioGroup
    private val batchGroup: RadioGroup
    private val ttlGroup: RadioGroup
    private val capGroup: RadioGroup
    private val netGroup: RadioGroup

    init {
        freqGroup = addGroup("更新频率", freqLabels)
        batchGroup = addGroup("每次新增图片", batchValues.map { "$it 张" })
        ttlGroup = addGroup("未收藏图片自动删除", ttlValues.map { "$it 天" })
        capGroup = addGroup("图库数量上限", capValues.map { "$it 张" })
        netGroup = addGroup("更新网络条件", netLabels)
    }

    private fun addGroup(title: String, labels: List<String>): RadioGroup {
        val titleView = TextView(root.context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 28, 0, 4)
        }
        root.addView(titleView)
        val rg = RadioGroup(root.context).apply { orientation = RadioGroup.VERTICAL }
        val tint = ColorStateList.valueOf(0xFFFFD54F.toInt())
        labels.forEachIndexed { i, label ->
            rg.addView(RadioButton(root.context).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFDDDDDD.toInt())
                buttonTintList = tint
                id = i + 1
            })
        }
        rg.setOnCheckedChangeListener { _, _ ->
            if (!suppressListener) listener?.invoke()
        }
        root.addView(rg)
        return rg
    }

    /** Fires when the user changes any preset (not when we set them programmatically). */
    fun setOnChangedListener(l: () -> Unit) {
        listener = l
    }

    fun applySettings(s: AppPrefs.Settings) {
        suppressListener = true
        try {
            freqGroup.check(indexOr(freqValues, s.frequency, AppPrefs.DEF_FREQUENCY) + 1)
            batchGroup.check(indexOr(batchValues, s.batchCount, AppPrefs.DEF_BATCH) + 1)
            ttlGroup.check(indexOr(ttlValues, s.ttlDays, AppPrefs.DEF_TTL) + 1)
            capGroup.check(indexOr(capValues, s.indexCap, AppPrefs.DEF_CAP) + 1)
            netGroup.check(indexOr(netValues, s.network, AppPrefs.DEF_NETWORK) + 1)
        } finally {
            suppressListener = false
        }
    }

    fun readSettings(): AppPrefs.Settings {
        fun <T> selected(group: RadioGroup, values: List<T>, fallback: T): T {
            val idx = group.checkedRadioButtonId - 1
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
        private fun <T> indexOr(values: List<T>, value: T, fallback: T): Int {
            val i = values.indexOf(value)
            return if (i >= 0) i else values.indexOf(fallback).coerceAtLeast(0)
        }
    }
}
