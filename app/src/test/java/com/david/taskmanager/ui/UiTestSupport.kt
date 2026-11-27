package com.david.taskmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate

private val CREATED: Instant = Instant.parse("2026-11-01T08:00:00Z")

fun uiTask(
    title: String,
    position: Long = 0,
    dueDate: LocalDate? = null,
    availableFrom: LocalDate? = null,
    dependencies: List<Dependency> = emptyList(),
    completedAt: Instant? = null,
): Task = Task(
    id = TaskId.random(),
    title = title,
    status = if (completedAt != null) TaskStatus.DONE else TaskStatus.OPEN,
    dueDate = dueDate,
    availableFrom = availableFrom,
    dependencies = dependencies,
    position = position,
    createdAt = CREATED,
    updatedAt = CREATED,
    completedAt = completedAt,
)

/** Creates a ViewModel in [store] so that `store.clear()` runs its `onCleared()`, as leaving a screen does. */
inline fun <reified VM : ViewModel> ViewModelStore.create(noinline create: () -> VM): VM =
    ViewModelProvider.create(this, viewModelFactory { initializer { create() } })[VM::class]
