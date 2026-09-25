package com.headphonealarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.alarmDataStore: DataStore<Preferences> by preferencesDataStore(name = "alarm_store")

/**
 * 闹钟持久化仓库。整表以 JSON 形式存放，读多写少，避免引入数据库层的复杂度。
 */
class AlarmRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 全量闹钟列表（按时间排序） */
    val alarms: Flow<List<AlarmItem>> = context.alarmDataStore.data
        .map { prefs -> decode(prefs[KEY_ALARMS]) }

    suspend fun getById(id: Long): AlarmItem? =
        alarms.first().firstOrNull { it.id == id }

    suspend fun upsert(item: AlarmItem) = mutate { list ->
        val index = list.indexOfFirst { it.id == item.id }
        if (index >= 0) list.toMutableList().also { it[index] = item } else list + item
    }

    suspend fun remove(id: Long) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: Long, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    suspend fun setSnoozedUntil(id: Long, until: Long?) = mutate { list ->
        list.map { if (it.id == id) it.copy(snoozedUntil = until) else it }
    }

    private suspend fun mutate(block: (List<AlarmItem>) -> List<AlarmItem>) {
        context.alarmDataStore.edit { prefs ->
            val current = decode(prefs[KEY_ALARMS])
            prefs[KEY_ALARMS] = json.encodeToString(block(current).sortedForDisplay())
        }
    }

    private fun decode(raw: String?): List<AlarmItem> {
        if (raw == null) return emptyList()
        // A corrupt/non-list value is NOT an empty alarm store. Failing the read also prevents
        // edit() from replacing the original bytes with a new list on the next mutation.
        return json.decodeFromString<List<AlarmItem>>(raw).sortedForDisplay()
    }

    private fun List<AlarmItem>.sortedForDisplay(): List<AlarmItem> =
        sortedWith(compareBy({ it.hour }, { it.minute }))

    private companion object {
        val KEY_ALARMS = stringPreferencesKey("alarms_json")
    }
}
