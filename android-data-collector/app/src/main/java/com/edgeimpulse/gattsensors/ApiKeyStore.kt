package com.edgeimpulse.gattsensors

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists an optional runtime API-key override in SharedPreferences.
 *
 * Priority order:
 *   1. Key saved by the user in the Settings dialog (survives app restarts)
 *   2. BuildConfig.EI_API_KEY  (set at build time via gradle.properties)
 *   3. Empty string (upload calls will fail with 401 until a key is provided)
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ei_prefs", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(resolveKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    /** Returns the active key (runtime override → build-time → empty). */
    fun get(): String = _apiKey.value

    /**
     * Save [key] as the runtime override. Pass an empty string to clear the
     * override and fall back to the build-time key.
     */
    fun set(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(PREF_KEY).apply()
        } else {
            prefs.edit().putString(PREF_KEY, trimmed).apply()
        }
        _apiKey.value = resolveKey()
    }

    /** True when a user-entered override is currently active. */
    fun hasOverride(): Boolean = prefs.contains(PREF_KEY)

    private fun resolveKey(): String {
        val stored = prefs.getString(PREF_KEY, null)
        if (!stored.isNullOrBlank()) return stored
        val buildTime = runCatching { BuildConfig.EI_API_KEY }.getOrNull()
        if (!buildTime.isNullOrBlank()) return buildTime
        return ""
    }

    companion object {
        private const val PREF_KEY = "api_key_override"
    }
}
