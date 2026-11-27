package com.david.taskmanager.core

import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

sealed interface TaskState {
    data object Available : TaskState

    /**
     * Open but not actionable yet. [reasons] is never empty. [unlockDate] is the date the task will become
     * available, or null when that depends on an open task being completed.
     */
    data class Locked(val reasons: List<LockReason>, val unlockDate: LocalDate?) : TaskState

    data object Done : TaskState
}

sealed interface LockReason {
    /** "Available from <date>". */
    data class NotBefore(val date: LocalDate) : LockReason

    /** "Waiting on: <title>" (and, if [delayDays] > 0, "+N days after it is done"). */
    data class WaitingOn(val taskId: TaskId, val title: String, val delayDays: Int) : LockReason

    /** "Available N days after <title>": the dependency is done, its delay runs until [unlockDate]. */
    data class DelayAfter(val taskId: TaskId, val title: String, val delayDays: Int, val unlockDate: LocalDate) :
        LockReason
}

/**
 * Derives AVAILABLE / LOCKED / DONE. "Today" and the local date of a completion both come from [clock]
 * (its instant and its zone).
 */
class AvailabilityEvaluator(private val clock: Clock) {

    fun today(): LocalDate = LocalDate.now(clock)

    /** The device-local date on which [instant] happened. */
    fun localDate(instant: Instant): LocalDate = instant.atZone(clock.zone).toLocalDate()

    /** @throws IllegalStateException if a dependency is missing from [tasksById]. */
    fun evaluate(task: Task, tasksById: Map<TaskId, Task>, today: LocalDate = today()): TaskState {
        if (task.status == TaskStatus.DONE) return TaskState.Done

        val reasons = buildList {
            task.availableFrom?.let { if (it > today) add(LockReason.NotBefore(it)) }
            for (dependency in task.dependencies) {
                val other = tasksById[dependency.taskId]
                    ?: error("Task ${task.id} depends on missing task ${dependency.taskId}")
                val completedAt = other.completedAt
                if (other.status != TaskStatus.DONE || completedAt == null) {
                    add(LockReason.WaitingOn(other.id, other.title, dependency.delayDays))
                } else {
                    val unlock = localDate(completedAt).plusDays(dependency.delayDays.toLong())
                    if (unlock > today) add(LockReason.DelayAfter(other.id, other.title, dependency.delayDays, unlock))
                }
            }
        }
        if (reasons.isEmpty()) return TaskState.Available

        val unlockDate = if (reasons.any { it is LockReason.WaitingOn }) {
            null
        } else {
            reasons.maxOf {
                when (it) {
                    is LockReason.NotBefore -> it.date
                    is LockReason.DelayAfter -> it.unlockDate
                    is LockReason.WaitingOn -> error("unreachable")
                }
            }
        }
        return TaskState.Locked(reasons, unlockDate)
    }

    fun isAvailable(task: Task, tasksById: Map<TaskId, Task>, today: LocalDate = today()): Boolean =
        evaluate(task, tasksById, today) == TaskState.Available
}
