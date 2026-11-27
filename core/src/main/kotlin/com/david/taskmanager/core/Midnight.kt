package com.david.taskmanager.core

import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime

/** Real time until the start of the next local day, accounting for daylight-saving transitions. */
fun durationUntilNextMidnight(clock: Clock): Duration {
    val now = ZonedDateTime.now(clock)
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(clock.zone)
    return Duration.between(now, nextMidnight)
}
