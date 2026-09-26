package com.posemirror.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.posemirror.app.index.IndexManager
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.prefs.UpdateFrequency
import com.posemirror.app.work.UpdateScheduler
import com.posemirror.app.work.UpdateWorker
import kotlinx.coroutines.launch
import java.io.File

/**
 * Settings screen: the five update preset groups (changes apply immediately
 * and re-schedule the background worker) plus current index statistics.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "设置"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title)

        val form = PrefsForm(this)
        val scroll = ScrollView(this).apply {
            addView(form.root)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        val statsText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, 24, 0, 8)
            text = "图库：读取中…"
        }
        root.addView(statsText)
        setContentView(root)

        lifecycleScope.launch {
            form.applySettings(AppPrefs.load(this@SettingsActivity))
            form.setOnChangedListener {
                val ns = form.readSettings()
                lifecycleScope.launch {
                    AppPrefs.save(this@SettingsActivity, ns)
                    if (ns.frequency == UpdateFrequency.OFF) {
                        UpdateScheduler.cancel(this@SettingsActivity)
                    } else {
                        UpdateScheduler.schedule(
                            this@SettingsActivity, ns.frequency, ns.batchCount, ns.network
                        )
                    }
                }
            }
        }
        refreshStats(statsText)
    }

    private fun refreshStats(tv: TextView) {
        Thread {
            val dir = File(filesDir, UpdateWorker.INDEX_DIR)
            val stats = if (File(dir, "format.json").exists()) {
                runCatching { IndexManager(dir).stats() }.getOrNull()
            } else null
            runOnUiThread {
                tv.text = if (stats == null) {
                    "图库：未下载"
                } else {
                    val mb = stats.bytes / 1024 / 1024
                    "图库：${stats.count} 张（含收藏 ${stats.pinned}）• 占用约 ${mb} MB"
                }
            }
        }.start()
    }
}
