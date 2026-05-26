package com.edgeimpulse.gattsensors

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the voice-control "default action" that fires after the wake
 * word is heard. When enabled the [voice.VoiceCommandManager] skips the
 * STT step entirely and immediately starts a labelled multi-stream
 * recording for the configured duration — useful for one-shot field
 * captures where the user just wants to say "hey android" and have a
 * sample land in Edge Impulse without speaking a full command.
 */
class VoiceSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ei_voice_prefs", Context.MODE_PRIVATE)

    data class DefaultAction(
        val enabled: Boolean,
        val label: String,
        val durationSec: Int,
    ) {
        val durationMs: Long get() = durationSec.coerceAtLeast(1) * 1000L
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<DefaultAction> = _state.asStateFlow()

    fun get(): DefaultAction = _state.value

    fun update(enabled: Boolean, label: String, durationSec: Int) {
        prefs.edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_LABEL, label.trim())
            .putInt(PREF_DURATION, durationSec.coerceAtLeast(1))
            .apply()
        _state.value = load()
    }

    private fun load(): DefaultAction = DefaultAction(
        enabled     = prefs.getBoolean(PREF_ENABLED, false),
        label       = prefs.getString(PREF_LABEL, "") ?: "",
        durationSec = prefs.getInt(PREF_DURATION, 5),
    )

    companion object {
        private const val PREF_ENABLED  = "voice_default_enabled"
        private const val PREF_LABEL    = "voice_default_label"
        private const val PREF_DURATION = "voice_default_duration_sec"
    }
}
