package com.example.fcmkeepalive

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit

class AppPrefs(context: Context) {
    private val storageContext: Context = context.createDeviceProtectedStorageContext()

    private val prefs: SharedPreferences =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getChosenImeId(): String = prefs.getString(KEY_CHOSEN_IME_ID, "") ?: ""

    fun setChosenImeId(imeId: String) {
        prefs.edit { putString(KEY_CHOSEN_IME_ID, imeId) }
    }

    fun getLastSwitchResult(): String = prefs.getString(KEY_LAST_SWITCH_RESULT, "Not executed") ?: "Not executed"

    fun setLastSwitchResult(result: String) {
        prefs.edit { putString(KEY_LAST_SWITCH_RESULT, result) }
    }

    fun getLastFailureReason(): String = prefs.getString(KEY_LAST_FAILURE_REASON, "None") ?: "None"

    fun setLastFailureReason(reason: String) {
        prefs.edit { putString(KEY_LAST_FAILURE_REASON, reason) }
    }

    fun setLastExecutionSnapshot(timestampMs: Long, event: String, result: String) {
        prefs.edit {
            putLong(KEY_LAST_EXEC_TIME_MS, timestampMs)
                .putString(KEY_LAST_EXEC_EVENT, event)
                .putString(KEY_LAST_EXEC_RESULT, result)
        }
    }

    fun getLastExecutionSummary(): String {
        val timestampMs = prefs.getLong(KEY_LAST_EXEC_TIME_MS, 0L)
        val event = prefs.getString(KEY_LAST_EXEC_EVENT, "")?.trim().orEmpty()
        val result = prefs.getString(KEY_LAST_EXEC_RESULT, "")?.trim().orEmpty()
        if (timestampMs <= 0L || event.isEmpty() || result.isEmpty()) {
            return "N/A"
        }
        val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(timestampMs))
        return "$formattedTime $event $result"
    }

    fun setFcmNotificationSummary(statusLine: String, statsLine: String) {
        prefs.edit {
            putString(KEY_FCM_NOTIFY_STATUS_LINE, statusLine)
            putString(KEY_FCM_NOTIFY_STATS_LINE, statsLine)
        }
    }

    fun setFcmNotificationCountryCode(countryCode: String?) {
        prefs.edit {
            putString(KEY_FCM_NOTIFY_COUNTRY_CODE, countryCode?.trim().orEmpty())
        }
    }

    fun getFcmNotificationStatusLine(): String {
        return prefs.getString(KEY_FCM_NOTIFY_STATUS_LINE, "Not connected") ?: "Not connected"
    }

    fun getFcmNotificationStatsLine(): String {
        return prefs.getString(KEY_FCM_NOTIFY_STATS_LINE, "Connects - Ping -") ?: "Connects - Ping -"
    }

    fun getFcmNotificationCountryCode(): String {
        return prefs.getString(KEY_FCM_NOTIFY_COUNTRY_CODE, "") ?: ""
    }

    fun getLogEntriesJson(): String = prefs.getString(KEY_LOG_ENTRIES_JSON, "[]") ?: "[]"

    fun setLogEntriesJson(json: String) {
        prefs.edit { putString(KEY_LOG_ENTRIES_JSON, json) }
    }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "ime_switcher_prefs"
        private const val KEY_CHOSEN_IME_ID = "chosen_ime_id"
        private const val KEY_LAST_SWITCH_RESULT = "last_switch_result"
        private const val KEY_LAST_FAILURE_REASON = "last_failure_reason"
        private const val KEY_LOG_ENTRIES_JSON = "log_entries_json"
        private const val KEY_LAST_EXEC_TIME_MS = "last_exec_time_ms"
        private const val KEY_LAST_EXEC_EVENT = "last_exec_event"
        private const val KEY_LAST_EXEC_RESULT = "last_exec_result"
        private const val KEY_FCM_NOTIFY_STATUS_LINE = "fcm_notify_status_line"
        private const val KEY_FCM_NOTIFY_STATS_LINE = "fcm_notify_stats_line"
        private const val KEY_FCM_NOTIFY_COUNTRY_CODE = "fcm_notify_country_code"
    }
}
