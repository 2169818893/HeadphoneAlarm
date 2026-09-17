package com.headphonealarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_store")

/**
 * 应用级设置持久化（目前仅主题选择）。以字符串键存储，避免 data 层反向依赖 ui 层的枚举。
 */
class SettingsRepository(private val context: Context) {

    val themeKey: Flow<String> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_THEME] ?: DEFAULT_THEME }

    suspend fun setThemeKey(key: String) {
        context.settingsDataStore.edit { it[KEY_THEME] = key }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_key")
        const val DEFAULT_THEME = "material"
    }
}
