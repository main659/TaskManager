package com.david.taskmanager.data

import androidx.room.withTransaction
import com.david.taskmanager.core.AvailabilityEvaluator
import com.david.taskmanager.core.MoveDirection
import com.david.taskmanager.core.TaskLists
import com.david.taskmanager.core.TaskOperations
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate

/**
 * The single entry point for reading and changing tasks. Every write loads all tasks, applies one
 * [TaskOperations] call and saves the difference, all in one transaction: the rules live in `core`, and an
 * exception (e.g. a rejected cycle) rolls the whole write back.
 */
class TaskRepository(private val db: TaskDatabase, private val clock: Clock) {

    private val dao = db.taskDao()
    private val ops = TaskOperations(clock)
    private val evaluator = AvailabilityEvaluator(clock)

    /** All tasks, in ascending `position` order. */
    val tasks: Flow<List<Task>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun addTask(
        title: String,
        notes: String? = null,
        dueDate: LocalDate? = null,
        availableFrom: LocalDate? = null,
        dependencies: List<Dependency> = emptyList(),
    ): TaskId {
        val id = TaskId.random()
        write { ops.add(it, id, title, notes, dueDate, availableFrom, dependencies) }
        return id
    }

    suspend fun updateDetails(id: TaskId, title: String, notes: String?, dueDate: LocalDate?, availableFrom: LocalDate?) =
        write { ops.updateDetails(it, id, title, notes, dueDate, availableFrom) }

    /** Edits only title and notes; dates are read inside the transaction so concurrent edits don't clash. */
    suspend fun setTitleAndNotes(id: TaskId, title: String, notes: String?) = write { tasks ->
        val task = tasks[id] ?: return@write tasks
        ops.updateDetails(tasks, id, title, notes, task.dueDate, task.availableFrom)
    }

    suspend fun setDueDate(id: TaskId, dueDate: LocalDate?) = write { tasks ->
        val task = tasks[id] ?: return@write tasks
        ops.updateDetails(tasks, id, task.title, task.notes, dueDate, task.availableFrom)
    }

    suspend fun setAvailableFrom(id: TaskId, availableFrom: LocalDate?) = write { tasks ->
        val task = tasks[id] ?: return@write tasks
        ops.updateDetails(tasks, id, task.title, task.notes, task.dueDate, availableFrom)
    }

    /** Move up / down within the Available list as it is at the time of the write. */
    suspend fun moveAvailable(id: TaskId, direction: MoveDirection) = write { tasks ->
        val order = TaskLists.from(tasks.values, evaluator).available.map { it.id }
        ops.move(tasks, id, order, direction)
    }

    /**
     * Changes the dependency list based on its current value, read inside the transaction.
     *
     * @throws com.david.taskmanager.core.DependencyException if the result is invalid (nothing is written).
     */
    suspend fun updateDependencies(id: TaskId, change: (List<Dependency>) -> List<Dependency>) = write { tasks ->
        val task = tasks[id] ?: return@write tasks
        ops.setDependencies(tasks, id, change(task.dependencies))
    }

    suspend fun setDependencies(id: TaskId, dependencies: List<Dependency>) =
        write { ops.setDependencies(it, id, dependencies) }

    suspend fun complete(id: TaskId) = write { ops.complete(it, id) }

    suspend fun uncomplete(id: TaskId) = write { ops.uncomplete(it, id) }

    suspend fun delete(id: TaskId) = write { ops.delete(it, id) }

    /**
     * Midnight refresh: moves tasks that unlocked since the last evaluated date to the top, then records today.
     * The first run only records today. If the stored date is in the future (clock or time zone moved back),
     * nothing is promoted.
     */
    suspend fun refreshForToday() {
        db.withTransaction {
            val today = LocalDate.now(clock)
            val last = dao.getState(KEY_LAST_EVALUATED_DATE)?.let(LocalDate::parse)
            if (last != null && last < today) {
                val before = load()
                save(before, ops.refreshForDate(before, last))
            }
            dao.putState(AppStateEntity(KEY_LAST_EVALUATED_DATE, today.toString()))
        }
    }

    private suspend fun write(operation: (Map<TaskId, Task>) -> Map<TaskId, Task>) {
        db.withTransaction {
            val before = load()
            save(before, operation(before))
        }
    }

    private suspend fun load(): Map<TaskId, Task> = dao.getAll().map { it.toModel() }.associateBy { it.id }

    private suspend fun save(before: Map<TaskId, Task>, after: Map<TaskId, Task>) {
        if (after === before) return
        val changed = after.values.filter { before[it.id] != it }
        val removed = (before.keys - after.keys).map { it.toString() }
        if (changed.isNotEmpty()) {
            dao.upsertTasks(changed.map { it.toEntity() })
            dao.deleteDependenciesOf(changed.map { it.id.toString() })
            dao.insertDependencies(changed.flatMap { it.toDependencyEntities() })
        }
        if (removed.isNotEmpty()) dao.deleteTasks(removed)
    }

    companion object {
        const val KEY_LAST_EVALUATED_DATE = "last_evaluated_date"
    }
}
