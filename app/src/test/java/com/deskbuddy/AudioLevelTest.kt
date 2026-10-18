package com.deskbuddy
import org.junit.Assert.*
import org.junit.Test
class AudioLevelTest {
    @Test fun silenceStaysSilent() { assertEquals(0f, AudioLevel.fromPcm(ShortArray(640),640),0f) }
    @Test fun loudIsLargerThanQuietAndNeverExceedsOne() {
        val quiet = AudioLevel.fromPcm(ShortArray(640) { 200 },640)
        val loud = AudioLevel.fromPcm(ShortArray(640) { 10000 },640)
        assertTrue(loud > quiet)
        assertTrue(loud <= 1f)
        assertTrue(AudioLevel.fromPcm(ShortArray(640) { Short.MAX_VALUE },640) <= 1f)
    }
    @Test fun emptyAndTruncatedBuffersAreSafe() {
        assertEquals(0f, AudioLevel.fromPcm(shortArrayOf(0),0),0f)
        assertEquals(0f, AudioLevel.fromPcm(shortArrayOf(0),20),0f)
    }
}
