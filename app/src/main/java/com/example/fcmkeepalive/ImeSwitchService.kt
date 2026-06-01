package com.example.fcmkeepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.lang.Thread.sleep
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

class ImeSwitchService : Service() {
    private val prefs by lazy { AppPrefs(this) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val isBroadcastTaskRunning = AtomicBoolean(false)

    private val runtimeScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_OFF) {
                handleScreenEvent(EventType.SCREEN_OFF)
            } else if (action == Intent.ACTION_USER_PRESENT) {
                handleScreenEvent(EventType.USER_PRESENT)
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
        return START_STICKY
    }

    private fun handleScreenEvent(eventType: EventType) {
        if (!isBroadcastTaskRunning.compareAndSet(false, true)) {
            logTaskDropped(eventType, "task already running")
            return
        }
        try {
            ioExecutor.execute {
                try {
                    runScreenEventTask(eventType)
                } finally {
                    isBroadcastTaskRunning.set(false)
                }
            }
        } catch (t: Throwable) {
            isBroadcastTaskRunning.set(false)
            AppLogger.e(
                this,
                TAG,
                "switch_task",
                "Failed to submit task",
                t,
                "event=${eventType.name}"
            )
        }
    }

    private fun runScreenEventTask(eventType: EventType) {
        if (!ImeHelper.hasWriteSecureSettings(this)) {
            handleSwitchFailure(
                eventType = eventType,
                reason = "WRITE_SECURE_SETTINGS is not granted, cannot auto switch"
            )
            return
        }

        val targetImeId = when (eventType) {
            EventType.SCREEN_OFF -> ImeHelper.resolveGboardImeId()
            EventType.USER_PRESENT -> prefs.getChosenImeId().trim()
        }

        if (targetImeId.isEmpty()) {
            handleSwitchFailure(
                eventType = eventType,
                reason = if (eventType == EventType.USER_PRESENT) {
                    "IME ID is not configured"
                } else {
                    "Gboard ID is empty"
                }
            )
            return
        }

        val success: Boolean
        var failureReason = "Failed to write DEFAULT_INPUT_METHOD"
        try {
            success = ImeHelper.setDefaultIme(this, targetImeId)
        } catch (t: Throwable) {
            handleSwitchFailure(
                eventType = eventType,
                reason = "Switch exception: ${t.message ?: t.javaClass.simpleName}",
                targetImeId = targetImeId
            )
            return
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
                updateNotificationSummary(FCM_PLACEHOLDER_META, countryCode = null, latencyMs = null)
                val firstMeta = collectFirstFcmDiagnosticsMeta()
                val firstMetaOrPlaceholder = firstMeta ?: FCM_PLACEHOLDER_META
                AppLogger.i(this, TAG, logEvent, logMessage, firstMetaOrPlaceholder)

                var metricsMetaSource = firstMetaOrPlaceholder
                if (firstMeta != null && !hasConnectedLine(firstMeta)) {
                    val finalMeta = collectFinalDisconnectedDiagnosis(firstMeta)
                    AppLogger.i(this, TAG, "", "final diagnosis", finalMeta)
                    metricsMetaSource = finalMeta
                }
                resolveMetricsAsyncAndRefresh(metricsMetaSource)
            } else {
                AppLogger.i(this, TAG, logEvent, logMessage, null)
            }
            recordLastExecution(eventType, "success")
        } else {
            handleSwitchFailure(
                eventType = eventType,
                reason = failureReason,
                targetImeId = targetImeId
            )
        }
    }

    private fun logTaskDropped(eventType: EventType, reason: String) {
        AppLogger.w(
            this,
            TAG,
            "switch_task",
            "${eventType.name} dropped: $reason",
            "event=${eventType.name}, reason=$reason"
        )
    }

    private fun handleSwitchFailure(eventType: EventType, reason: String, targetImeId: String? = null) {
        prefs.setLastFailureReason(reason)
        prefs.setLastSwitchResult("${eventType.name} switch failed")
        val meta = buildString {
            append("event=${eventType.name}")
            if (!targetImeId.isNullOrBlank()) append(", imeId=$targetImeId")
        }
        AppLogger.w(
            this,
            TAG,
            "switch_result",
            "${eventType.name} switch failed: $reason",
            meta
        )
        recordLastExecution(eventType, "failed")
    }

    private fun ensureForeground() {
        if (isForeground) return
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
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
        return lastMeta ?: firstMeta
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
            val reason = "Shizuku not running"
            AppLogger.w(this, TAG, "shizuku_cmd", reason, "command=gcm_dumpsys")
            return DumpsysResult(output = null, reason = reason)
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            val reason = "Shizuku permission denied"
            AppLogger.w(this, TAG, "shizuku_cmd", reason, "command=gcm_dumpsys")
            return DumpsysResult(output = null, reason = reason)
        }
        val command = "dumpsys activity service com.google.android.gms/.gcm.GcmService"
        val commandResult = runShizukuCommand(command)
        if (!commandResult.output.isNullOrBlank()) {
            return commandResult
        }
        AppLogger.w(
            this,
            TAG,
            "shizuku_cmd",
            "Command returned no output",
            "command=$command, reason=${commandResult.reason ?: "empty"}"
        )
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
                val reason = if (error.isNotBlank()) error.trim() else "Non-zero exit ($exitCode): $command"
                AppLogger.w(
                    this,
                    TAG,
                    "shizuku_cmd",
                    "Command failed",
                    "command=$command, exit=$exitCode, error=${reason.take(300)}"
                )
                return DumpsysResult(
                    output = null,
                    reason = reason
                )
            }
            val merged = output.ifBlank { error }.takeIf { it.isNotBlank() }
            if (merged.isNullOrBlank()) {
                AppLogger.w(
                    this,
                    TAG,
                    "shizuku_cmd",
                    "Command succeeded but no output",
                    "command=$command"
                )
            }
            DumpsysResult(output = merged, reason = null)
        }.getOrElse {
            AppLogger.e(
                this,
                TAG,
                "shizuku_cmd",
                "Command exception",
                it,
                "command=$command"
            )
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

    private fun updateNotificationSummary(meta: String, countryCode: String?, latencyMs: Int?) {
        val statusLine = buildStatusLine(meta)
        val statsLine = buildStatsLine(meta, countryCode, latencyMs)
        prefs.setFcmNotificationSummary(statusLine, statsLine)
        prefs.setFcmNotificationCountryCode(countryCode)
        refreshNotification()
    }

    private fun resolveMetricsAsyncAndRefresh(meta: String) {
        val connectedIp = parseConnectedIpFromMeta(meta)
        var cacheState = "skip"
        val countryCode = if (connectedIp == null) {
            AppLogger.w(this, TAG, "fcm_metric", "Country lookup failed", "reason=ip_not_found_in_meta")
            null
        } else if (!isValidPublicIpv4(connectedIp)) {
            AppLogger.w(
                this,
                TAG,
                "fcm_metric",
                "Country lookup failed",
                "reason=ip_not_public, ip=$connectedIp"
            )
            null
        } else {
            val cacheLookup = getCountryCodeFromCache(connectedIp)
            cacheState = cacheLookup.state
            val cachedCountry = cacheLookup.countryCode
            if (cachedCountry != null) {
                cachedCountry
            } else {
                fetchCountryCodeByIp(connectedIp)?.also { fetchedCountry ->
                    saveCountryCodeToCache(connectedIp, fetchedCountry)
                    cacheState = if (cacheState == "expired") {
                        "expired,store"
                    } else {
                        "miss,store"
                    }
                }
            }
        }
        val latencyMs = parseLastPingMs(meta)
        val metricsMeta = buildMetricsMeta(
            countryCode = countryCode ?: PLACEHOLDER_METRIC,
            ip = connectedIp,
            cache = cacheState
        )
        AppLogger.i(this, TAG, "fcm_metric", "country_latency", metricsMeta)
        updateNotificationSummary(meta, countryCode, latencyMs)
    }

    private fun parseLastPingMs(meta: String): Int? {
        val line = meta.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("Last ping:", ignoreCase = true) ||
                    it.startsWith("Last ping=", ignoreCase = true)
            }
            ?: return null
        val raw = line.substringAfter(":")
            .substringAfter("=")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return raw.toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun buildMetricsMeta(countryCode: String, ip: String?, cache: String): String {
        return buildString {
            appendLine("countryCode=$countryCode")
            appendLine("ip=${ip?.ifBlank { PLACEHOLDER_METRIC } ?: PLACEHOLDER_METRIC}")
            append("cache=$cache")
        }
    }

    private fun parseConnectedIpFromMeta(meta: String): String? {
        val connectedLine = meta.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("connected=", ignoreCase = true) }
            ?: return null
        val endpoint = connectedLine.substringAfter("connected=", "")
            .substringBefore(",")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        val ipCandidate = endpoint.substringAfterLast("/")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return ipCandidate.takeIf { isValidIpv4(it) }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val segments = ip.split(".")
        if (segments.size != 4) return false
        for (segment in segments) {
            val n = segment.toIntOrNull() ?: return false
            if (n !in 0..255) return false
        }
        return true
    }

    private fun isValidPublicIpv4(ip: String): Boolean {
        if (!isValidIpv4(ip)) return false
        val segments = ip.split(".")
        val nums = IntArray(4)
        for (i in 0..3) {
            nums[i] = segments[i].toInt()
        }
        if (nums[0] == 198 && nums[1] in 18..19) return false
        if (nums[0] == 10) return false
        if (nums[0] == 172 && nums[1] in 16..31) return false
        if (nums[0] == 192 && nums[1] == 168) return false
        if (nums[0] == 127) return false
        if (nums[0] == 0) return false
        if (nums[0] >= 224) return false
        return true
    }

    private fun fetchCountryCodeByIp(ip: String): String? {
        return runCatching {
            val url = URL("https://ipinfo.io/$ip/json")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = API_TIMEOUT_MS
            connection.readTimeout = API_TIMEOUT_MS
            try {
                val responseText = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
                JSONObject(responseText)
                    .getString("country")
                    .trim()
                    .uppercase(Locale.ROOT)
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            AppLogger.w(
                this,
                TAG,
                "fcm_metric",
                "Country lookup by ip failed",
                "ip=$ip, error=${it.message ?: it.javaClass.simpleName}"
            )
        }.getOrNull()
    }

    private fun getCountryCodeFromCache(ip: String): CountryCacheLookup {
        val now = System.currentTimeMillis()
        val cacheItems = loadCountryCacheItems()
        var foundCountry: String? = null
        var expired = false
        val refreshed = ArrayList<CountryCodeCacheItem>(cacheItems.size)
        for (item in cacheItems) {
            if (now - item.updatedAtMs > COUNTRY_CACHE_TTL_MS) {
                if (item.ip == ip) {
                    expired = true
                }
                continue
            }
            if (item.ip == ip) {
                foundCountry = item.countryCode
            } else {
                refreshed.add(item)
            }
        }
        if (foundCountry != null) {
            refreshed.add(CountryCodeCacheItem(ip = ip, countryCode = foundCountry, updatedAtMs = now))
        }
        val pruned = pruneCountryCache(refreshed)
        if (pruned != cacheItems) {
            persistCountryCacheItems(pruned)
        }
        val state = when {
            foundCountry != null -> "hit"
            expired -> "expired"
            else -> "miss"
        }
        return CountryCacheLookup(countryCode = foundCountry, state = state)
    }

    private fun saveCountryCodeToCache(ip: String, countryCode: String) {
        if (countryCode.isBlank()) return
        val now = System.currentTimeMillis()
        val cacheItems = loadCountryCacheItems()
        val refreshed = ArrayList<CountryCodeCacheItem>(cacheItems.size + 1)
        for (item in cacheItems) {
            if (item.ip == ip) continue
            if (now - item.updatedAtMs > COUNTRY_CACHE_TTL_MS) continue
            refreshed.add(item)
        }
        refreshed.add(
            CountryCodeCacheItem(
                ip = ip,
                countryCode = countryCode.trim().uppercase(Locale.ROOT),
                updatedAtMs = now
            )
        )
        persistCountryCacheItems(pruneCountryCache(refreshed))
    }

    private fun pruneCountryCache(items: List<CountryCodeCacheItem>): List<CountryCodeCacheItem> {
        val now = System.currentTimeMillis()
        return items.filter { now - it.updatedAtMs <= COUNTRY_CACHE_TTL_MS }
            .sortedBy { it.updatedAtMs }
    }

    private fun loadCountryCacheItems(): List<CountryCodeCacheItem> {
        val raw = prefs.getCountryCodeCacheJson()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ip = obj.optString("ip").trim()
                    val countryCode = obj.optString("countryCode").trim().uppercase(Locale.ROOT)
                    val updatedAtMs = obj.optLong("updatedAtMs", 0L)
                    if (ip.isBlank() || countryCode.isBlank() || updatedAtMs <= 0L) continue
                    add(
                        CountryCodeCacheItem(
                            ip = ip,
                            countryCode = countryCode,
                            updatedAtMs = updatedAtMs
                        )
                    )
                }
            }
        }.getOrElse {
            AppLogger.w(this, TAG, "fcm_metric", "country_cache_parse_failed", "error=${it.message}")
            emptyList()
        }
    }

    private fun persistCountryCacheItems(items: List<CountryCodeCacheItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("ip", item.ip)
                    put("countryCode", item.countryCode)
                    put("updatedAtMs", item.updatedAtMs)
                }
            )
        }
        prefs.setCountryCodeCacheJson(arr.toString())
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

    private fun buildStatsLine(meta: String, countryCode: String?, latencyMs: Int?): String {
        val lines = meta.lineSequence().map { it.trim() }.toList()
        val connects = lines.firstOrNull { it.startsWith("streamId=") }
            ?.split(",")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("connects=") }
            ?.substringAfter("connects=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "-"

        val normalizedCountryCode = countryCode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
        val latencyText = latencyMs?.let { "${it}ms" } ?: "-"
        val countryText = normalizedCountryCode ?: "-"
        val pingSegment = "$countryText $latencyText"

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
        private const val API_TIMEOUT_MS = 5_000
        private const val COUNTRY_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val PLACEHOLDER_METRIC = "-"
        private const val FCM_PLACEHOLDER_META = "diagnosis=pending"

        const val ACTION_START = "com.example.fcmkeepalive.action.START"
    }

    private data class DumpsysResult(
        val output: String?,
        val reason: String?
    )

    private data class CountryCodeCacheItem(
        val ip: String,
        val countryCode: String,
        val updatedAtMs: Long
    )

    private data class CountryCacheLookup(
        val countryCode: String?,
        val state: String
    )

    enum class EventType {
        SCREEN_OFF,
        USER_PRESENT
    }
}
