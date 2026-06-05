package com.example.fcmkeepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors

class ImeSwitchService : Service() {
    private val prefs by lazy { AppPrefs(this) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticsExecutor = Executors.newSingleThreadExecutor()
    private val isBroadcastTaskRunning = AtomicBoolean(false)
    private val pendingScreenEvent = AtomicReference<EventType?>(null)
    private val isUserPresentDiagnosticsRunning = AtomicBoolean(false)
    private val lastDerivedMappedEvent = AtomicReference<EventType?>(null)
    private val lastDerivedMappedAtMs = AtomicLong(0L)
    private val castStateObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            handleCastStateSettingChanged(uri)
        }
    }

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

    private val runtimeMirrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MIRROR_DEVICE_CHANGED) return
            handleMirrorDeviceChanged()
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
        ContextCompat.registerReceiver(
            this,
            runtimeMirrorReceiver,
            IntentFilter(ACTION_MIRROR_DEVICE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        registerCastStateObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        return START_STICKY
    }

    private fun handleScreenEvent(eventType: EventType) {
        pendingScreenEvent.set(eventType)
        if (!isBroadcastTaskRunning.compareAndSet(false, true)) return
        try {
            ioExecutor.execute {
                processPendingScreenEvents()
            }
        } catch (t: Throwable) {
            pendingScreenEvent.compareAndSet(eventType, null)
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

    private fun processPendingScreenEvents() {
        while (true) {
            val eventType = pendingScreenEvent.getAndSet(null)
            if (eventType != null) {
                runScreenEventTask(eventType)
                continue
            }
            isBroadcastTaskRunning.set(false)
            if (pendingScreenEvent.get() == null || !isBroadcastTaskRunning.compareAndSet(false, true)) {
                return
            }
        }
    }

    private fun runScreenEventTask(eventType: EventType) {
        when (prefs.getKeepAliveMode()) {
            KeepAliveMode.IME -> runImeScreenEventTask(eventType)
            KeepAliveMode.BATTERY_AC -> runBatteryAcScreenEventTask(eventType)
        }
    }

    private fun handleMirrorDeviceChanged() {
        handleDerivedDeviceStateSignal(
            eventName = MIRROR_EVENT_NAME,
            source = "mirror",
            action = ACTION_MIRROR_DEVICE_CHANGED
        )
    }

    private fun registerCastStateObservers() {
        OBSERVED_CAST_SETTINGS.forEach { setting ->
            contentResolver.registerContentObserver(setting.uri, false, castStateObserver)
        }
        AppLogger.i(
            this,
            TAG,
            CAST_STATE_OBSERVER_EVENT_NAME,
            "observer registered",
            OBSERVED_CAST_SETTINGS.joinToString(separator = "\n") { setting ->
                "setting=${setting.namespace.name.lowercase(Locale.ROOT)}:${setting.key}"
            }
        )
    }

    private fun handleCastStateSettingChanged(uri: Uri?) {
        val setting = OBSERVED_CAST_SETTINGS.firstOrNull { it.uri == uri }
        val rawState = if (setting != null) {
            buildString {
                appendLine("setting=${setting.namespace.name.lowercase(Locale.ROOT)}:${setting.key}")
                append("value=${readObservedSettingValue(setting) ?: "-"}")
            }
        } else {
            "settingUri=${uri ?: "-"}"
        }
        handleDerivedDeviceStateSignal(
            eventName = CAST_STATE_CHANGED_EVENT_NAME,
            source = "cast_settings",
            action = uri?.toString(),
            rawState = rawState
        )
    }

    private fun readObservedSettingValue(setting: ObservedSetting): String? {
        return when (setting.namespace) {
            SettingNamespace.GLOBAL -> Settings.Global.getString(contentResolver, setting.key)
            SettingNamespace.SECURE -> Settings.Secure.getString(contentResolver, setting.key)
            SettingNamespace.SYSTEM -> Settings.System.getString(contentResolver, setting.key)
        }
    }

    private fun handleDerivedDeviceStateSignal(
        eventName: String,
        source: String,
        action: String? = null,
        rawState: String? = null
    ) {
        val snapshot = readDeviceStateSnapshot()
        val mappedEvent = if (
            snapshot.isKeyguardLocked ||
            snapshot.isDeviceLocked ||
            !snapshot.isInteractive
        ) {
            EventType.SCREEN_OFF
        } else {
            EventType.USER_PRESENT
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val previousEvent = lastDerivedMappedEvent.get()
        val previousAtMs = lastDerivedMappedAtMs.get()
        if (
            previousEvent == mappedEvent &&
            nowElapsedMs - previousAtMs <= DERIVED_EVENT_DUPLICATE_SUPPRESSION_WINDOW_MS
        ) {
            AppLogger.d(
                this,
                TAG,
                eventName,
                "duplicate ignored for $mappedEvent",
                buildDeviceStateMeta(
                    snapshot = snapshot,
                    mappedEvent = mappedEvent,
                    duplicateIgnored = true,
                    source = source,
                    action = action,
                    rawState = rawState
                )
            )
            return
        }

        lastDerivedMappedEvent.set(mappedEvent)
        lastDerivedMappedAtMs.set(nowElapsedMs)
        AppLogger.i(
            this,
            TAG,
            eventName,
            "mapped to $mappedEvent",
            buildDeviceStateMeta(
                snapshot = snapshot,
                mappedEvent = mappedEvent,
                duplicateIgnored = false,
                source = source,
                action = action,
                rawState = rawState
            )
        )
        handleScreenEvent(mappedEvent)
    }

    private fun readDeviceStateSnapshot(): DeviceStateSnapshot {
        return DeviceStateSnapshot(
            isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false,
            isDeviceLocked = keyguardManager?.isDeviceLocked ?: false,
            isInteractive = powerManager?.isInteractive ?: true
        )
    }

    private fun buildDeviceStateMeta(
        snapshot: DeviceStateSnapshot,
        mappedEvent: EventType,
        duplicateIgnored: Boolean,
        source: String,
        action: String? = null,
        rawState: String? = null
    ): String {
        return buildString {
            appendLine("source=$source")
            appendLine("action=${action?.ifBlank { "-" } ?: "-"}")
            appendLine("rawState=${rawState?.ifBlank { "-" } ?: "-"}")
            appendLine("isKeyguardLocked=${snapshot.isKeyguardLocked}")
            appendLine("isDeviceLocked=${snapshot.isDeviceLocked}")
            appendLine("isInteractive=${snapshot.isInteractive}")
            appendLine("mappedEvent=${mappedEvent.name}")
            append("duplicateIgnored=$duplicateIgnored")
        }
    }

    private fun runImeScreenEventTask(eventType: EventType) {
        if (!ImeHelper.hasWriteSecureSettings(this)) {
            handleImeFailure(
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
            handleImeFailure(
                eventType = eventType,
                reason = if (eventType == EventType.USER_PRESENT) {
                    "IME ID is not configured"
                } else {
                    "Gboard ID is empty"
                }
            )
            return
        }

        val success = try {
            ImeHelper.setDefaultIme(this, targetImeId)
        } catch (t: Throwable) {
            handleImeFailure(
                eventType = eventType,
                reason = "Switch exception: ${t.message ?: t.javaClass.simpleName}",
                targetImeId = targetImeId
            )
            return
        }

        if (!success) {
            handleImeFailure(
                eventType = eventType,
                reason = "Failed to write DEFAULT_INPUT_METHOD",
                targetImeId = targetImeId
            )
            return
        }

        prefs.setLastFailureReason("None")
        prefs.setLastSwitchResult("${eventType.name} IME success -> $targetImeId")
        val imeName = ImeHelper.resolveImeDisplayName(this, targetImeId)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: targetImeId
        val logEvent = eventType.name
        if (eventType == EventType.USER_PRESENT) {
            val logMessage = "$imeName, switch success"
            AppLogger.i(this, TAG, logEvent, logMessage, buildImeMeta(eventType, targetImeId))
            enqueueUserPresentFcmDiagnostics()
        } else {
            val logMessage = if (targetImeId == ImeHelper.resolveGboardImeId()) {
                val serviceStarted = startGboardServiceAndCheckResult()
                "$imeName, switch success, service ${if (serviceStarted) "success" else "failed"}"
            } else {
                "$imeName, switch success"
            }
            AppLogger.i(this, TAG, logEvent, logMessage, buildImeMeta(eventType, targetImeId))
        }
        recordLastExecution(eventType, "success")
    }

    private fun runBatteryAcScreenEventTask(eventType: EventType) {
        if (eventType == EventType.SCREEN_OFF && !prefs.isBatteryAcScreenOffArmed()) {
            return
        }
        if (eventType == EventType.SCREEN_OFF) {
            prefs.setBatteryAcScreenOffArmed(false)
        } else if (eventType == EventType.USER_PRESENT) {
            prefs.setBatteryAcScreenOffArmed(true)
        }

        if (!Shizuku.pingBinder()) {
            handleBatteryAcFailure(
                eventType = eventType,
                reason = "Shizuku is not running"
            )
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            handleBatteryAcFailure(
                eventType = eventType,
                reason = "Shizuku permission denied"
            )
            return
        }

        val acValue = if (eventType == EventType.SCREEN_OFF) 1 else 0
        val batteryReset = eventType == EventType.USER_PRESENT
        val disablePowerSoundsCommand = "settings put global power_sounds_enabled 0"
        val restorePowerSoundsCommand = "settings put global power_sounds_enabled 1"
        val batteryCommand = if (batteryReset) {
            "dumpsys battery reset"
        } else {
            "dumpsys battery set ac $acValue"
        }
        val powerKeyCommand = if (eventType == EventType.SCREEN_OFF) "input keyevent 26" else null
        val disablePowerSoundsReason = runShizukuCommandSilently(disablePowerSoundsCommand).reason
        var restorePowerSoundsReason: String? = null
        var failureReason: String? = null
        var failedStep: String? = null

        try {
            val batteryReason = runShizukuCommandSilently(batteryCommand).reason
            if (batteryReason != null) {
                failureReason = batteryReason
                failedStep = "battery_reset"
                return
            }

            if (powerKeyCommand != null) {
                val powerKeyReason = runShizukuCommandSilently(powerKeyCommand).reason
                if (powerKeyReason != null) {
                    failureReason = powerKeyReason
                    failedStep = "power_key"
                    return
                }
            }
        } finally {
            if (eventType == EventType.USER_PRESENT) {
                sleep(500L)
            }
            restorePowerSoundsReason = runShizukuCommandSilently(restorePowerSoundsCommand).reason
        }

        if (failureReason == null && restorePowerSoundsReason != null) {
            failureReason = restorePowerSoundsReason
            failedStep = "power_sounds_restore"
        }

        if (failureReason != null) {
            handleBatteryAcFailure(
                eventType = eventType,
                reason = failureReason,
                batteryCommand = batteryCommand,
                powerKeyCommand = powerKeyCommand,
                disablePowerSoundsCommand = disablePowerSoundsCommand,
                restorePowerSoundsCommand = restorePowerSoundsCommand,
                disablePowerSoundsReason = disablePowerSoundsReason,
                restorePowerSoundsReason = restorePowerSoundsReason,
                failedStep = failedStep
            )
            return
        }

        prefs.setLastFailureReason("None")
        prefs.setLastSwitchResult(
            if (batteryReset) "${eventType.name} battery reset success" else "${eventType.name} ac success -> $acValue"
        )
        val batteryMeta = buildBatteryAcMeta(
            eventType = eventType,
            batteryCommand = batteryCommand,
            powerKeyCommand = powerKeyCommand,
            disablePowerSoundsCommand = disablePowerSoundsCommand,
            restorePowerSoundsCommand = restorePowerSoundsCommand,
            disablePowerSoundsReason = disablePowerSoundsReason,
            restorePowerSoundsReason = restorePowerSoundsReason
        )
        if (eventType == EventType.USER_PRESENT) {
            AppLogger.i(
                this,
                TAG,
                "${eventType.name}_AC",
                if (batteryReset) "battery reset success" else "ac=$acValue set success",
                batteryMeta
            )
            enqueueUserPresentFcmDiagnostics()
        } else {
            AppLogger.i(
                this,
                TAG,
                "${eventType.name}_AC",
                "ac=$acValue set success, power key success",
                batteryMeta
            )
        }
        recordLastExecution(eventType, "success")
    }

    private fun handleImeFailure(eventType: EventType, reason: String, targetImeId: String? = null) {
        prefs.setLastFailureReason(reason)
        prefs.setLastSwitchResult("${eventType.name} IME failed")
        AppLogger.w(
            this,
            TAG,
            eventType.name,
            "IME failed: $reason",
            buildImeMeta(eventType, targetImeId)
        )
        recordLastExecution(eventType, "failed")
    }

    private fun handleBatteryAcFailure(
        eventType: EventType,
        reason: String,
        batteryCommand: String? = null,
        powerKeyCommand: String? = null,
        disablePowerSoundsCommand: String? = null,
        restorePowerSoundsCommand: String? = null,
        disablePowerSoundsReason: String? = null,
        restorePowerSoundsReason: String? = null,
        failedStep: String? = null
    ) {
        prefs.setLastFailureReason(reason)
        prefs.setLastSwitchResult(
            if (eventType == EventType.USER_PRESENT) "${eventType.name} battery reset failed" else "${eventType.name} ac failed"
        )
        AppLogger.w(
            this,
            TAG,
            "${eventType.name}_AC",
            if (eventType == EventType.USER_PRESENT) "battery reset failed: $reason" else "ac failed: $reason",
            buildBatteryAcMeta(
                eventType = eventType,
                batteryCommand = batteryCommand,
                powerKeyCommand = powerKeyCommand,
                disablePowerSoundsCommand = disablePowerSoundsCommand,
                restorePowerSoundsCommand = restorePowerSoundsCommand,
                disablePowerSoundsReason = disablePowerSoundsReason,
                restorePowerSoundsReason = restorePowerSoundsReason,
                failedStep = failedStep
            )
        )
        recordLastExecution(eventType, "failed")
    }

    private fun buildImeMeta(eventType: EventType, targetImeId: String?): String {
        return buildString {
            appendLine("mode=${KeepAliveMode.IME.storageValue}")
            appendLine("event=${eventType.name}")
            append("imeId=${targetImeId?.ifBlank { "-" } ?: "-"}")
        }
    }

    private fun buildBatteryAcMeta(
        eventType: EventType,
        batteryCommand: String?,
        powerKeyCommand: String? = null,
        disablePowerSoundsCommand: String? = null,
        restorePowerSoundsCommand: String? = null,
        disablePowerSoundsReason: String? = null,
        restorePowerSoundsReason: String? = null,
        failedStep: String? = null
    ): String {
        return buildString {
            appendLine("mode=${KeepAliveMode.BATTERY_AC.storageValue}")
            appendLine("event=${eventType.name}")
            appendLine("disablePowerSoundsCommand=${disablePowerSoundsCommand?.ifBlank { "-" } ?: "-"}")
            appendLine("restorePowerSoundsCommand=${restorePowerSoundsCommand?.ifBlank { "-" } ?: "-"}")
            appendLine("batteryCommand=${batteryCommand?.ifBlank { "-" } ?: "-"}")
            appendLine("powerKeyCommand=${powerKeyCommand?.ifBlank { "-" } ?: "-"}")
            appendLine("disablePowerSoundsReason=${disablePowerSoundsReason?.ifBlank { "-" } ?: "-"}")
            appendLine("restorePowerSoundsReason=${restorePowerSoundsReason?.ifBlank { "-" } ?: "-"}")
            append("failedStep=${failedStep?.ifBlank { "-" } ?: "-"}")
        }
    }

    private fun mergeLogMeta(primary: String?, secondary: String?): String? {
        val parts = listOfNotNull(
            primary?.takeIf { it.isNotBlank() },
            secondary?.takeIf { it.isNotBlank() }
        )
        return parts.joinToString(separator = "\n").ifBlank { null }
    }

    private fun collectInitialUserPresentFcmMeta(): String {
        updateNotificationSummary(FCM_PLACEHOLDER_META, countryCode = null, latencyMs = null)
        val firstMeta = collectFirstFcmDiagnosticsMeta()
        return firstMeta ?: FCM_PLACEHOLDER_META
    }

    private fun enqueueUserPresentFcmDiagnostics() {
        if (!isUserPresentDiagnosticsRunning.compareAndSet(false, true)) {
            AppLogger.w(
                this,
                TAG,
                "fcm_diag",
                "USER_PRESENT diagnostics dropped: task already running",
                "event=USER_PRESENT, reason=task already running"
            )
            return
        }
        try {
            diagnosticsExecutor.execute {
                try {
                    val firstFcmMeta = collectInitialUserPresentFcmMeta()
                    AppLogger.i(this, TAG, "fcm_diag", "initial diagnosis", firstFcmMeta)
                    continueUserPresentFcmFlow(firstFcmMeta)
                } finally {
                    isUserPresentDiagnosticsRunning.set(false)
                }
            }
        } catch (t: Throwable) {
            isUserPresentDiagnosticsRunning.set(false)
            AppLogger.e(
                this,
                TAG,
                "fcm_diag",
                "Failed to submit diagnostics task",
                t,
                "event=USER_PRESENT"
            )
        }
    }

    private fun continueUserPresentFcmFlow(firstMetaOrPlaceholder: String) {
        var metricsMetaSource = firstMetaOrPlaceholder
        if (firstMetaOrPlaceholder != FCM_PLACEHOLDER_META && !hasConnectedLine(firstMetaOrPlaceholder)) {
            val finalMeta = collectFinalDisconnectedDiagnosis(firstMetaOrPlaceholder)
            AppLogger.i(this, TAG, "", "final diagnosis", finalMeta)
            metricsMetaSource = finalMeta
        }
        resolveMetricsAsyncAndRefresh(metricsMetaSource)
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

    private fun startGboardServiceAndCheckResult(): Boolean {
        runShizukuCommandSilently(START_GBOARD_SERVICE_COMMAND)
        return waitForGboardServiceRunning()
    }

    private fun waitForGboardServiceRunning(): Boolean {
        repeat(GBOARD_SERVICE_CHECK_ATTEMPTS) { attempt ->
            val result = runShizukuCommandSilently(CHECK_GBOARD_SERVICE_COMMAND)
            val output = result.output.orEmpty()
            if (
                output.contains(GBOARD_IME_COMPONENT, ignoreCase = true) ||
                output.contains(GBOARD_IME_CLASS, ignoreCase = true)
            ) {
                return true
            }
            if (attempt < GBOARD_SERVICE_CHECK_ATTEMPTS - 1) {
                sleep(GBOARD_SERVICE_CHECK_DELAY_MS)
            }
        }
        return false
    }

    private fun runShizukuCommand(command: String): DumpsysResult {
        return runShizukuCommandInternal(command, shouldLog = true)
    }

    private fun runShizukuCommandSilently(command: String): DumpsysResult {
        return runShizukuCommandInternal(command, shouldLog = false)
    }

    private fun runShizukuCommandInternal(command: String, shouldLog: Boolean): DumpsysResult {
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
            val result = ShizukuProcessRunner.run(
                process = process,
                timeoutMs = SHIZUKU_COMMAND_TIMEOUT_MS,
                destroyGraceMs = SHIZUKU_PROCESS_DESTROY_GRACE_MS,
                streamReadGraceMs = SHIZUKU_STREAM_READ_GRACE_MS
            )
            if (result.timedOut) {
                val reason = buildShizukuTimeoutReason()
                AppLogger.w(
                    this,
                    TAG,
                    "shizuku_cmd",
                    "Command timeout",
                    "command=$command, timeoutMs=$SHIZUKU_COMMAND_TIMEOUT_MS"
                )
                return DumpsysResult(output = null, reason = reason)
            }
            val output = result.stdout
            val error = result.stderr
            val exitCode = result.exitCode ?: -1
            if (exitCode != 0) {
                val reason = if (error.isNotBlank()) error.trim() else "Non-zero exit ($exitCode): $command"
                if (shouldLog) {
                    AppLogger.w(
                        this,
                        TAG,
                        "shizuku_cmd",
                        "Command failed",
                        "command=$command, exit=$exitCode, error=${reason.take(300)}"
                    )
                }
                return DumpsysResult(
                    output = null,
                    reason = reason
                )
            }
            val merged = output.ifBlank { error }.takeIf { it.isNotBlank() }
            if (shouldLog && merged.isNullOrBlank()) {
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
            if (shouldLog) {
                AppLogger.e(
                    this,
                    TAG,
                    "shizuku_cmd",
                    "Command exception",
                    it,
                    "command=$command"
                )
            }
            DumpsysResult(output = null, reason = "Command exception: ${it.message}")
        }
    }

    private fun buildShizukuTimeoutReason(): String {
        return "Command timeout after ${SHIZUKU_COMMAND_TIMEOUT_MS}ms"
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
        runCatching { unregisterReceiver(runtimeMirrorReceiver) }
        runCatching { contentResolver.unregisterContentObserver(castStateObserver) }
        ioExecutor.shutdownNow()
        diagnosticsExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ImeSwitchService"
        private const val MIRROR_EVENT_NAME = "MIRROR_DEVICE_CHANGED"
        private const val CAST_STATE_OBSERVER_EVENT_NAME = "CAST_STATE_OBSERVER"
        private const val CAST_STATE_CHANGED_EVENT_NAME = "CAST_STATE_CHANGED"
        private const val CHANNEL_ID = "ime_switch_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DERIVED_EVENT_DUPLICATE_SUPPRESSION_WINDOW_MS = 1_500L
        private const val SHIZUKU_COMMAND_TIMEOUT_MS = 10_000L
        private const val SHIZUKU_PROCESS_DESTROY_GRACE_MS = 1_000L
        private const val SHIZUKU_STREAM_READ_GRACE_MS = 1_000L
        private const val START_GBOARD_SERVICE_COMMAND =
            "am startservice -n com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        private const val CHECK_GBOARD_SERVICE_COMMAND =
            "dumpsys activity service com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        private const val GBOARD_IME_COMPONENT =
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        private const val GBOARD_IME_CLASS = "com.android.inputmethod.latin.LatinIME"
        private const val GBOARD_SERVICE_CHECK_DELAY_MS = 300L
        private const val GBOARD_SERVICE_CHECK_ATTEMPTS = 3
        private const val RECHECK_DELAY_MS = 30_000L
        private const val RECHECK_MAX_ATTEMPTS = 3
        private const val API_TIMEOUT_MS = 5_000
        private const val COUNTRY_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val PLACEHOLDER_METRIC = "-"
        private const val FCM_PLACEHOLDER_META = "diagnosis=pending"
        private const val ACTION_MIRROR_DEVICE_CHANGED = "miui.intent.action.MIRROR_DEVICE_CHANGED"
        private val OBSERVED_CAST_SETTINGS = listOf(
            ObservedSetting(SettingNamespace.SECURE, "screen_project_in_screening"),
            ObservedSetting(SettingNamespace.SECURE, "cast_mode"),
            ObservedSetting(SettingNamespace.SECURE, "screen_project_small_window_on"),
            ObservedSetting(SettingNamespace.SECURE, "screen_project_hang_up_on"),
            ObservedSetting(SettingNamespace.SECURE, "synergy_mode"),
            ObservedSetting(SettingNamespace.SECURE, "mirror_input_state"),
            ObservedSetting(SettingNamespace.GLOBAL, "mirror_switch"),
            ObservedSetting(SettingNamespace.GLOBAL, "ucar_casting_state"),
            ObservedSetting(SettingNamespace.GLOBAL, "VTCAMERA_CAMERA_STATUS"),
            ObservedSetting(SettingNamespace.GLOBAL, "VTCAMERA_CAMERA_DEVICETYPE")
        )

        const val ACTION_START = "com.example.fcmkeepalive.action.START"
    }

    private data class DeviceStateSnapshot(
        val isKeyguardLocked: Boolean,
        val isDeviceLocked: Boolean,
        val isInteractive: Boolean
    )

    private data class ObservedSetting(
        val namespace: SettingNamespace,
        val key: String
    ) {
        val uri: Uri
            get() = when (namespace) {
                SettingNamespace.GLOBAL -> Settings.Global.getUriFor(key)
                SettingNamespace.SECURE -> Settings.Secure.getUriFor(key)
                SettingNamespace.SYSTEM -> Settings.System.getUriFor(key)
            }
    }

    private enum class SettingNamespace {
        GLOBAL,
        SECURE,
        SYSTEM
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
