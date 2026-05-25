/*
 * Copyright (c) 2025 EdgeImpulse Inc.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.edgeimpulse.gattsensors.voice

/**
 * Parses spoken commands like:
 *   "capture five seconds with label idle"
 *   "capture 10s label up down"
 *   "record 20 seconds circle"
 * into a structured [VoiceCommand].
 */
object VoiceCommandParser {

    /** Supported labels mapped to their canonical form. */
    private val LABELS = mapOf(
        "idle"   to "idle",
        "updown" to "updown",
        "up down" to "updown",
        "up-down" to "updown",
        "circle" to "circle",
    )

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "sixty" to 60,
    )

    private val ACTION_RE = Regex("""(?i)\b(capture|record|start)\b""")
    private val DURATION_RE = Regex("""(?i)(\d+)\s*(s|sec|secs|second|seconds)?""")
    private val WORD_DURATION_RE = Regex(
        "(?i)\\b(${NUMBER_WORDS.keys.joinToString("|")})\\s*(s|sec|secs|second|seconds)?\\b"
    )

    /**
     * Try to parse [transcript]; returns null when the text does not look like
     * a capture command or is ambiguous.
     */
    fun parse(transcript: String?): VoiceCommand? {
        if (transcript.isNullOrBlank()) return null
        val text = transcript.lowercase().trim()

        if (!ACTION_RE.containsMatchIn(text)) return null

        val durationSec = extractDuration(text) ?: return null
        val label = extractLabel(text) ?: return null

        return VoiceCommand(
            durationSeconds = durationSec,
            label = label,
            raw = transcript,
        )
    }

    private fun extractDuration(text: String): Int? {
        DURATION_RE.find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        WORD_DURATION_RE.find(text)?.let { m ->
            NUMBER_WORDS[m.groupValues[1]]?.let { return it }
        }
        return null
    }

    private fun extractLabel(text: String): String? {
        // Prefer text after "label "
        val labelHint = Regex("""(?i)\blabel\s+(.+)$""").find(text)?.groupValues?.get(1)
        val haystack = labelHint ?: text
        for ((alias, canon) in LABELS) {
            if (haystack.contains(alias)) return canon
        }
        return null
    }
}

/** Parsed voice command — durationSeconds always > 0 and label is canonical. */
data class VoiceCommand(
    val durationSeconds: Int,
    val label: String,
    val raw: String,
) {
    val durationMs: Long get() = durationSeconds * 1000L
}
