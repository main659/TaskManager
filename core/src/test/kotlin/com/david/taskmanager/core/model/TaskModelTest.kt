package com.david.taskmanager.core.model

import com.david.taskmanager.core.doneTask
import com.david.taskmanager.core.instantAt
import com.david.taskmanager.core.openTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TaskModelTest {

    @Test(expected = IllegalArgumentException::class)
    fun `blank title is rejected`() {
        openTask("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative delay is rejected`() {
        Dependency(TaskId.random(), delayDays = -1)
    }

    @Test
    fun `delay defaults to zero`() {
        assertEquals(0, Dependency(TaskId.random()).delayDays)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `done task without completedAt is rejected`() {
        openTask("A").copy(status = TaskStatus.DONE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `open task with completedAt is rejected`() {
        doneTask("A", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0))).copy(status = TaskStatus.OPEN)
    }
}
