package com.example.fcmkeepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.lang.Thread.sleep
import java.util.concurrent.Executors

class ImeSwitchService : Service() {
    private val prefs by lazy { AppPrefs(this) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val runtimeScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenEvent(EventType.SCREEN_OFF)
                Intent.ACTION_USER_PRESENT -> handleScreenEvent(EventType.USER_PRESENT)
                else -> Unit
            }
        }
    }

    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(
            runtimeScreenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        when (intent?.action) {
            ACTION_START -> Unit
            null -> Unit
            else -> Unit
        }

        return START_STICKY
    }

    private fun handleScreenEvent(eventType: EventType) {
        if (!ImeHelper.hasWriteSecureSettings(this)) {
            val reason = "WRITE_SECURE_SETTINGS is not granted, cannot auto switch"
            prefs.setLastFailureReason(reason)
            prefs.setLastSwitchResult("${eventType.name} switch failed")
            AppLogger.w(
                this,
                TAG,
                "switch_result",
                "${eventType.name} switch failed: $reason",
                "event=${eventType.name}"
            )
            recordLastExecution(eventType, "failed")
            return
        }

        val targetImeId = when (eventType) {
            EventType.SCREEN_OFF -> ImeHelper.resolveGboardImeId()
            EventType.USER_PRESENT -> prefs.getChosenImeId().trim()
        }

        if (targetImeId.isEmpty()) {
            val reason = if (eventType == EventType.USER_PRESENT) {
                "IME ID is not configured"
            } else {
                "Gboard ID is empty"
            }
            prefs.setLastFailureReason(reason)
            prefs.setLastSwitchResult("${eventType.name} switch failed")
            AppLogger.w(
                this,
                TAG,
                "switch_result",
                "${eventType.name} switch failed: $reason",
                "event=${eventType.name}"
            )
            recordLastExecution(eventType, "failed")
            return
        }

        switchOnce(eventType, targetImeId)
    }

    private fun switchOnce(eventType: EventType, targetImeId: String) {
        ioExecutor.execute {
            val success: Boolean
            var failureReason = "Failed to write DEFAULT_INPUT_METHOD"
            try {
                success = ImeHelper.setDefaultIme(this, targetImeId)
            } catch (t: Throwable) {
                failureReason = "Switch exception: ${t.message ?: t.javaClass.simpleName}"
                prefs.setLastFailureReason(failureReason)
                prefs.setLastSwitchResult("${eventType.name} switch failed")
                AppLogger.w(
                    this,
                    TAG,
                    "switch_result",
                    "${eventType.name} switch failed: $failureReason",
                    "event=${eventType.name}, imeId=$targetImeId"
                )
                recordLastExecution(eventType, "failed")
                return@execute
            }

            if (success) {
                prefs.setLastFailureReason("None")
                prefs.setLastSwitchResult("${eventType.name} switch success -> $targetImeId")
                val imeName = ImeHelper.resolveImeDisplayName(this, targetImeId)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: targetImeId
                val logEvent = eventType.name
                val logMessage = "$imeName success"
                val logMeta: String? = if (eventType == EventType.USER_PRESENT) {
                    collectFcmDiagnosticsMeta()
                } else {
                    null
                }
                AppLogger.i(
                    this,
                    TAG,
                    logEvent,
                    logMessage,
                    logMeta
                )
                recordLastExecution(eventType, "success")
            } else {
                prefs.setLastFailureReason(failureReason)
                prefs.setLastSwitchResult("${eventType.name} switch failed")
                AppLogger.w(
                    this,
                    TAG,
                    "switch_result",
                    "${eventType.name} switch failed: $failureReason",
                    "event=${eventType.name}, imeId=$targetImeId"
                )
                recordLastExecution(eventType, "failed")
            }
        }
    }

    private fun ensureForeground() {
        if (isForeground) return
        startForeground(NOTIFICATION_ID, buildNotification())
        isForeground = true
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("FCM ${prefs.getFcmNotificationStatusLine()}")
            .setContentText(prefs.getFcmNotificationStatsLine())
            .setContentIntent(buildNotificationContentIntent())
            .setOngoing(true)
            .build()
    }

    private fun buildNotificationContentIntent(): PendingIntent {
        val diagnosticsIntent = Intent().apply {
            component = ComponentName(
                "com.google.android.gms",
                "com.google.android.gms.gcm.GcmDiagnostics"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchIntent = if (diagnosticsIntent.resolveActivity(packageManager) != null) {
            diagnosticsIntent
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun recordLastExecution(eventType: EventType, result: String) {
        if (eventType != EventType.SCREEN_OFF) return
        prefs.setLastExecutionSnapshot(System.currentTimeMillis(), eventType.name, result)
        refreshNotification()
    }

    private fun collectFcmDiagnosticsMeta(): String? {
        val firstResult = runGcmDumpsys()
        val firstMeta = firstResult.output?.let { extractFcmDiagnosticsBlock(it) }
            ?.takeIf { it.isNotBlank() }
        if (firstMeta == null) return null
        if (hasConnectedLine(firstMeta)) {
            updateNotificationSummaryFromMeta(firstMeta)
            return firstMeta
        }

        AppLogger.i(
            this,
            TAG,
            "fcm_diag",
            "heartbeat trigger"
        )
        val heartbeatResult = triggerMcsHeartbeatOnce()
        AppLogger.i(
            this,
            TAG,
            "fcm_diag",
            "heartbeat result: ${if (heartbeatResult.success) "success" else "failed"}",
            buildHeartbeatResultMeta(heartbeatResult)
        )
        sleep(400)

        val secondResult = runGcmDumpsys()
        val secondMeta = secondResult.output?.let { extractFcmDiagnosticsBlock(it) }
            ?.takeIf { it.isNotBlank() }
        val finalMeta = secondMeta ?: firstMeta
        updateNotificationSummaryFromMeta(finalMeta)
        return finalMeta
    }

    private fun hasConnectedLine(meta: String): Boolean {
        return meta.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.contains("connected=") || trimmed.contains("connecting=")
        }
    }

    private fun triggerMcsHeartbeatOnce(): HeartbeatTriggerResult {
        val command = "am broadcast -a com.google.android.intent.action.MCS_HEARTBEAT"
        val result = runShizukuCommand(command)
        return HeartbeatTriggerResult(
            success = !result.output.isNullOrBlank() || result.reason.isNullOrBlank(),
            output = result.output,
            reason = result.reason
        )
    }

    private fun buildHeartbeatResultMeta(result: HeartbeatTriggerResult): String? {
        val output = result.output?.trim().takeIf { !it.isNullOrBlank() }?.take(200)
        val reason = result.reason?.trim().takeIf { !it.isNullOrBlank() }?.take(200)
        val parts = listOfNotNull(
            output?.let { "output=$it" },
            reason?.let { "reason=$it" }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun runGcmDumpsys(): DumpsysResult {
        if (!Shizuku.pingBinder()) {
            return DumpsysResult(output = null, reason = "Shizuku not running")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return DumpsysResult(output = null, reason = "Shizuku permission denied")
        }
        val command = "dumpsys activity service com.google.android.gms/.gcm.GcmService"
        val commandResult = runShizukuCommand(command)
        if (!commandResult.output.isNullOrBlank()) {
            return commandResult
        }
        return DumpsysResult(
            output = null,
            reason = commandResult.reason ?: "No output from dumpsys command"
        )
    }

    private fun runShizukuCommand(command: String): DumpsysResult {
        return runCatching {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process
            val exitCode = process.waitFor()
            val output = process.inputStream?.bufferedReader()?.readText().orEmpty()
            val error = process.errorStream?.bufferedReader()?.readText().orEmpty()
            if (exitCode != 0) {
                return DumpsysResult(
                    output = null,
                    reason = if (error.isNotBlank()) error.trim() else "Non-zero exit ($exitCode): $command"
                )
            }
            val merged = output.ifBlank { error }.takeIf { it.isNotBlank() }
            DumpsysResult(output = merged, reason = null)
        }.getOrElse {
            DumpsysResult(output = null, reason = "Command exception: ${it.message}")
        }
    }

    private fun extractFcmDiagnosticsBlock(raw: String): String? {
        val lines = raw.lineSequence().toList()
        val deviceIdLineIndex = lines.indexOfFirst { it.contains("DeviceID:") }
        if (deviceIdLineIndex < 0 || deviceIdLineIndex + 1 >= lines.size) return null

        val collected = mutableListOf<String>()
        for (index in (deviceIdLineIndex + 1) until lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty()) break
            collected.add(trimmed)
        }

        if (collected.isEmpty()) return null
        return collected.joinToString(separator = "\n")
    }

    private fun updateNotificationSummaryFromMeta(meta: String) {
        val statusLine = buildStatusLine(meta)
        val statsLine = buildStatsLine(meta)
        prefs.setFcmNotificationSummary(statusLine, statsLine)
        refreshNotification()
    }

    private fun buildStatusLine(meta: String): String {
        val lines = meta.lineSequence().map { it.trim() }.toList()
        val isConnected = lines.any {
            it.contains("connected=") || it.contains("connecting=")
        }

        val connectTimeRaw = lines.firstOrNull { it.startsWith("connectTime=") }
            ?.substringAfter("connectTime=")
            ?.trim()
            .orEmpty()
        val disconnectTimeRaw = lines.firstOrNull { it.startsWith("disconnectTime=") }
            ?.substringAfter("disconnectTime=")
            ?.trim()
            .orEmpty()

        if (isConnected) {
            val connectedTime = connectTimeRaw.split("/")
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "-"
            return "Connected $connectedTime"
        }

        val disconnectTime = disconnectTimeRaw.split("/")
            .firstOrNull()
            ?.takeIf { it.isNotBlank() }
        return if (disconnectTime != null) {
            "Disconnect $disconnectTime"
        } else {
            "Not connected"
        }
    }

    private fun buildStatsLine(meta: String): String {
        val lines = meta.lineSequence().map { it.trim() }.toList()
        val connects = lines.firstOrNull { it.startsWith("streamId=") }
            ?.split(",")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("connects=") }
            ?.substringAfter("connects=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "-"

        val ping = lines.firstOrNull { it.startsWith("Last ping:") }
            ?.substringAfter("Last ping:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "-"

        val initial = lines.firstOrNull { it.startsWith("Heartbeat:") }
            ?.substringAfter("initial:", "")
            ?.substringBefore(")")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "-"

        return "Connects $connects Ping $ping Initial $initial"
    }

    private fun refreshNotification() {
        if (!isForeground) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "IME listener service",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(runtimeScreenReceiver) }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ImeSwitchService"
        private const val CHANNEL_ID = "ime_switch_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.fcmkeepalive.action.START"
    }

    private data class DumpsysResult(
        val output: String?,
        val reason: String?
    )

    private data class HeartbeatTriggerResult(
        val success: Boolean,
        val output: String?,
        val reason: String?
    )

    enum class EventType {
        SCREEN_OFF,
        USER_PRESENT
    }
}
