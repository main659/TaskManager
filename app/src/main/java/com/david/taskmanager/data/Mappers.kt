package com.david.taskmanager.data

import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Instants are stored with millisecond precision; anything finer is truncated.

fun TaskWithDependencies.toModel(): Task = Task(
    id = TaskId(UUID.fromString(task.id)),
    title = task.title,
    notes = task.notes,
    status = TaskStatus.valueOf(task.status),
    dueDate = task.dueDate?.let(LocalDate::ofEpochDay),
    availableFrom = task.availableFrom?.let(LocalDate::ofEpochDay),
    dependencies = dependencies.sortedBy { it.sortOrder }.map {
        Dependency(TaskId(UUID.fromString(it.dependsOnId)), it.delayDays)
    },
    position = task.position,
    createdAt = Instant.ofEpochMilli(task.createdAt),
    updatedAt = Instant.ofEpochMilli(task.updatedAt),
    completedAt = task.completedAt?.let(Instant::ofEpochMilli),
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id.toString(),
    title = title,
    notes = notes,
    status = status.name,
    dueDate = dueDate?.toEpochDay(),
    availableFrom = availableFrom?.toEpochDay(),
    position = position,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    completedAt = completedAt?.toEpochMilli(),
)

fun Task.toDependencyEntities(): List<DependencyEntity> = dependencies.mapIndexed { index, dependency ->
    DependencyEntity(
        taskId = id.toString(),
        dependsOnId = dependency.taskId.toString(),
        delayDays = dependency.delayDays,
        sortOrder = index,
    )
}
