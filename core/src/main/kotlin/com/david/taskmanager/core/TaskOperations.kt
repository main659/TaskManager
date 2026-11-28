package com.david.taskmanager.core

import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.importTitle
import java.time.Clock
import java.time.LocalDate

enum class MoveDirection { UP, DOWN }

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

    /**
     * Creates an OPEN task at the top of the list.
     *
     * @throws DependencyException if [dependencies] is invalid.
     * @throws IllegalArgumentException if [id] already exists or [title] is blank.
     */
    fun add(
        tasks: Map<TaskId, Task>,
        id: TaskId,
        title: String,
        notes: String? = null,
        dueDate: LocalDate? = null,
        availableFrom: LocalDate? = null,
        dependencies: List<Dependency> = emptyList(),
    ): Map<TaskId, Task> {
        require(id !in tasks) { "Task $id already exists" }
        val now = clock.instant()
        val task = Task(
            id = id,
            title = title,
            notes = notes,
            dueDate = dueDate,
            availableFrom = availableFrom,
            dependencies = dependencies,
            position = (tasks.values.minOfOrNull { it.position } ?: 1L) - 1,
            createdAt = now,
            updatedAt = now,
        )
        val result = tasks + (id to task)
        DependencyGraph(result).validate(id, dependencies)
        return result
    }

    /**
     * Creates OPEN tasks from Google tasks, on top of the list in the given order (first = top). They have no
     * dependencies or availability date, so they are available straight away.
     *
     * @throws IllegalArgumentException if an id already exists.
     */
    fun addImported(tasks: Map<TaskId, Task>, imported: List<Pair<TaskId, RemoteTask>>): Map<TaskId, Task> {
        if (imported.isEmpty()) return tasks
        val now = clock.instant()
        val first = (tasks.values.minOfOrNull { it.position } ?: 0L) - imported.size
        val created = imported.mapIndexed { index, (id, remote) ->
            require(id !in tasks) { "Task $id already exists" }
            id to Task(
                id = id,
                title = importTitle(remote),
                notes = remote.notes?.takeIf { it.isNotBlank() },
                dueDate = remote.dueDate,
                position = first + index,
                createdAt = now,
                updatedAt = now,
            )
        }
        return tasks + created
    }

    /**
     * Edits the plain fields of a task. Changing [availableFrom] can lock or unlock it.
     *
     * @throws IllegalArgumentException if [title] is blank.
     */
    fun updateDetails(
        tasks: Map<TaskId, Task>,
        id: TaskId,
        title: String,
        notes: String?,
        dueDate: LocalDate?,
        availableFrom: LocalDate?,
    ): Map<TaskId, Task> {
        val task = tasks[id] ?: return tasks
        val edited = task.copy(title = title, notes = notes, dueDate = dueDate, availableFrom = availableFrom)
        if (edited == task) return tasks
        return transition(tasks) { it + (id to edited.copy(updatedAt = clock.instant())) }
    }

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
     * Manual reorder: swaps the positions of [id] and its neighbour in [orderedIds] (the list as the user
     * sees it, top first). Moving past either end, or a task not in the list, is a no-op.
     */
    fun move(
        tasks: Map<TaskId, Task>,
        id: TaskId,
        orderedIds: List<TaskId>,
        direction: MoveDirection,
    ): Map<TaskId, Task> {
        val index = orderedIds.indexOf(id)
        if (index < 0) return tasks
        val neighbourId = orderedIds.getOrNull(if (direction == MoveDirection.UP) index - 1 else index + 1)
            ?: return tasks
        val task = tasks[id] ?: return tasks
        val neighbour = tasks[neighbourId] ?: return tasks
        return tasks +
            (id to task.copy(position = neighbour.position)) +
            (neighbourId to neighbour.copy(position = task.position))
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
