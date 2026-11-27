package com.david.taskmanager.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The production clock: system time in the device's *current* time zone. Unlike
 * `Clock.systemDefaultZone()`, which keeps the zone it was created with, the zone is read on every call, so
 * "today" stays right after the user changes time zone while the app is running.
 */
class SystemZoneClock : Clock() {
    private val utc: Clock = systemUTC()

    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = utc.instant()

    override fun millis(): Long = utc.millis()
}
