package com.david.taskmanager.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.david.taskmanager.core.AvailabilityEvaluator
import com.david.taskmanager.core.MoveDirection
import com.david.taskmanager.core.TaskLists
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class ListsUiState(val lists: TaskLists, val today: LocalDate)

/**
 * State for the Available / Locked / Done tabs. Writes run in [appScope] and return their [Job] so callers
 * (and tests) can wait for them.
 */
class TaskListsViewModel(
    private val repository: TaskRepository,
    clock: Clock,
    today: StateFlow<LocalDate>,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val evaluator = AvailabilityEvaluator(clock)

    /** Null until the first load from the database. */
    val state: StateFlow<ListsUiState?> = combine(repository.tasks, today) { tasks, day ->
        ListsUiState(TaskLists.from(tasks, evaluator, day), day)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Adds a task with the trimmed [title]; blank titles are ignored (returns null). */
    fun add(title: String): Job? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        return appScope.launch { repository.addTask(trimmed) }
    }

    fun complete(id: TaskId): Job = appScope.launch { repository.complete(id) }

    fun uncomplete(id: TaskId): Job = appScope.launch { repository.uncomplete(id) }

    fun move(id: TaskId, direction: MoveDirection): Job = appScope.launch { repository.moveAvailable(id, direction) }
}
