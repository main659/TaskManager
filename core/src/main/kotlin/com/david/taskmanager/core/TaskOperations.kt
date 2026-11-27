package com.david.taskmanager.core

import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import java.time.Clock
import java.time.LocalDate

/**
 * Pure state transitions on the whole task set (SPEC §3 Rules). Each operation takes the current tasks and
 * returns the new tasks; the caller persists the difference in one write.
 *
 * Every operation also moves tasks that become available (LOCKED → AVAILABLE, or DONE → AVAILABLE on undo) to
 * the top of the list by giving them positions below all others. Only the task an operation targets gets a new
 * `updatedAt`; side effects (moving to the top, converting a delay into `availableFrom`, removing a deleted
 * dependency) do not change it.
 *
 * Operations on a task that does not exist, or that is already in the requested state, return the input
 * unchanged, so that applying the same change twice (e.g. from sync) is harmless.
 */
class TaskOperations(private val clock: Clock) {

    private val evaluator = AvailabilityEvaluator(clock)

    fun complete(tasks: Map<TaskId, Task>, id: TaskId): Map<TaskId, Task> {
        val task = tasks[id] ?: return tasks
        if (task.status == TaskStatus.DONE) return tasks
        val now = clock.instant()
        return transition(tasks) {
            it + (id to task.copy(status = TaskStatus.DONE, completedAt = now, updatedAt = now))
        }
    }

    fun uncomplete(tasks: Map<TaskId, Task>, id: TaskId): Map<TaskId, Task> {
        val task = tasks[id] ?: return tasks
        if (task.status == TaskStatus.OPEN) return tasks
        return transition(tasks) {
            it + (id to task.copy(status = TaskStatus.OPEN, completedAt = null, updatedAt = clock.instant()))
        }
    }

    /**
     * Hard delete. The task is removed from its dependents' dependency lists. If it was DONE, a dependent that
     * is still inside that dependency's delay window keeps its unlock date: the pending delay is converted into
     * `availableFrom = max(existing, completedDate + delayDays)`.
     */
    fun delete(tasks: Map<TaskId, Task>, id: TaskId): Map<TaskId, Task> {
        val deleted = tasks[id] ?: return tasks
        val today = evaluator.today()
        val completedDate = deleted.completedAt?.let(evaluator::localDate)
        return transition(tasks) { current ->
            (current - id).mapValues { (_, task) ->
                val dependency = task.dependencies.firstOrNull { it.taskId == id } ?: return@mapValues task
                var availableFrom = task.availableFrom
                if (completedDate != null && task.status == TaskStatus.OPEN) {
                    val unlock = completedDate.plusDays(dependency.delayDays.toLong())
                    if (unlock > today && (availableFrom == null || unlock > availableFrom)) availableFrom = unlock
                }
                task.copy(dependencies = task.dependencies - dependency, availableFrom = availableFrom)
            }
        }
    }

    /**
     * Replaces the dependencies of [id].
     *
     * @throws DependencyException if the new list is invalid (self-dependency, cycle, unknown or duplicate task).
     * @throws IllegalArgumentException if [id] is not in [tasks].
     */
    fun setDependencies(tasks: Map<TaskId, Task>, id: TaskId, dependencies: List<Dependency>): Map<TaskId, Task> {
        val task = requireNotNull(tasks[id]) { "Unknown task $id" }
        DependencyGraph(tasks).validate(id, dependencies)
        return transition(tasks) {
            it + (id to task.copy(dependencies = dependencies, updatedAt = clock.instant()))
        }
    }

    /**
     * Midnight refresh: moves to the top every task that was locked on [previousDate] (the last date
     * availability was evaluated) and is available today. Nothing else changes.
     */
    fun refreshForDate(tasks: Map<TaskId, Task>, previousDate: LocalDate): Map<TaskId, Task> =
        promoteNewlyAvailable(before = tasks, beforeDate = previousDate, after = tasks)

    private inline fun transition(
        tasks: Map<TaskId, Task>,
        change: (Map<TaskId, Task>) -> Map<TaskId, Task>,
    ): Map<TaskId, Task> {
        val today = evaluator.today()
        return promoteNewlyAvailable(before = tasks, beforeDate = today, after = change(tasks))
    }

    /**
     * Tasks present in both maps that were not AVAILABLE (LOCKED or DONE) in [before] (evaluated on
     * [beforeDate]) and are AVAILABLE in [after] (evaluated today) get positions above every other task,
     * keeping their relative order.
     */
    private fun promoteNewlyAvailable(
        before: Map<TaskId, Task>,
        beforeDate: LocalDate,
        after: Map<TaskId, Task>,
    ): Map<TaskId, Task> {
        val today = evaluator.today()
        val unlocked = after.values
            .filter { task ->
                val previous = before[task.id] ?: return@filter false
                !evaluator.isAvailable(previous, before, beforeDate) &&
                    evaluator.isAvailable(task, after, today)
            }
            .sortedBy { it.position }
        if (unlocked.isEmpty()) return after

        val unlockedIds = unlocked.mapTo(mutableSetOf()) { it.id }
        val top = after.values.filter { it.id !in unlockedIds }.minOfOrNull { it.position } ?: 0L
        val firstPosition = top - unlocked.size
        return after + unlocked.mapIndexed { index, task ->
            task.id to task.copy(position = firstPosition + index)
        }
    }
}
