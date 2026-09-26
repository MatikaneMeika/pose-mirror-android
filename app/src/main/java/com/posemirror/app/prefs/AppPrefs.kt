package com.posemirror.app.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pose_mirror")

/** Background-update frequency presets. `days == null` means updates are off. */
enum class UpdateFrequency(val id: String, val days: Long?) {
    DAILY("daily", 1),
    EVERY_3_DAYS("every3", 3),
    WEEKLY("weekly", 7),
    OFF("off", null);

    companion object {
        fun fromId(id: String?): UpdateFrequency =
            values().firstOrNull { it.id == id } ?: EVERY_3_DAYS
    }
}

/** Network-condition presets for the background updater. */
enum class NetworkCondition(val id: String) {
    WIFI("wifi"),
    WIFI_CHARGING("wifi_charging");

    companion object {
        fun fromId(id: String?): NetworkCondition =
            values().firstOrNull { it.id == id } ?: WIFI
    }
}

/** User-chosen update settings. Defaults are the middle preset of each group. */
object AppPrefs {
    private val KEY_FREQUENCY = stringPreferencesKey("update_frequency")
    private val KEY_BATCH = intPreferencesKey("batch_count")
    private val KEY_TTL = intPreferencesKey("ttl_days")
    private val KEY_CAP = intPreferencesKey("index_cap")
    private val KEY_NETWORK = stringPreferencesKey("network_condition")
    private val KEY_FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
    private val KEY_AUTO_INDEX_TRIED = booleanPreferencesKey("auto_index_tried")

    const val DEF_BATCH = 50
    const val DEF_TTL = 30
    const val DEF_CAP = 2000
    val DEF_FREQUENCY = UpdateFrequency.EVERY_3_DAYS
    val DEF_NETWORK = NetworkCondition.WIFI

    data class Settings(
        val frequency: UpdateFrequency = DEF_FREQUENCY,
        val batchCount: Int = DEF_BATCH,
        val ttlDays: Int = DEF_TTL,
        val indexCap: Int = DEF_CAP,
        val network: NetworkCondition = DEF_NETWORK,
    )

    suspend fun load(context: Context): Settings {
        val p = context.dataStore.data.first()
        return Settings(
            frequency = UpdateFrequency.fromId(p[KEY_FREQUENCY]),
            batchCount = p[KEY_BATCH] ?: DEF_BATCH,
            ttlDays = p[KEY_TTL] ?: DEF_TTL,
            indexCap = p[KEY_CAP] ?: DEF_CAP,
            network = NetworkCondition.fromId(p[KEY_NETWORK]),
        )
    }

    suspend fun save(context: Context, s: Settings) {
        context.dataStore.edit { p ->
            p[KEY_FREQUENCY] = s.frequency.id
            p[KEY_BATCH] = s.batchCount
            p[KEY_TTL] = s.ttlDays
            p[KEY_CAP] = s.indexCap
            p[KEY_NETWORK] = s.network.id
        }
    }

    suspend fun isFirstRunDone(context: Context): Boolean =
        context.dataStore.data.map { it[KEY_FIRST_RUN_DONE] == true }.first()

    suspend fun setFirstRunDone(context: Context) {
        context.dataStore.edit { it[KEY_FIRST_RUN_DONE] = true }
    }

    /** Whether the first-launch automatic starter-index download has run once. */
    suspend fun isAutoIndexTried(context: Context): Boolean =
        context.dataStore.data.map { it[KEY_AUTO_INDEX_TRIED] == true }.first()

    suspend fun setAutoIndexTried(context: Context) {
        context.dataStore.edit { it[KEY_AUTO_INDEX_TRIED] = true }
    }
}
