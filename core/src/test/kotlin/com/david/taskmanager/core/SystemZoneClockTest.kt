package com.david.taskmanager.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class SystemZoneClockTest {

    private val originalZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun `zone follows the current default zone`() {
        val clock = SystemZoneClock()

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals(ZoneId.of("Asia/Tokyo"), clock.zone)

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(ZoneId.of("America/New_York"), clock.zone)
    }

    @Test
    fun `instant is the current system time`() {
        val before = Instant.ofEpochMilli(System.currentTimeMillis())
        val now = SystemZoneClock().instant()
        assertTrue(Duration.between(before, now).abs() < Duration.ofSeconds(5))
    }

    @Test
    fun `withZone returns a clock fixed to that zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        val clock = SystemZoneClock().withZone(tokyo)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(tokyo, clock.zone)
    }
}
