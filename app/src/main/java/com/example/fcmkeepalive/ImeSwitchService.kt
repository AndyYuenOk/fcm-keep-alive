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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.lang.Thread.sleep
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
                else -> {}
            }
        }
    }

    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            runtimeScreenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        when (intent?.action) {
            ACTION_START -> Unit
            null -> Unit
            else -> {}
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
                if (eventType == EventType.USER_PRESENT) {
                    val firstMeta = collectFirstFcmDiagnosticsMeta()
                        ?.let { appendCountryCodeToMeta(it) }
                    AppLogger.i(
                        this,
                        TAG,
                        logEvent,
                        logMessage,
                        firstMeta
                    )
                    if (firstMeta != null && hasConnectedLine(firstMeta)) {
                        updateNotificationSummaryFromMeta(firstMeta)
                    }
                    if (firstMeta != null && !hasConnectedLine(firstMeta)) {
                        val finalDiagnosisMeta = collectFinalDisconnectedDiagnosis(firstMeta)
                        AppLogger.i(
                            this,
                            TAG,
                            "fcm_diag",
                            "final diagnosis: ${diagnosisState(finalDiagnosisMeta)}",
                            finalDiagnosisMeta
                        )
                    }
                } else {
                    AppLogger.i(
                        this,
                        TAG,
                        logEvent,
                        logMessage,
                        null
                    )
                }
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
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
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

    private fun collectFirstFcmDiagnosticsMeta(): String? {
        val firstResult = runGcmDumpsys()
        return firstResult.output?.let { extractFcmDiagnosticsBlock(it) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun collectFinalDisconnectedDiagnosis(firstMeta: String): String {
        triggerMcsHeartbeatOnce()
        var lastMeta: String? = null
        var attempt = 0
        while (attempt < RECHECK_MAX_ATTEMPTS) {
            attempt += 1
            sleep(RECHECK_DELAY_MS)
            val retryResult = runGcmDumpsys()
            val retryMeta = retryResult.output?.let { extractFcmDiagnosticsBlock(it) }
                ?.takeIf { it.isNotBlank() }
            if (retryMeta != null) {
                lastMeta = retryMeta
                if (hasConnectedLine(retryMeta)) {
                    break
                }
            }
        }
        val finalMeta = appendCountryCodeToMeta(lastMeta ?: firstMeta)
        updateNotificationSummaryFromMeta(finalMeta)
        return finalMeta
    }

    private fun diagnosisState(meta: String?): String {
        if (meta.isNullOrBlank()) return "empty"
        return if (hasConnectedLine(meta)) "connected" else "not connected"
    }

    private fun hasConnectedLine(meta: String): Boolean {
        return meta.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.contains("connected=")
        }
    }

    private fun triggerMcsHeartbeatOnce(): Boolean {
        val command = "am broadcast -a com.google.android.intent.action.MCS_HEARTBEAT"
        val result = runShizukuCommand(command)
        return !result.output.isNullOrBlank() || result.reason.isNullOrBlank()
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
        val countryCode = resolveCountryCodeFromMeta(meta)
        val statusLine = buildStatusLine(meta)
        val statsLine = buildStatsLine(meta, countryCode)
        prefs.setFcmNotificationSummary(statusLine, statsLine)
        prefs.setFcmNotificationCountryCode(countryCode)
        refreshNotification()
    }

    private fun appendCountryCodeToMeta(meta: String): String {
        val countryCode = resolveCountryCodeFromMeta(meta) ?: return meta
        val existingLine = meta.lineSequence().any { it.trim().startsWith("countryCode=") }
        if (existingLine) return meta
        return "$meta\ncountryCode=${countryCode.uppercase(Locale.ROOT)}"
    }

    private fun resolveCountryCodeFromMeta(meta: String): String? {
        val ip = extractIpv4(meta) ?: return null
        val cached = prefs.getIpCountryCode(ip)
        if (!cached.isNullOrBlank()) return cached
        val fetched = fetchCountryCodeFromApi(ip) ?: return null
        prefs.setIpCountryCode(ip, fetched)
        return fetched
    }

    private fun extractIpv4(raw: String): String? {
        val ipPattern = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val candidate = ipPattern.find(raw)?.value ?: return null
        val parts = candidate.split(".")
        if (parts.size != 4) return null
        val valid = parts.all { part ->
            val n = part.toIntOrNull() ?: return@all false
            n in 0..255
        }
        return if (valid) candidate else null
    }

    private fun fetchCountryCodeFromApi(ip: String): String? {
        return runCatching {
            val url = URL("https://ip9.com.cn/get?ip=$ip")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
            }
            try {
                val responseText = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
                val json = JSONObject(responseText)
                if (json.optInt("ret", -1) != 200) return@runCatching null
                val data = json.optJSONObject("data") ?: return@runCatching null
                data.optString("country_code", "")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.uppercase(Locale.ROOT)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun buildStatusLine(meta: String): String {
        val lines = meta.lineSequence().map { it.trim() }.toList()
        val isConnected = lines.any {
            it.contains("connected=")
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

    private fun buildStatsLine(meta: String, countryCode: String?): String {
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
        val pingInt = ping.toIntOrNull()
        val normalizedCountryCode = countryCode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
        val pingSegment = when {
            pingInt != null && normalizedCountryCode != null ->
                "$normalizedCountryCode ${pingInt}ms"
            pingInt != null ->
                "- ${pingInt}ms"
            normalizedCountryCode != null ->
                "$normalizedCountryCode $ping"
            else ->
                "- $ping"
        }

        val initial = lines.firstOrNull { it.startsWith("Heartbeat:") }
            ?.substringAfter("initial:", "")
            ?.substringBefore(")")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "-"
        val initialMinutesText = formatInitialMinutes(initial)

        return "$pingSegment Initial $initialMinutesText Connects $connects"
    }

    private fun formatInitialMinutes(initialRaw: String): String {
        val seconds = initialRaw.removeSuffix("s").trim().toDoubleOrNull()
            ?: return initialRaw
        val minutes = seconds / 60.0
        val roundedOneDecimal = kotlin.math.round(minutes * 10.0) / 10.0
        val isWhole = kotlin.math.abs(roundedOneDecimal - roundedOneDecimal.toInt()) < 1e-9
        return if (isWhole) {
            "${roundedOneDecimal.toInt()}m"
        } else {
            String.format(Locale.US, "%.1fm", roundedOneDecimal)
        }
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
        private const val RECHECK_DELAY_MS = 30_000L
        private const val RECHECK_MAX_ATTEMPTS = 3

        const val ACTION_START = "com.example.fcmkeepalive.action.START"
    }

    private data class DumpsysResult(
        val output: String?,
        val reason: String?
    )

    enum class EventType {
        SCREEN_OFF,
        USER_PRESENT
    }
}
