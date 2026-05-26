package com.edgeimpulse.gattsensors.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {

    @Test fun `null and blank input return null`() {
        assertNull(VoiceCommandParser.parse(null))
        assertNull(VoiceCommandParser.parse(""))
        assertNull(VoiceCommandParser.parse("   "))
    }

    @Test fun `transcript without action verb returns null`() {
        assertNull(VoiceCommandParser.parse("five seconds with label idle"))
    }

    @Test fun `digit duration with explicit label keyword`() {
        val cmd = VoiceCommandParser.parse("capture 10 seconds with label idle")
        assertNotNull(cmd)
        assertEquals(10, cmd!!.durationSeconds)
        assertEquals("idle", cmd.label)
        assertEquals(10_000L, cmd.durationMs)
    }

    @Test fun `record verb with word duration`() {
        val cmd = VoiceCommandParser.parse("record five seconds circle")
        assertNotNull(cmd)
        assertEquals(5, cmd!!.durationSeconds)
        assertEquals("circle", cmd.label)
    }

    @Test fun `start verb with hyphenated label maps to canonical form`() {
        val cmd = VoiceCommandParser.parse("start 20s with label up-down")
        assertNotNull(cmd)
        assertEquals(20, cmd!!.durationSeconds)
        assertEquals("updown", cmd.label)
    }

    @Test fun `unknown label returns null`() {
        assertNull(VoiceCommandParser.parse("capture 5 seconds with label dancing"))
    }

    @Test fun `missing duration returns null`() {
        assertNull(VoiceCommandParser.parse("capture with label idle"))
    }

    @Test fun `casing and surrounding noise do not break parser`() {
        val cmd = VoiceCommandParser.parse("Hey Android, CAPTURE 15 SECONDS with label idle please")
        assertNotNull(cmd)
        assertEquals(15, cmd!!.durationSeconds)
        assertEquals("idle", cmd.label)
    }
}
