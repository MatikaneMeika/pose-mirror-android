package com.posemirror.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.posemirror.app.R
import com.posemirror.app.index.IndexManager
import com.posemirror.app.prefs.AppPrefs
import com.posemirror.app.prefs.UpdateFrequency
import com.posemirror.app.work.UpdateScheduler
import com.posemirror.app.work.UpdateWorker
import kotlinx.coroutines.launch
import java.io.File

/**
 * Settings screen: the five update preset groups (changes apply immediately,
 * re-schedule the background worker, and confirm with a snackbar) plus
 * current index statistics.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        val statsText = findViewById<TextView>(R.id.statsText)
        val form = PrefsForm(this)
        findViewById<LinearLayout>(R.id.prefsContainer).addView(form.root)

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
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.prefs_applied),
                        Snackbar.LENGTH_SHORT
                    ).show()
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
                    getString(R.string.stats_empty)
                } else {
                    val mb = stats.bytes / 1024 / 1024
                    getString(R.string.stats_line, stats.count, stats.pinned, mb)
                }
            }
        }.start()
    }
}
