package com.david.taskmanager.core.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class TaskId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): TaskId = TaskId(UUID.randomUUID())
    }
}

enum class TaskStatus { OPEN, DONE }

/** "This task waits on [taskId]", optionally until [delayDays] days after it was completed. */
data class Dependency(
    val taskId: TaskId,
    val delayDays: Int = 0,
) {
    init {
        require(delayDays >= 0) { "delayDays must be >= 0, was $delayDays" }
    }
}

/**
 * A task as stored. AVAILABLE / LOCKED are derived by [com.david.taskmanager.core.AvailabilityEvaluator]
 * and never stored.
 *
 * Lists are ordered by ascending [position]; the top of a list has the lowest position.
 */
data class Task(
    val id: TaskId,
    val title: String,
    val notes: String? = null,
    val status: TaskStatus = TaskStatus.OPEN,
    val dueDate: LocalDate? = null,
    val availableFrom: LocalDate? = null,
    val dependencies: List<Dependency> = emptyList(),
    val position: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require((status == TaskStatus.DONE) == (completedAt != null)) {
            "completedAt must be set if and only if status is DONE (status=$status, completedAt=$completedAt)"
        }
    }
}
