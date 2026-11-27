package com.david.taskmanager.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.david.taskmanager.core.AvailabilityEvaluator
import com.david.taskmanager.core.DependencyException
import com.david.taskmanager.core.DependencyGraph
import com.david.taskmanager.core.TaskState
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class DependencyItem(val task: Task, val delayDays: Int)

data class TaskDetail(
    val task: Task,
    val state: TaskState,
    val today: LocalDate,
    val dependencies: List<DependencyItem>,
    /** Tasks that can be added as a dependency without creating a cycle. */
    val candidates: List<Task>,
    /** Tasks that wait on this one. */
    val dependents: List<Task>,
)

sealed interface DetailState {
    data object Loading : DetailState
    data object NotFound : DetailState
    data class Loaded(val detail: TaskDetail) : DetailState
}

data class TextFields(val title: String, val notes: String)

/**
 * Detail / edit screen. Dates and dependencies are saved as soon as they change. Title and notes live here
 * while being typed and are saved after a short pause and when the screen closes; a blank title is never
 * saved, so the previous title is kept.
 */
class TaskDetailViewModel(
    private val taskId: TaskId,
    private val repository: TaskRepository,
    clock: Clock,
    today: StateFlow<LocalDate>,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val evaluator = AvailabilityEvaluator(clock)

    val state: StateFlow<DetailState> = combine(repository.tasks, today) { tasks, day -> build(tasks, day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    private val _text = MutableStateFlow<TextFields?>(null)

    /** Null until the task has been loaded. */
    val text: StateFlow<TextFields?> = _text.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** The last rejected dependency change, if any. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pendingSave: Job? = null

    init {
        viewModelScope.launch {
            val task = repository.tasks.first().firstOrNull { it.id == taskId } ?: return@launch
            _text.compareAndSet(null, TextFields(task.title, task.notes.orEmpty()))
        }
    }

    fun onTitleChange(title: String) = editText { it.copy(title = title) }

    fun onNotesChange(notes: String) = editText { it.copy(notes = notes) }

    /** Saves title and notes now. Returns null if there is nothing to save (not loaded, or blank title). */
    fun saveText(): Job? {
        pendingSave?.cancel()
        val fields = _text.value ?: return null
        val title = fields.title.trim()
        if (title.isEmpty()) return null
        val notes = fields.notes.trim().ifEmpty { null }
        return appScope.launch { repository.setTitleAndNotes(taskId, title, notes) }
    }

    fun setDueDate(date: LocalDate?): Job = appScope.launch { repository.setDueDate(taskId, date) }

    fun setAvailableFrom(date: LocalDate?): Job = appScope.launch { repository.setAvailableFrom(taskId, date) }

    fun addDependency(id: TaskId): Job = editDependencies { it + Dependency(id) }

    fun removeDependency(id: TaskId): Job = editDependencies { deps -> deps.filterNot { it.taskId == id } }

    fun setDelay(id: TaskId, delayDays: Int): Job = editDependencies { deps ->
        deps.map { if (it.taskId == id) it.copy(delayDays = delayDays.coerceIn(0, MAX_DELAY_DAYS)) else it }
    }

    fun delete(): Job {
        pendingSave?.cancel()
        return appScope.launch { repository.delete(taskId) }
    }

    override fun onCleared() {
        saveText()
    }

    private fun editText(change: (TextFields) -> TextFields) {
        _text.update { current -> current?.let(change) }
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            saveText()
        }
    }

    private fun editDependencies(change: (List<Dependency>) -> List<Dependency>): Job = appScope.launch {
        try {
            repository.updateDependencies(taskId, change)
            _error.value = null
        } catch (e: DependencyException) {
            _error.value = e.message
        }
    }

    private fun build(tasks: List<Task>, today: LocalDate): DetailState {
        val byId = tasks.associateBy { it.id }
        val task = byId[taskId] ?: return DetailState.NotFound
        val graph = DependencyGraph(byId)
        val dependencyIds = task.dependencies.map { it.taskId }.toSet()
        val candidates = tasks
            .filter { it.id != taskId && it.id !in dependencyIds }
            .filter { runCatching { graph.validate(taskId, task.dependencies + Dependency(it.id)) }.isSuccess }
            .sortedBy { it.title.lowercase() }
        return DetailState.Loaded(
            TaskDetail(
                task = task,
                state = evaluator.evaluate(task, byId, today),
                today = today,
                dependencies = task.dependencies.mapNotNull { dep -> byId[dep.taskId]?.let { DependencyItem(it, dep.delayDays) } },
                candidates = candidates,
                dependents = graph.dependentsOf(taskId).mapNotNull(byId::get).sortedBy { it.title.lowercase() },
            ),
        )
    }

    companion object {
        const val SAVE_DELAY_MS = 500L
        const val MAX_DELAY_DAYS = 365
    }
}
