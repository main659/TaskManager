package com.david.taskmanager.core

import com.david.taskmanager.core.model.Task
import java.time.LocalDate

data class LockedTask(val task: Task, val reasons: List<LockReason>, val unlockDate: LocalDate?)

/**
 * The three lists the UI shows.
 * - [available]: by position (top = lowest).
 * - [locked]: by unlock date (soonest first), tasks with no computable date last, then by position.
 * - [done]: most recently completed first.
 */
data class TaskLists(
    val available: List<Task>,
    val locked: List<LockedTask>,
    val done: List<Task>,
) {
    companion object {
        fun from(
            tasks: Collection<Task>,
            evaluator: AvailabilityEvaluator,
            today: LocalDate = evaluator.today(),
        ): TaskLists {
            val byId = tasks.associateBy { it.id }
            val available = mutableListOf<Task>()
            val locked = mutableListOf<LockedTask>()
            val done = mutableListOf<Task>()
            for (task in tasks) {
                when (val state = evaluator.evaluate(task, byId, today)) {
                    TaskState.Available -> available += task
                    is TaskState.Locked -> locked += LockedTask(task, state.reasons, state.unlockDate)
                    TaskState.Done -> done += task
                }
            }
            return TaskLists(
                available = available.sortedBy { it.position },
                locked = locked.sortedWith(
                    compareBy<LockedTask, LocalDate?>(nullsLast()) { it.unlockDate }.thenBy { it.task.position },
                ),
                done = done.sortedByDescending { it.completedAt },
            )
        }
    }
}
