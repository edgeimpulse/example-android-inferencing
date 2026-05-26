package com.edgeimpulse.gattsensors

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists voice-control preferences:
 *  - whether wake-word listening (KWS) is enabled at all,
 *  - the KWS detection threshold (0..1),
 *  - and an optional "default action" that fires after the wake word
 *    is heard. When the default action is enabled the
 *    [voice.VoiceCommandManager] skips the STT step entirely and
 *    immediately starts a labelled multi-stream recording for the
 *    configured duration — useful for one-shot field captures where the
 *    user just wants to say "hey android" and have a sample land in
 *    Edge Impulse without speaking a full command.
 */
class VoiceSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ei_voice_prefs", Context.MODE_PRIVATE)

    data class VoiceSettings(
        val voiceControlEnabled: Boolean,
        val kwsThreshold: Float,
        val defaultActionEnabled: Boolean,
        val defaultActionLabel: String,
        val defaultActionDurationSec: Int,
    ) {
        val defaultActionDurationMs: Long
            get() = defaultActionDurationSec.coerceAtLeast(1) * 1000L
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<VoiceSettings> = _state.asStateFlow()

    fun get(): VoiceSettings = _state.value

    fun update(
        voiceControlEnabled: Boolean,
        kwsThreshold: Float,
        defaultActionEnabled: Boolean,
        defaultActionLabel: String,
        defaultActionDurationSec: Int,
    ) {
        prefs.edit()
            .putBoolean(PREF_VOICE_ENABLED, voiceControlEnabled)
            .putFloat(PREF_KWS_THRESHOLD, kwsThreshold.coerceIn(0.05f, 0.99f))
            .putBoolean(PREF_DEFAULT_ENABLED, defaultActionEnabled)
            .putString(PREF_DEFAULT_LABEL, defaultActionLabel.trim())
            .putInt(PREF_DEFAULT_DURATION, defaultActionDurationSec.coerceAtLeast(1))
            .apply()
        _state.value = load()
    }

    private fun load(): VoiceSettings = VoiceSettings(
        voiceControlEnabled      = prefs.getBoolean(PREF_VOICE_ENABLED, false),
        kwsThreshold             = prefs.getFloat(PREF_KWS_THRESHOLD, DEFAULT_THRESHOLD),
        defaultActionEnabled     = prefs.getBoolean(PREF_DEFAULT_ENABLED, false),
        defaultActionLabel       = prefs.getString(PREF_DEFAULT_LABEL, "") ?: "",
        defaultActionDurationSec = prefs.getInt(PREF_DEFAULT_DURATION, 5),
    )

    companion object {
        const val DEFAULT_THRESHOLD = 0.80f

        private const val PREF_VOICE_ENABLED    = "voice_control_enabled"
        private const val PREF_KWS_THRESHOLD    = "voice_kws_threshold"
        private const val PREF_DEFAULT_ENABLED  = "voice_default_enabled"
        private const val PREF_DEFAULT_LABEL    = "voice_default_label"
        private const val PREF_DEFAULT_DURATION = "voice_default_duration_sec"
    }
}
