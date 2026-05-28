package com.example.fcmkeepalive

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_LOG_ENTRIES = 200
    private val lock = Any()

    fun d(context: Context, tag: String, event: String, message: String, meta: String? = null) {
        Log.d(tag, "$event | $message")
        append(context, "D", tag, event, message, meta)
    }

    fun i(context: Context, tag: String, event: String, message: String, meta: String? = null) {
        Log.i(tag, "$event | $message")
        append(context, "I", tag, event, message, meta)
    }

    fun w(context: Context, tag: String, event: String, message: String, meta: String? = null) {
        Log.w(tag, "$event | $message")
        append(context, "W", tag, event, message, meta)
    }

    fun e(
        context: Context,
        tag: String,
        event: String,
        message: String,
        throwable: Throwable? = null,
        meta: String? = null
    ) {
        if (throwable != null) {
            Log.e(tag, "$event | $message", throwable)
        } else {
            Log.e(tag, "$event | $message")
        }
        append(
            context = context,
            level = "E",
            tag = tag,
            event = event,
            message = if (throwable != null) "$message: ${throwable.message}" else message,
            meta = meta
        )
    }

    fun getLogs(context: Context): List<LogEntry> {
        synchronized(lock) {
            val prefs = AppPrefs(context.applicationContext)
            val raw = prefs.getLogEntriesJson()
            return try {
                parseEntries(raw).asReversed()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to parse persisted logs. Clearing corrupted data.", t)
                prefs.setLogEntriesJson("[]")
                emptyList()
            }
        }
    }

    fun clearLogs(context: Context) {
        synchronized(lock) {
            val prefs = AppPrefs(context.applicationContext)
            prefs.setLogEntriesJson("[]")
        }
    }

    private fun append(
        context: Context,
        level: String,
        tag: String,
        event: String,
        message: String,
        meta: String?
    ) {
        synchronized(lock) {
            val prefs = AppPrefs(context.applicationContext)
            val entries = try {
                parseEntries(prefs.getLogEntriesJson()).toMutableList()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to parse persisted logs during append. Resetting storage.", t)
                prefs.setLogEntriesJson("[]")
                mutableListOf()
            }
            entries.add(
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    event = event,
                    message = message,
                    meta = meta
                )
            )

            val capped = if (entries.size > MAX_LOG_ENTRIES) {
                entries.takeLast(MAX_LOG_ENTRIES)
            } else {
                entries
            }
            prefs.setLogEntriesJson(toJson(capped))
        }
    }

    private fun parseEntries(json: String): List<LogEntry> {
        val result = mutableListOf<LogEntry>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                LogEntry(
                    timestamp = obj.getLong("timestamp"),
                    level = obj.getString("level"),
                    tag = obj.getString("tag"),
                    event = obj.getString("event"),
                    message = obj.getString("message"),
                    meta = if (obj.has("meta")) obj.getString("meta").takeIf { it.isNotBlank() } else null
                )
            )
        }
        return result
    }

    private fun toJson(entries: List<LogEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("level", entry.level)
                    put("tag", entry.tag)
                    put("event", entry.event)
                    put("message", entry.message)
                    if (!entry.meta.isNullOrBlank()) {
                        put("meta", entry.meta)
                    }
                }
            )
        }
        return array.toString()
    }
}

