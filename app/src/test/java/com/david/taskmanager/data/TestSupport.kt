package com.david.taskmanager.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val PARIS: ZoneId = ZoneId.of("Europe/Paris")

/** Today in tests: 1 Dec 2026, 10:00 Paris time. */
val TODAY: LocalDate = LocalDate.of(2026, 12, 1)

fun clockAt(dateTime: LocalDateTime): Clock = Clock.fixed(dateTime.atZone(PARIS).toInstant(), PARIS)

fun clockOn(date: LocalDate): Clock = clockAt(date.atTime(10, 0))

fun inMemoryDatabase(): TaskDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun taskEntity(
    id: String,
    title: String = "Task $id",
    position: Long = 0,
    notes: String? = null,
    status: String = "OPEN",
    completedAt: Long? = null,
): TaskEntity = TaskEntity(
    id = id,
    title = title,
    notes = notes,
    status = status,
    dueDate = null,
    availableFrom = null,
    position = position,
    createdAt = 1_000L,
    updatedAt = 2_000L,
    completedAt = completedAt,
)
