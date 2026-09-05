package com.local.douyindownloader

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

data class AppSettings(
    val defaultMode: DownloadMode = DownloadMode.MERGE_KEEP,
    val preferH264: Boolean = false,
    val customTreeUri: String? = null,
    val lastUpdateCheckEpochDay: Long = Long.MIN_VALUE,
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

    companion object {
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val PREFER_H264 = booleanPreferencesKey("prefer_h264")
        val CUSTOM_TREE_URI = stringPreferencesKey("custom_tree_uri")
        val LAST_UPDATE_CHECK_EPOCH_DAY = longPreferencesKey("last_update_check_epoch_day")
    }
}
