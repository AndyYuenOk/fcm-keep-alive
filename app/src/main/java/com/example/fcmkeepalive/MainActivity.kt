package com.example.fcmkeepalive

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val prefs by lazy { AppPrefs(this) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var logs by mutableStateOf<List<LogEntry>>(emptyList())
    private var showGrantShizukuButton by mutableStateOf(true)
    private var isMenuExpanded by mutableStateOf(false)
    private var isImeDialogVisible by mutableStateOf(false)
    private var isKeepAliveModeDialogVisible by mutableStateOf(false)
    private var isPowerkeeperDialogVisible by mutableStateOf(false)
    private var powerkeeperDialogText by mutableStateOf("")
    private var imeDialogOptions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var imeDialogSelectedIndex by mutableStateOf(0)
    private var keepAliveModeDialogSelectedIndex by mutableStateOf(0)
    private var snackbarHostState: SnackbarHostState? = null
    private var pendingSnackbarMessage by mutableStateOf<String?>(null)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshLogs()
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshLogs() }
    private val shizukuRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                grantWriteSecureSettingsByShizuku()
            } else {
                showSnackbarMessage("Shizuku permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val isDarkTheme = isSystemInDarkTheme()
        val context = LocalContext.current
        val view = LocalView.current
        val colorScheme = if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (!view.isInEditMode) {
            SideEffect {
                this@MainActivity.enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(colorScheme.background.toArgb())
                    } else {
                        SystemBarStyle.light(
                            colorScheme.background.toArgb(),
                            colorScheme.background.toArgb()
                        )
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(colorScheme.surface.toArgb())
                    } else {
                        SystemBarStyle.light(
                            colorScheme.surface.toArgb(),
                            colorScheme.surface.toArgb()
                        )
                    }
                )
            }
        }
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(shizukuRequestPermissionResultListener)
        prefs.registerChangeListener(prefListener)
        ensureServiceRunning()
        refreshLogs()
        refreshGrantButtonVisibility()
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
        refreshGrantButtonVisibility()
    }

    override fun onStop() {
        super.onStop()
        Shizuku.removeRequestPermissionResultListener(shizukuRequestPermissionResultListener)
        prefs.unregisterChangeListener(prefListener)
    }

    @Composable
    private fun MainScreen() {
        val localSnackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        snackbarHostState = localSnackbarHostState

        LaunchedEffect(pendingSnackbarMessage) {
            val message = pendingSnackbarMessage ?: return@LaunchedEffect
            localSnackbarHostState.showSnackbar(message)
            pendingSnackbarMessage = null
        }

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = localSnackbarHostState) },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(visible = isMenuExpanded) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            if (showGrantShizukuButton) {
                                ExtendedFloatingActionButton(
                                    text = { Text("Grant Shizuku") },
                                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                                    onClick = {
                                        requestShizukuAndGrantSecureSettings()
                                        isMenuExpanded = false
                                    }
                                )
                            }
                            ExtendedFloatingActionButton(
                                text = { Text("Keep Alive Mode") },
                                icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                onClick = {
                                    showKeepAliveModeSelector()
                                    isMenuExpanded = false
                                }
                            )
                            ExtendedFloatingActionButton(
                                text = { Text("Choose IME") },
                                icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                                onClick = {
                                    showImeSelector()
                                    isMenuExpanded = false
                                }
                            )
                            ExtendedFloatingActionButton(
                                text = { Text("IME Settings") },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                    isMenuExpanded = false
                                }
                            )
                            ExtendedFloatingActionButton(
                                text = { Text("PowerKeeper No restrictions") },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    queryPowerkeeperScenario8()
                                    isMenuExpanded = false
                                }
                            )
                            ExtendedFloatingActionButton(
                                text = { Text("Clean FCM Whitelist") },
                                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                                onClick = {
                                    cleanFcmClientWhitelist()
                                    isMenuExpanded = false
                                }
                            )
                            ExtendedFloatingActionButton(
                                text = { Text("FCM Diagnostics") },
                                icon = { Icon(Icons.Default.LocalFireDepartment, contentDescription = null) },
                                onClick = {
                                    openFcmDiagnostics()
                                    isMenuExpanded = false
                                }
                            )
                            ExtendedFloatingActionButton(
                                text = { Text("Clear Logs") },
                                icon = { Icon(Icons.Default.ClearAll, contentDescription = null) },
                                onClick = {
                                    AppLogger.clearLogs(this@MainActivity)
                                    refreshLogs()
                                    scope.launch { localSnackbarHostState.showSnackbar("Logs cleared") }
                                    isMenuExpanded = false
                                }
                            )
                        }
                    }

                    FloatingActionButton(onClick = { isMenuExpanded = !isMenuExpanded }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { entry ->
                        LogItem(entry)
                    }
                }
            }
        }

        if (isImeDialogVisible) {
            ImeSelectorDialog()
        }
        if (isKeepAliveModeDialogVisible) {
            KeepAliveModeDialog()
        }
        if (isPowerkeeperDialogVisible) {
            PowerkeeperResultDialog()
        }
    }

    @Composable
    private fun ImeSelectorDialog() {
        AlertDialog(
            onDismissRequest = { isImeDialogVisible = false },
            title = { Text("Choose IME") },
            text = {
                Column {
                    imeDialogOptions.forEachIndexed { index, imePair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { imeDialogSelectedIndex = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = imeDialogSelectedIndex == index,
                                onClick = { imeDialogSelectedIndex = index }
                            )
                            Text(
                                text = imePair.first,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (imeDialogOptions.isNotEmpty()) {
                            val selectedImeId = imeDialogOptions[imeDialogSelectedIndex].second
                            prefs.setChosenImeId(selectedImeId)
                            showSnackbarMessage("IME ID saved")
                        }
                        isImeDialogVisible = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { isImeDialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    @Composable
    private fun KeepAliveModeDialog() {
        val options = KeepAliveMode.entries
        AlertDialog(
            onDismissRequest = { isKeepAliveModeDialogVisible = false },
            title = { Text("Keep Alive Mode") },
            text = {
                Column {
                    options.forEachIndexed { index, mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { keepAliveModeDialogSelectedIndex = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = keepAliveModeDialogSelectedIndex == index,
                                onClick = { keepAliveModeDialogSelectedIndex = index }
                            )
                            Text(
                                text = mode.displayName,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMode = options[keepAliveModeDialogSelectedIndex]
                        prefs.setKeepAliveMode(selectedMode)
                        showSnackbarMessage("Current mode: ${selectedMode.displayName}")
                        isKeepAliveModeDialogVisible = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { isKeepAliveModeDialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    @Composable
    private fun PowerkeeperResultDialog() {
        AlertDialog(
            onDismissRequest = { isPowerkeeperDialogVisible = false },
            title = { Text("PowerKeeper") },
            text = {
                Text(
                    text = "No restrictions\n$powerkeeperDialogText",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = { isPowerkeeperDialogVisible = false }) {
                    Text("Close")
                }
            }
        )
    }

    @Composable
    private fun LogItem(entry: LogEntry) {
        val time = dateFormat.format(Date(entry.timestamp))
        val metaLines = entry.meta
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        val normalizedEvent = entry.event.removeSuffix("_IME")
        val metaMap = parseMetaMap(metaLines)
        val isLegacyImeEvent = entry.event.endsWith("_IME")
        val isImeMetaLog = metaLines.any { it == "mode=ime" } && metaLines.any { it.startsWith("imeId=") }
        val hasFcmMeta = hasFcmDiagnosticsMeta(metaLines)
        val hasBatteryAcMeta = isBatteryAcLog(metaMap)
        val shouldCondenseImeLog = isImeMetaLog || (isLegacyImeEvent && !hasFcmDiagnosticsMeta(metaLines))
        val contentLines = when {
            entry.event == "fcm_metric" && entry.message == "country_latency" -> {
                listOf(buildCountryLatencySummary(metaLines))
            }
            shouldCondenseImeLog -> {
                listOf("$normalizedEvent, ${entry.message}")
            }
            hasBatteryAcMeta && hasFcmMeta -> {
                listOf(buildBatteryAcSummaryLine(normalizedEvent, metaMap)) + extractFcmMetaLines(metaLines)
            }
            entry.event.isNotBlank() && !hasFcmMeta -> {
                listOf(buildNonFcmSummaryLine(entry, normalizedEvent, metaMap))
            }
            else -> {
                val summaryLine = when {
                    entry.event.isBlank() -> entry.message
                    entry.event == "fcm_metric" -> entry.message
                    hasFcmMeta &&
                        entry.message.contains("switch success", ignoreCase = true) ->
                        "${normalizedEvent}, ${entry.message}"
                    else -> "${normalizedEvent} ${entry.message}"
                }
                listOf(summaryLine) + metaLines
            }
        }
        val selectableText = buildString {
            appendLine(time)
            contentLines.forEach { line -> appendLine(line) }
        }.trimEnd()

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            SelectionContainer {
                Text(
                    text = selectableText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
        }
    }

    private fun buildCountryLatencySummary(metaLines: List<String>): String {
        val metaMap = parseMetaMap(metaLines)
        val countryCode = metaMap["countryCode"].orEmpty().ifBlank { "-" }
        val ip = metaMap["ip"].orEmpty().ifBlank { "-" }
        val cache = metaMap["cache"].orEmpty().ifBlank { "-" }
        return "$countryCode $ip cache=$cache"
    }

    private fun buildNonFcmSummaryLine(
        entry: LogEntry,
        normalizedEvent: String,
        metaMap: Map<String, String>
    ): String {
        if (isBatteryAcLog(metaMap)) {
            return buildBatteryAcSummaryLine(normalizedEvent, metaMap)
        }
        return if (entry.event.isBlank()) {
            entry.message
        } else {
            "$normalizedEvent, ${entry.message}"
        }
    }

    private fun buildBatteryAcSummaryLine(normalizedEvent: String, metaMap: Map<String, String>): String {
        val eventLabel = normalizedEvent.removeSuffix("_AC")
        val failedStep = metaMap["failedStep"].orEmpty().takeUnless { it.isBlank() || it == "-" }
        val steps = mutableListOf<String>()
        val acValue = extractBatteryAcValue(metaMap["batteryCommand"])

        appendStepResult(
            steps = steps,
            label = "mute",
            wasAttempted = metaMap["disablePowerSoundsCommand"].hasRealValue(),
            didFail = metaMap["disablePowerSoundsReason"].hasRealValue() || failedStep == "power_sounds_disable"
        )
        appendStepResult(
            steps = steps,
            label = if (acValue == "reset") "battery reset" else if (acValue != null) "set ac $acValue" else "set ac",
            wasAttempted = metaMap["batteryCommand"].hasRealValue(),
            didFail = failedStep == "battery_reset"
        )
        appendStepResult(
            steps = steps,
            label = "power key",
            wasAttempted = metaMap["powerKeyCommand"].hasRealValue(),
            didFail = failedStep == "power_key"
        )
        appendStepResult(
            steps = steps,
            label = "restore sounds",
            wasAttempted = metaMap["restorePowerSoundsCommand"].hasRealValue(),
            didFail = metaMap["restorePowerSoundsReason"].hasRealValue() || failedStep == "power_sounds_restore"
        )

        return (listOf(eventLabel) + steps).joinToString(separator = ", ")
    }

    private fun appendStepResult(
        steps: MutableList<String>,
        label: String,
        wasAttempted: Boolean,
        didFail: Boolean
    ) {
        if (!wasAttempted) return
        steps += "$label ${if (didFail) "failed" else "success"}"
    }

    private fun isBatteryAcLog(metaMap: Map<String, String>): Boolean {
        return metaMap["mode"] == "battery_ac"
    }

    private fun extractBatteryAcValue(command: String?): String? {
        val parts = command
            ?.takeIf { it.hasRealValue() }
            ?.trim()
            ?.split(Regex("\\s+"))
            .orEmpty()
        if (parts.size >= 3 && parts[0] == "dumpsys" && parts[1] == "battery" && parts[2] == "reset") {
            return "reset"
        }
        return parts.lastOrNull()
            ?.takeIf { it == "0" || it == "1" }
    }

    private fun parseMetaMap(metaLines: List<String>): Map<String, String> {
        return metaLines.mapNotNull { line ->
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            key to value
        }.toMap()
    }

    private fun String?.hasRealValue(): Boolean {
        return !this.isNullOrBlank() && this != "-"
    }

    private fun hasFcmDiagnosticsMeta(metaLines: List<String>): Boolean {
        return metaLines.any { it.startsWith("Heartbeat:", ignoreCase = true) } ||
            metaLines.any { it.startsWith("Last ping:", ignoreCase = true) } ||
            metaLines.any { it.startsWith("streamId=", ignoreCase = true) } ||
            metaLines.any { it.startsWith("connected=", ignoreCase = true) } ||
            metaLines.any { it.startsWith("diagnosis=", ignoreCase = true) }
    }

    private fun extractFcmMetaLines(metaLines: List<String>): List<String> {
        return metaLines.filter { line ->
            line.startsWith("Heartbeat:", ignoreCase = true) ||
                line.startsWith("Last ping:", ignoreCase = true) ||
                line.startsWith("streamId=", ignoreCase = true) ||
                line.startsWith("connected=", ignoreCase = true) ||
                line.startsWith("diagnosis=", ignoreCase = true) ||
                line.startsWith("connectTime=", ignoreCase = true) ||
                line.startsWith("Adaptive Heartbeat type", ignoreCase = true) ||
                line.startsWith("connectionsLimit:", ignoreCase = true)
        }
    }

    private fun ensureServiceRunning() {
        val serviceIntent = Intent(this, ImeSwitchService::class.java).apply {
            action = ImeSwitchService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun openFcmDiagnostics() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.google.android.gms",
                "com.google.android.gms.gcm.GcmDiagnostics"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            showSnackbarMessage("FCM Diagnostics is unavailable")
        }
    }

    private fun requestShizukuAndGrantSecureSettings() {
        if (!Shizuku.pingBinder()) {
            showSnackbarMessage("Shizuku is not running. Please start Shizuku first.")
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            grantWriteSecureSettingsByShizuku()
            return
        }

        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    private fun cleanFcmClientWhitelist() {
        if (!ensureShizukuReadyForCommand(::showSnackbarMessage)) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                cleanFcmClientWhitelistInternal()
            }
            showSnackbarMessage(result.message)
            refreshLogs()
        }
    }

    private fun queryPowerkeeperScenario8() {
        if (!ensureShizukuReadyForCommand(::showPowerkeeperDialog)) return
        lifecycleScope.launch {
            val dialogText = withContext(Dispatchers.IO) {
                val result = runShizukuCommand("dumpsys activity service com.miui.powerkeeper/.PowerKeeperBackgroundService")
                if (!result.success) {
                    return@withContext "Query failed:\n${result.error ?: "unknown error"}"
                }
                val matchedPackages = result.output.lineSequence()
                    .filter { it.contains("scenario:8", ignoreCase = true) }
                    .mapNotNull { line ->
                        val trimmed = line.trim()
                        val start = trimmed.indexOf("package:", ignoreCase = true)
                        if (start < 0) return@mapNotNull null
                        val value = trimmed.substring(start + "package:".length).trim()
                        val pkg = value.substringBefore(" ").trim()
                        pkg.takeIf { PACKAGE_NAME_REGEX.matches(it) }
                    }
                    .distinct()
                    .toList()
                if (matchedPackages.isEmpty()) {
                    "No package matched: scenario:8"
                } else {
                    matchedPackages.joinToString(separator = "\n")
                }
            }
            showPowerkeeperDialog(dialogText)
        }
    }

    private fun ensureShizukuReadyForCommand(onFail: (String) -> Unit): Boolean {
        if (!Shizuku.pingBinder()) {
            onFail("Shizuku is not running. Please start Shizuku first.")
            return false
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            onFail("Shizuku permission required")
            return false
        }
        return true
    }

    private fun showPowerkeeperDialog(text: String) {
        powerkeeperDialogText = text.ifBlank { "(empty)" }
        isPowerkeeperDialogVisible = true
    }

    private fun cleanFcmClientWhitelistInternal(): WhitelistCleanupResult {
        val queryResult = queryFcmClientPackages()
        if (!queryResult.success) {
            AppLogger.w(
                this,
                "MainActivity",
                "fcm_whitelist",
                "Failed to query FCM client apps",
                queryResult.error?.take(300)
            )
            return WhitelistCleanupResult("Failed to query FCM client apps")
        }
        val rawPackages = queryResult.packages
        val packages = rawPackages.filterNot { EXCLUDED_FCM_PACKAGES.contains(it) }
        if (packages.isEmpty()) {
            AppLogger.i(
                this,
                "MainActivity",
                "fcm_whitelist",
                "No FCM client apps found"
            )
            return WhitelistCleanupResult("No FCM client apps found")
        }

        val whitelistSnapshot = getCurrentDeviceIdleWhitelistPackages()
        if (!whitelistSnapshot.success) {
            AppLogger.w(
                this,
                "MainActivity",
                "fcm_whitelist",
                "Failed to query deviceidle whitelist",
                whitelistSnapshot.error?.take(300)
            )
            return WhitelistCleanupResult("Failed to query deviceidle whitelist")
        }

        val inWhitelistTargets = packages
            .filter { it != packageName }
            .filter { whitelistSnapshot.packages.contains(it) }
            .distinct()
        val notInWhitelist = packages
            .filter { it != packageName }
            .count { !whitelistSnapshot.packages.contains(it) }

        if (inWhitelistTargets.isEmpty()) {
            val message = if (notInWhitelist > 0) {
                "Whitelist cleanup: nothing to remove"
            } else {
                "Whitelist cleanup: no removable apps"
            }
            AppLogger.i(
                this,
                "MainActivity",
                "fcm_whitelist",
                message,
                buildString {
                    appendLine("queried=${packages.size}")
                    appendLine("inWhitelist=0")
                    appendLine("notInWhitelist=$notInWhitelist")
                    append("removed=0")
                }
            )
            return WhitelistCleanupResult(message)
        }

        val removalResult = removeFromDeviceIdleWhitelist(inWhitelistTargets)
        val summary = "Whitelist cleanup: removed ${removalResult.removed}/${inWhitelistTargets.size}"
        val message = if (removalResult.failed > 0) {
            "$summary, failed ${removalResult.failed}"
        } else {
            summary
        }
        val meta = buildString {
            appendLine("queried=${packages.size}")
            appendLine("inWhitelist=${inWhitelistTargets.size}")
            appendLine("notInWhitelist=$notInWhitelist")
            appendLine("removed=${removalResult.removed}")
            appendLine("failed=${removalResult.failed}")
            if (removalResult.removedPackages.isNotEmpty()) {
                appendLine("removedPackages:")
                removalResult.removedPackages.forEach { pkg ->
                    appendLine(pkg)
                }
            }
            if (removalResult.failedPackages.isNotEmpty()) {
                append("failedPackages=${removalResult.failedPackages.joinToString("|")}")
            } else {
                // Remove trailing newline for cleaner rendering when no failed packages exist.
                if (endsWith("\n")) deleteCharAt(lastIndex)
            }
        }
        if (removalResult.failed > 0) {
            AppLogger.w(this, "MainActivity", "fcm_whitelist", message, meta)
        } else {
            AppLogger.i(this, "MainActivity", "fcm_whitelist", message, meta)
        }
        return WhitelistCleanupResult(message)
    }

    private fun getCurrentDeviceIdleWhitelistPackages(): WhitelistSnapshotResult {
        val primary = runShizukuCommand("cmd deviceidle whitelist")
        if (primary.success) {
            return WhitelistSnapshotResult(
                success = true,
                packages = parseDeviceIdleWhitelistPackages(primary.output),
                error = null
            )
        }

        val fallback = runShizukuCommand("dumpsys deviceidle whitelist")
        if (fallback.success) {
            return WhitelistSnapshotResult(
                success = true,
                packages = parseDeviceIdleWhitelistPackages(fallback.output),
                error = null
            )
        }

        return WhitelistSnapshotResult(
            success = false,
            packages = emptySet(),
            error = fallback.error ?: primary.error ?: "Unknown whitelist query error"
        )
    }

    private fun parseDeviceIdleWhitelistPackages(raw: String): Set<String> {
        val result = linkedSetOf<String>()
        PACKAGE_NAME_IN_TEXT_REGEX.findAll(raw).forEach { match ->
            val packageName = match.value.trim().trimEnd(',', '.', ':', ';')
            if (PACKAGE_NAME_REGEX.matches(packageName)) {
                result.add(packageName)
            }
        }
        return result
    }

    private fun queryFcmClientPackages(): FcmClientQueryResult {
        val commandResult = runShizukuCommand(
            "dumpsys activity service com.google.android.gms/.gcm.GcmService"
        )
        if (!commandResult.success) {
            return FcmClientQueryResult(
                success = false,
                packages = emptyList(),
                error = commandResult.error
            )
        }
        val lines = commandResult.output.lineSequence().toList()
        val packages = linkedSetOf<String>()
        var inBlock = false
        for (line in lines) {
            val trimmed = line.trim()
            if (!inBlock) {
                if (trimmed.startsWith("Apps supporting client queue:")) {
                    inBlock = true
                }
                continue
            }
            if (trimmed.startsWith("Affinity Scores")) {
                break
            }
            if (trimmed.isBlank()) continue

            val firstToken = trimmed.substringBefore(" ").trim()
            val packageName = firstToken.substringBefore(":").trim()
            if (PACKAGE_NAME_REGEX.matches(packageName)) {
                packages.add(packageName)
            }
        }
        return FcmClientQueryResult(
            success = true,
            packages = packages.toList(),
            error = null
        )
    }

    private fun removeFromDeviceIdleWhitelist(packages: List<String>): WhitelistRemovalResult {
        var removed = 0
        var failed = 0
        val removedPackages = mutableListOf<String>()
        val failedPackages = mutableListOf<String>()

        packages.forEach { pkg ->
            val primary = runShizukuCommand("cmd deviceidle whitelist -$pkg")
            if (primary.success) {
                removed += 1
                removedPackages.add(pkg)
                return@forEach
            }

            val fallback = runShizukuCommand("dumpsys deviceidle whitelist -$pkg")
            if (fallback.success) {
                removed += 1
                removedPackages.add(pkg)
                return@forEach
            }

            failed += 1
            val reason = fallback.error ?: primary.error ?: "unknown error"
            failedPackages.add("$pkg(${reason.take(80)})")
        }

        return WhitelistRemovalResult(
            removed = removed,
            failed = failed,
            removedPackages = removedPackages,
            failedPackages = failedPackages
        )
    }

    private fun runShizukuCommand(command: String): CommandResult {
        return try {
            if (!Shizuku.pingBinder()) {
                val reason = "Shizuku not running"
                AppLogger.w(this, "MainActivity", "shizuku_cmd", reason, "command=$command")
                return CommandResult(success = false, output = "", error = reason)
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                val reason = "Shizuku permission denied"
                AppLogger.w(this, "MainActivity", "shizuku_cmd", reason, "command=$command")
                return CommandResult(success = false, output = "", error = reason)
            }
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
            val output = process.inputStream?.bufferedReader()?.readText().orEmpty()
            val error = process.errorStream?.bufferedReader()?.readText().orEmpty()
            val code = process.waitFor()
            val success = code == 0
            if (!success) {
                val reason = error.ifBlank { "exit code $code" }
                AppLogger.w(
                    this,
                    "MainActivity",
                    "shizuku_cmd",
                    "Command failed",
                    "command=$command, exit=$code, error=${reason.take(300)}"
                )
            } else if (output.isBlank() && error.isBlank()) {
                AppLogger.w(
                    this,
                    "MainActivity",
                    "shizuku_cmd",
                    "Command succeeded but no output",
                    "command=$command"
                )
            }
            CommandResult(
                success = success,
                output = output,
                error = if (success) null else error.ifBlank { "exit code $code" }
            )
        } catch (t: Throwable) {
            AppLogger.e(
                this,
                "MainActivity",
                "shizuku_cmd",
                "Command exception",
                t,
                "command=$command"
            )
            CommandResult(success = false, output = "", error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun grantWriteSecureSettingsByShizuku() {
        try {
            val cmd = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as Process

            val output = process.inputStream?.bufferedReader()?.readText().orEmpty()
            val error = process.errorStream?.bufferedReader()?.readText().orEmpty()
            val code = process.waitFor()

            if (code == 0) {
                showSnackbarMessage("WRITE_SECURE_SETTINGS granted successfully")
                AppLogger.i(this, "MainActivity", "SHIZUKU_GRANT", "WRITE_SECURE_SETTINGS granted successfully")
            } else {
                val message = if (error.isNotBlank()) error else output
                showSnackbarMessage("Grant failed: $message")
                AppLogger.w(this, "MainActivity", "SHIZUKU_GRANT", "Grant failed", message)
            }
        } catch (t: Throwable) {
            showSnackbarMessage("Grant exception: ${t.message}")
            AppLogger.e(this, "MainActivity", "SHIZUKU_GRANT", "Grant exception", t)
        } finally {
            refreshGrantButtonVisibility()
        }
    }

    private fun showImeSelector() {
        val imePairs = ImeHelper.getAllImePairs(this)
        if (imePairs.isEmpty()) {
            showSnackbarMessage("No non-Gboard IMEs found")
            return
        }
        imeDialogOptions = imePairs
        imeDialogSelectedIndex = imePairs.indexOfFirst { it.second == prefs.getChosenImeId() }
            .let { if (it < 0) 0 else it }
        isImeDialogVisible = true
    }

    private fun showKeepAliveModeSelector() {
        keepAliveModeDialogSelectedIndex = KeepAliveMode.entries
            .indexOf(prefs.getKeepAliveMode())
            .let { if (it < 0) 0 else it }
        isKeepAliveModeDialogVisible = true
    }

    private fun showSnackbarMessage(message: String) {
        val hostState = snackbarHostState
        if (hostState == null) {
            pendingSnackbarMessage = message
            return
        }
        lifecycleScope.launch {
            hostState.showSnackbar(message)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshLogs() {
        logs = AppLogger.getLogs(this)
    }

    private fun refreshGrantButtonVisibility() {
        showGrantShizukuButton = !ImeHelper.hasWriteSecureSettings(this)
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
        private val PACKAGE_NAME_IN_TEXT_REGEX = Regex("\\b[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+\\b")
        private val EXCLUDED_FCM_PACKAGES = setOf("com.android.vending")
    }

    private data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String?
    )

    private data class FcmClientQueryResult(
        val success: Boolean,
        val packages: List<String>,
        val error: String?
    )

    private data class WhitelistRemovalResult(
        val removed: Int,
        val failed: Int,
        val removedPackages: List<String>,
        val failedPackages: List<String>
    )

    private data class WhitelistSnapshotResult(
        val success: Boolean,
        val packages: Set<String>,
        val error: String?
    )

    private data class WhitelistCleanupResult(
        val message: String
    )
}
