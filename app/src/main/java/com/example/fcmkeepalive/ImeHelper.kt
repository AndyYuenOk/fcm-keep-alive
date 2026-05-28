package com.example.fcmkeepalive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

object ImeHelper {
    const val GBOARD_IME_ID =
        "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setDefaultIme(context: Context, imeId: String): Boolean {
        return Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            imeId
        )
    }

    fun getEnabledImeIds(context: Context): Set<String> {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return imm.enabledInputMethodList.map { it.id }.toSet()
    }

    fun getAllImePairs(context: Context): List<Pair<String, String>> {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        val pm = context.packageManager
        return imm.enabledInputMethodList.map { info ->
            val label = info.loadLabel(pm).toString().trim()
            val displayName = label.ifBlank { info.packageName }
            displayName to info.id
        }.filter { it.second != GBOARD_IME_ID }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    fun resolveImeDisplayName(context: Context, imeId: String): String? {
        if (imeId.isBlank()) return null
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return null
        val pm = context.packageManager
        return imm.enabledInputMethodList.firstOrNull { it.id == imeId }?.let { info ->
            val label = info.loadLabel(pm).toString().trim()
            label.ifBlank { info.packageName }
        }
    }

    fun resolveGboardImeId(): String {
        return GBOARD_IME_ID
    }
}
