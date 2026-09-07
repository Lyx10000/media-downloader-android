package com.local.multiplatformdownloader.core.settings


import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.SourcePlatform
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class AppSettings(
    val defaultMode: DownloadMode = DownloadMode.MERGE_KEEP,
    val preferH264: Boolean = false,
    val customTreeUri: String? = null,
    val lastUpdateCheckEpochDay: Long = Long.MIN_VALUE,
    val batchDownloadSettings: BatchDownloadSettings = BatchDownloadSettings(),
    val platformRiskUntil: Map<SourcePlatform, Long> = emptyMap(),
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            AppSettings(
                defaultMode = DownloadMode.fromWire(
                    preferences[DEFAULT_MODE] ?: DownloadMode.MERGE_KEEP.wireValue,
                ),
                preferH264 = preferences[PREFER_H264] ?: false,
                customTreeUri = preferences[CUSTOM_TREE_URI]?.takeIf(String::isNotBlank),
                lastUpdateCheckEpochDay = preferences[LAST_UPDATE_CHECK_EPOCH_DAY] ?: Long.MIN_VALUE,
                batchDownloadSettings = BatchDownloadSettings.fromJson(
                    preferences[BATCH_DOWNLOAD_SETTINGS] ?: "{}",
                ),
                platformRiskUntil = parsePlatformRiskUntil(
                    preferences[PLATFORM_RISK_UNTIL] ?: "{}",
                ),
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDefaultMode(mode: DownloadMode) {
        dataStore.edit { it[DEFAULT_MODE] = mode.wireValue }
    }

    suspend fun setPreferH264(value: Boolean) {
        dataStore.edit { it[PREFER_H264] = value }
    }

    suspend fun setCustomTree(uri: String?) {
        dataStore.edit { preferences ->
            if (uri.isNullOrBlank()) preferences.remove(CUSTOM_TREE_URI)
            else preferences[CUSTOM_TREE_URI] = uri
        }
    }

    suspend fun setLastUpdateCheckEpochDay(value: Long) {
        dataStore.edit { it[LAST_UPDATE_CHECK_EPOCH_DAY] = value }
    }

    suspend fun setBatchDownloadSettings(value: BatchDownloadSettings) {
        dataStore.edit { it[BATCH_DOWNLOAD_SETTINGS] = value.toJson() }
    }

    suspend fun setPlatformRiskUntil(value: Map<SourcePlatform, Long>) {
        dataStore.edit { preferences ->
            preferences[PLATFORM_RISK_UNTIL] = encodePlatformRiskUntil(value)
        }
    }

    companion object {
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val PREFER_H264 = booleanPreferencesKey("prefer_h264")
        val CUSTOM_TREE_URI = stringPreferencesKey("custom_tree_uri")
        val LAST_UPDATE_CHECK_EPOCH_DAY = longPreferencesKey("last_update_check_epoch_day")
        val BATCH_DOWNLOAD_SETTINGS = stringPreferencesKey("batch_download_settings")
        val PLATFORM_RISK_UNTIL = stringPreferencesKey("platform_risk_until")
    }
}

internal fun encodePlatformRiskUntil(value: Map<SourcePlatform, Long>): String = JSONObject().apply {
    value.filterValues { it > 0L }.forEach { (platform, until) ->
        put(platform.wireValue, until)
    }
}.toString()

internal fun parsePlatformRiskUntil(value: String): Map<SourcePlatform, Long> = runCatching {
    val root = JSONObject(value)
    SourcePlatform.entries.mapNotNull { platform ->
        root.optLong(platform.wireValue).takeIf { it > 0L }?.let { platform to it }
    }.toMap()
}.getOrDefault(emptyMap())
