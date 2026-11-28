package com.david.taskmanager.data

import androidx.room.withTransaction
import com.david.taskmanager.core.AvailabilityEvaluator
import com.david.taskmanager.core.MoveDirection
import com.david.taskmanager.core.TaskLists
import com.david.taskmanager.core.TaskOperations
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.RemoteTaskList
import com.david.taskmanager.core.sync.planImport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** The Google Tasks list the app is connected to. Stored once the one-time import has succeeded. */
data class GoogleConnection(val listId: String, val listTitle: String)

/** Outcome of the one-time import: tasks created locally, and Google tasks linked to existing local tasks. */
data class ImportResult(val imported: Int, val matched: Int)

/**
 * The single entry point for reading and changing tasks. Every write loads all tasks, applies one
 * [TaskOperations] call and saves the difference, all in one transaction: the rules live in `core`, and an
 * exception (e.g. a rejected cycle) rolls the whole write back.
 */
class TaskRepository(
    private val db: TaskDatabase,
    private val clock: Clock,
    /** Called after a write has committed a change (not after no-ops), e.g. to update the home-screen widget. */
    private val onChange: suspend () -> Unit = {},
) {

    private val dao = db.taskDao()
    private val ops = TaskOperations(clock)
    private val evaluator = AvailabilityEvaluator(clock)

    /** All tasks, in ascending `position` order. */
    val tasks: Flow<List<Task>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** The connected Google Tasks list, or null when not connected. */
    val googleConnection: Flow<GoogleConnection?> = dao.observeStates(GOOGLE_KEYS)
        .map { rows ->
            val values = rows.associate { it.key to it.value }
            values[KEY_GOOGLE_LIST_ID]?.let { GoogleConnection(it, values[KEY_GOOGLE_LIST_TITLE].orEmpty()) }
        }
        .distinctUntilChanged()

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
        val changed = db.withTransaction {
            val today = LocalDate.now(clock)
            val last = dao.getState(KEY_LAST_EVALUATED_DATE)?.let(LocalDate::parse)
            var tasksChanged = false
            if (last != null && last < today) {
                val before = load()
                tasksChanged = save(before, ops.refreshForDate(before, last))
            }
            dao.putState(AppStateEntity(KEY_LAST_EVALUATED_DATE, today.toString()))
            // A new date can change what is shown (e.g. due dates) even when no task moved.
            tasksChanged || last != today
        }
        if (changed) onChange()
    }

    /**
     * One-time import of [remote] (the open tasks of [list], in Google's order), in one transaction: creates the
     * new tasks, links them and the matched ones (see [planImport]), and records the connection. Importing the
     * same list again creates nothing.
     *
     * @throws IllegalStateException if the app is already connected to a different list.
     */
    suspend fun importFromGoogle(list: RemoteTaskList, remote: List<RemoteTask>): ImportResult {
        var tasksChanged = false
        val result = db.withTransaction {
            val connected = dao.getState(KEY_GOOGLE_LIST_ID)
            check(connected == null || connected == list.id) { "Already connected to another Google Tasks list" }
            val before = load()
            val links = dao.getGoogleLinks()
            val plan = planImport(
                local = before.values,
                linkedTaskIds = links.mapTo(mutableSetOf()) { TaskId(UUID.fromString(it.taskId)) },
                linkedRemoteIds = links.mapTo(mutableSetOf()) { it.googleTaskId },
                remote = remote,
            )
            val created = plan.creates.map { TaskId.random() to it }
            tasksChanged = save(before, ops.addImported(before, created))
            val newLinks = created.map { (id, task) -> linkEntity(id, task) } +
                plan.matches.map { linkEntity(it.taskId, it.remote) }
            if (newLinks.isNotEmpty()) dao.insertGoogleLinks(newLinks)
            dao.putState(AppStateEntity(KEY_GOOGLE_LIST_ID, list.id))
            dao.putState(AppStateEntity(KEY_GOOGLE_LIST_TITLE, list.title))
            ImportResult(imported = created.size, matched = plan.matches.size)
        }
        if (tasksChanged) onChange()
        return result
    }

    /** Forgets the connected list and every link. Local tasks are kept; Google is not touched. */
    suspend fun disconnectGoogle() = db.withTransaction {
        dao.deleteAllGoogleLinks()
        dao.deleteStates(GOOGLE_KEYS)
    }

    private fun linkEntity(id: TaskId, remote: RemoteTask) =
        GoogleTaskLinkEntity(id.toString(), remote.id, remote.updated.toEpochMilli())

    private suspend fun write(operation: (Map<TaskId, Task>) -> Map<TaskId, Task>) {
        val changed = db.withTransaction {
            val before = load()
            save(before, operation(before))
        }
        if (changed) onChange()
    }

    private suspend fun load(): Map<TaskId, Task> = dao.getAll().map { it.toModel() }.associateBy { it.id }

    /** Persists the difference; returns whether anything changed. */
    private suspend fun save(before: Map<TaskId, Task>, after: Map<TaskId, Task>): Boolean {
        if (after === before) return false
        val changed = after.values.filter { before[it.id] != it }
        val removed = (before.keys - after.keys).map { it.toString() }
        if (changed.isNotEmpty()) {
            dao.upsertTasks(changed.map { it.toEntity() })
            dao.deleteDependenciesOf(changed.map { it.id.toString() })
            dao.insertDependencies(changed.flatMap { it.toDependencyEntities() })
        }
        if (removed.isNotEmpty()) dao.deleteTasks(removed)
        return changed.isNotEmpty() || removed.isNotEmpty()
    }

    companion object {
        const val KEY_LAST_EVALUATED_DATE = "last_evaluated_date"
        const val KEY_GOOGLE_LIST_ID = "google_list_id"
        const val KEY_GOOGLE_LIST_TITLE = "google_list_title"
        private val GOOGLE_KEYS = listOf(KEY_GOOGLE_LIST_ID, KEY_GOOGLE_LIST_TITLE)
    }
}
