package com.david.taskmanager.core

import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val PARIS: ZoneId = ZoneId.of("Europe/Paris")

/** Today in tests: 1 Dec 2026, 10:00 Paris time. */
val TODAY: LocalDate = LocalDate.of(2026, 12, 1)

fun clockAt(dateTime: LocalDateTime, zone: ZoneId = PARIS): Clock =
    Clock.fixed(dateTime.atZone(zone).toInstant(), zone)

fun clockOn(date: LocalDate, zone: ZoneId = PARIS): Clock = clockAt(date.atTime(10, 0), zone)

val DEFAULT_CLOCK: Clock = clockOn(TODAY)

fun instantAt(dateTime: LocalDateTime, zone: ZoneId = PARIS): Instant = dateTime.atZone(zone).toInstant()

private val CREATED: Instant = instantAt(LocalDateTime.of(2026, 11, 1, 9, 0))

fun openTask(
    title: String,
    position: Long = 0,
    availableFrom: LocalDate? = null,
    dependencies: List<Dependency> = emptyList(),
): Task = Task(
    id = TaskId.random(),
    title = title,
    status = TaskStatus.OPEN,
    availableFrom = availableFrom,
    dependencies = dependencies,
    position = position,
    createdAt = CREATED,
    updatedAt = CREATED,
)

fun doneTask(title: String, completedAt: Instant, position: Long = 0): Task =
    openTask(title, position).copy(status = TaskStatus.DONE, completedAt = completedAt)

fun Task.dependsOn(other: Task, delayDays: Int = 0): Task =
    copy(dependencies = dependencies + Dependency(other.id, delayDays))

fun tasksOf(vararg tasks: Task): Map<TaskId, Task> = tasks.associateBy { it.id }
