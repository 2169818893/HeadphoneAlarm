package com.headphonealarm.data

import android.content.Context
import android.util.Log
import com.headphonealarm.util.RingtoneImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialize database changes with the matching AlarmManager operation. A UI coroutine can be
 * cancelled after DataStore commits, so a started mutation must finish scheduling as well.
 */
class AlarmOperations(
    private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler
) {
    private val mutex = Mutex()
    private val _failedScheduleIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Persistence may succeed even if the OS refuses to register/cancel an alarm. */
    val failedScheduleIds: StateFlow<Set<Long>> = _failedScheduleIds.asStateFlow()

    private fun reportSchedule(id: Long, success: Boolean) {
        _failedScheduleIds.value = if (success) _failedScheduleIds.value - id
        else _failedScheduleIds.value + id
    }

    suspend fun create(item: AlarmItem): Long = committed {
        val nextId = maxOf(
            System.currentTimeMillis(),
            (repository.alarms.first().maxOfOrNull { it.id } ?: 0L) + 1L
        )
        val newItem = item.copy(id = nextId, snoozedUntil = null, consumedByFire = false)
        repository.upsert(newItem)
        if (newItem.enabled) reportSchedule(newItem.id, scheduler.schedule(newItem))
        newItem.id
    }

    suspend fun save(item: AlarmItem, preserveEnabled: Boolean = false) = committed {
        val old = repository.getById(item.id)
        // Editing an alarm invalidates any pending snooze, including an already-consumed
        // one-shot alarm whose regular enabled switch is now off.
        val current = item.copy(
            enabled = if (preserveEnabled && old != null) old.enabled else item.enabled,
            snoozedUntil = null,
            consumedByFire = false
        )
        repository.upsert(current)
        val snoozeCancelled = scheduler.cancelSnooze(current)
        val regularUpdated = if (current.enabled) scheduler.schedule(current) else scheduler.cancelRegular(current)
        reportSchedule(current.id, snoozeCancelled && regularUpdated)
        if (old?.ringtoneUri != current.ringtoneUri) cleanupUnreferenced(listOfNotNull(old?.ringtoneUri))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = committed {
        val old = repository.getById(id) ?: return@committed
        val current = old.copy(enabled = enabled, snoozedUntil = null, consumedByFire = false)
        repository.upsert(current)
        val snoozeCancelled = scheduler.cancelSnooze(current)
        val regularUpdated = if (enabled) scheduler.schedule(current) else scheduler.cancelRegular(current)
        reportSchedule(id, snoozeCancelled && regularUpdated)
    }

    suspend fun delete(id: Long) = committed {
        val old = repository.getById(id) ?: return@committed
        repository.remove(id)
        reportSchedule(id, scheduler.cancel(old))
        cleanupUnreferenced(listOfNotNull(old.ringtoneUri))
    }

    suspend fun rescheduleAll() = committed {
        val failed = scheduler.rescheduleAll(repository).toMutableSet()
        val now = System.currentTimeMillis()
        repository.alarms.first().forEach { alarm ->
            val until = alarm.snoozedUntil
            val trigger = alarm.snoozeTriggerTime(now)
            val snoozeUpdated = if (until != null && trigger != null) {
                // OS 触发时间可以补偿延误，但 Intent 的令牌始终是原始持久化时间。
                scheduler.scheduleSnooze(alarm, trigger, until)
            } else {
                // 无记录时也取消残留系统 PendingIntent；不可恢复的旧贪睡清除记录。
                if (until != null) repository.setSnoozedUntil(alarm.id, null)
                scheduler.cancelSnooze(alarm)
            }
            if (!snoozeUpdated) failed.add(alarm.id)
        }
        // A failed deletion has no repository row, but its old OS alarm still needs cancelling.
        val storedIds = repository.alarms.first().mapTo(mutableSetOf()) { it.id }
        (_failedScheduleIds.value - storedIds).forEach { id ->
            if (!scheduler.cancel(AlarmItem(id = id, hour = 0, minute = 0))) failed.add(id)
        }
        _failedScheduleIds.value = failed
    }

    /** A fired snapshot must not undo a newer edit or manual toggle. */
    suspend fun recordFire(fired: AlarmItem) = committed {
        val current = repository.getById(fired.id) ?: return@committed
        if (fired.repeatDays.isEmpty()) {
            // The user may have pressed snooze before this asynchronous accounting runs.
            // Ignore only that bookkeeping field, not an actual edit or manual toggle.
            if (current.copy(
                    snoozedUntil = fired.snoozedUntil,
                    consumedByFire = fired.consumedByFire
                ) == fired && current.enabled
            ) {
                repository.upsert(current.copy(enabled = false, consumedByFire = true))
                reportSchedule(current.id, scheduler.cancelRegular(current))
            }
        } else if (current.enabled) {
            reportSchedule(current.id, scheduler.schedule(current))
        }
    }

    suspend fun rescheduleRepeatedFire(id: Long) = committed {
        val current = repository.getById(id) ?: return@committed
        if (current.enabled && current.repeatDays.isNotEmpty()) {
            reportSchedule(id, scheduler.schedule(current))
        }
    }

    /** Store first, then register. Roll back a failed registration so no phantom snooze is shown. */
    suspend fun snooze(fired: AlarmItem): Long? = committed {
        val current = repository.getById(fired.id) ?: return@committed null
        if (current.copy(
                enabled = fired.enabled,
                snoozedUntil = fired.snoozedUntil,
                consumedByFire = fired.consumedByFire
            ) != fired || (!current.enabled && !current.consumedByFire)
        ) {
            return@committed null
        }
        val until = System.currentTimeMillis() + fired.snoozeMinutes.coerceIn(1, 60) * 60_000L
        repository.setSnoozedUntil(fired.id, until)
        if (!scheduler.scheduleSnooze(current, until)) {
            repository.setSnoozedUntil(fired.id, current.snoozedUntil)
            // 新旧贪睡共享 PendingIntent 身份。失败后要还原旧触发及其原始令牌，
            // 不能只恢复磁盘记录，否则 OS 中可能仍有携带新令牌的旧闹钟。
            val restored = current.snoozedUntil?.let { oldUntil ->
                current.snoozeTriggerTime()?.let { trigger ->
                    scheduler.scheduleSnooze(current, trigger, oldUntil)
                } ?: scheduler.cancelSnooze(current)
            } ?: scheduler.cancelSnooze(current)
            reportSchedule(fired.id, restored)
            return@committed null
        }
        reportSchedule(fired.id, true)
        until
    }

    /** A stale/cancelled PendingIntent must not ring an edited, disabled or deleted alarm. */
    suspend fun consumeSnooze(id: Long, until: Long): AlarmItem? = committed {
        if (until <= 0L) return@committed null
        val current = repository.getById(id) ?: return@committed null
        val now = System.currentTimeMillis()
        if (current.snoozedUntil != until || current.activeSnoozeTime(now) == null ||
            until > now + SNOOZE_EARLY_TOLERANCE_MS
        ) return@committed null
        repository.setSnoozedUntil(id, null)
        reportSchedule(id, scheduler.cancelSnooze(current))
        current.copy(snoozedUntil = null)
    }

    /** 只撤销本次贪睡；不得误删该 ID 之后产生的新贪睡。 */
    suspend fun cancelSnooze(id: Long, until: Long) = committed {
        val current = repository.getById(id) ?: return@committed
        if (current.snoozedUntil != until) return@committed
        repository.setSnoozedUntil(id, null)
        reportSchedule(id, scheduler.cancelSnooze(current))
    }

    suspend fun cleanupRingtones(uris: List<String>) = committed {
        cleanupUnreferenced(uris)
    }

    private suspend fun cleanupUnreferenced(uris: List<String>) {
        if (uris.isEmpty()) return
        // File housekeeping must not turn an already committed save/delete into a UI failure.
        try {
            val referenced = repository.alarms.first().mapNotNull { it.ringtoneUri }.toSet()
            withContext(Dispatchers.IO) {
                uris.forEach { RingtoneImporter.deleteOwnedRingtone(context, it, referenced) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "铃声清理失败，将在下次清理时重试", error)
        }
    }

    private suspend inline fun <T> committed(crossinline block: suspend () -> T): T =
        withContext(NonCancellable) { mutex.withLock { block() } }

    private companion object {
        const val TAG = "AlarmOperations"
        const val SNOOZE_EARLY_TOLERANCE_MS = 60_000L
    }
}
