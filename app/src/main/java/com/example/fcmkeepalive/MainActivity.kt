package com.example.fcmkeepalive

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import kotlinx.coroutines.launch
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
    private var imeDialogOptions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var imeDialogSelectedIndex by mutableStateOf(0)
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
        val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (isDarkTheme) darkColorScheme() else lightColorScheme()
        }
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
                                text = { Text("FCM Diagnostics") },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    openFcmDiagnostics()
                                    isMenuExpanded = false
                                }
                            )
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
                    .padding(12.dp)
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
    private fun LogItem(entry: LogEntry) {
        val time = dateFormat.format(Date(entry.timestamp))
        val summaryLine = "${entry.event} ${entry.message}"
        val metaLines = entry.meta
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = summaryLine,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            metaLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
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
    }
}
