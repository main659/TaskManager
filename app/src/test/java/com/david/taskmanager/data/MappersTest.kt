package com.david.taskmanager.data

import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MappersTest {

    private fun Task.roundTrip(): Task =
        TaskWithDependencies(toEntity(), toDependencyEntities().reversed()).toModel()

    @Test
    fun `a full task survives the round trip, keeping dependency order`() {
        val task = Task(
            id = TaskId.random(),
            title = "Title",
            notes = "Notes",
            status = TaskStatus.DONE,
            dueDate = LocalDate.of(2026, 12, 24),
            availableFrom = LocalDate.of(2026, 12, 2),
            dependencies = listOf(Dependency(TaskId.random(), 3), Dependency(TaskId.random()), Dependency(TaskId.random(), 1)),
            position = -42,
            createdAt = Instant.parse("2026-11-01T08:00:00.123Z"),
            updatedAt = Instant.parse("2026-11-02T08:00:00.456Z"),
            completedAt = Instant.parse("2026-11-03T08:00:00.789Z"),
        )
        assertEquals(task, task.roundTrip())
    }

    @Test
    fun `a minimal task survives the round trip`() {
        val task = Task(
            id = TaskId.random(),
            title = "Title",
            position = 0,
            createdAt = Instant.parse("2026-11-01T08:00:00Z"),
            updatedAt = Instant.parse("2026-11-01T08:00:00Z"),
        )
        assertEquals(task, task.roundTrip())
    }

    @Test
    fun `instants are stored with millisecond precision`() {
        val task = Task(
            id = TaskId.random(),
            title = "Title",
            position = 0,
            createdAt = Instant.parse("2026-11-01T08:00:00.123456789Z"),
            updatedAt = Instant.parse("2026-11-01T08:00:00.123456789Z"),
        )
        assertEquals(Instant.parse("2026-11-01T08:00:00.123Z"), task.roundTrip().createdAt)
    }
}
