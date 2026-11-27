package com.david.taskmanager.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class MidnightTest {

    @Test
    fun `time until the next local midnight`() {
        val clock = clockAt(LocalDateTime.of(2026, 12, 1, 22, 30))
        assertEquals(Duration.ofMinutes(90), durationUntilNextMidnight(clock))
    }

    @Test
    fun `exactly at midnight waits a full day`() {
        val clock = clockAt(LocalDateTime.of(2026, 12, 1, 0, 0))
        assertEquals(Duration.ofHours(24), durationUntilNextMidnight(clock))
    }

    @Test
    fun `a 23-hour day when clocks go forward`() {
        // Paris switches to summer time on 29 Mar 2026 at 02:00.
        val clock = clockAt(LocalDateTime.of(2026, 3, 29, 0, 0))
        assertEquals(Duration.ofHours(23), durationUntilNextMidnight(clock))
    }

    @Test
    fun `a 25-hour day when clocks go back`() {
        // Paris switches back to winter time on 25 Oct 2026 at 03:00.
        val clock = clockAt(LocalDateTime.of(2026, 10, 25, 0, 0))
        assertEquals(Duration.ofHours(25), durationUntilNextMidnight(clock))
    }
}
