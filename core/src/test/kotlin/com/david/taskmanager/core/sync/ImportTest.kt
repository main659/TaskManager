package com.david.taskmanager.core.sync

import com.david.taskmanager.core.DEFAULT_CLOCK
import com.david.taskmanager.core.TODAY
import com.david.taskmanager.core.TaskOperations
import com.david.taskmanager.core.doneTask
import com.david.taskmanager.core.instantAt
import com.david.taskmanager.core.openTask
import com.david.taskmanager.core.tasksOf
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class ImportTest {

    private val updated: Instant = Instant.parse("2026-11-20T08:00:00Z")

    private fun remote(id: String, title: String, notes: String? = null, completed: Boolean = false) =
        RemoteTask(id, title, notes, dueDate = null, updated = updated, completed = completed)

    @Test
    fun `unknown remote tasks are created in remote order`() {
        val plan = planImport(emptyList(), emptySet(), emptySet(), listOf(remote("g1", "A"), remote("g2", "B")))

        assertEquals(listOf("g1", "g2"), plan.creates.map { it.id })
        assertEquals(emptyList<ImportPlan.Match>(), plan.matches)
    }

    @Test
    fun `already linked remote tasks are skipped, which makes the import repeatable`() {
        val plan = planImport(emptyList(), emptySet(), setOf("g1"), listOf(remote("g1", "A"), remote("g2", "B")))

        assertEquals(listOf("g2"), plan.creates.map { it.id })
    }

    @Test
    fun `a remote task linked to a task deleted locally is not imported again`() {
        // The link outlives the local task so that a later push can delete the Google copy.
        val plan = planImport(emptyList(), setOf(TaskId.random()), setOf("g1"), listOf(remote("g1", "A")))

        assertEquals(emptyList<RemoteTask>(), plan.creates)
        assertEquals(emptyList<ImportPlan.Match>(), plan.matches)
    }

    @Test
    fun `completed remote tasks are ignored`() {
        val plan = planImport(emptyList(), emptySet(), emptySet(), listOf(remote("g1", "A", completed = true)))

        assertEquals(emptyList<RemoteTask>(), plan.creates)
    }

    @Test
    fun `a remote task matching an open local title is linked instead of created`() {
        val local = openTask("Buy milk")
        val plan = planImport(listOf(local), emptySet(), emptySet(), listOf(remote("g1", "  buy MILK ")))

        assertEquals(emptyList<RemoteTask>(), plan.creates)
        assertEquals(listOf(ImportPlan.Match(local.id, remote("g1", "  buy MILK "))), plan.matches)
    }

    @Test
    fun `locked local tasks can be matched too`() {
        val locked = openTask("Call dentist", availableFrom = TODAY.plusDays(5))
        val plan = planImport(listOf(locked), emptySet(), emptySet(), listOf(remote("g1", "Call dentist")))

        assertEquals(locked.id, plan.matches.single().taskId)
    }

    @Test
    fun `done or already linked local tasks are not matched`() {
        val done = doneTask("A", completedAt = instantAt(LocalDateTime.of(2026, 11, 30, 9, 0)))
        val linked = openTask("B")
        val plan = planImport(
            listOf(done, linked),
            linkedTaskIds = setOf(linked.id),
            linkedRemoteIds = setOf("other"),
            remote = listOf(remote("g1", "A"), remote("g2", "B")),
        )

        assertEquals(listOf("g1", "g2"), plan.creates.map { it.id })
        assertEquals(emptyList<ImportPlan.Match>(), plan.matches)
    }

    @Test
    fun `each local task is matched at most once, the topmost first`() {
        val top = openTask("Same", position = 1)
        val lower = openTask("Same", position = 5)
        val plan = planImport(
            listOf(lower, top),
            emptySet(),
            emptySet(),
            listOf(remote("g1", "Same"), remote("g2", "Same"), remote("g3", "Same")),
        )

        assertEquals(listOf(top.id to "g1", lower.id to "g2"), plan.matches.map { it.taskId to it.remote.id })
        assertEquals(listOf("g3"), plan.creates.map { it.id })
    }

    @Test
    fun `blank remote titles are never matched`() {
        val plan = planImport(listOf(openTask("Untitled")), emptySet(), emptySet(), listOf(remote("g1", "  ")))

        assertEquals(listOf("g1"), plan.creates.map { it.id })
    }

    @Test
    fun `a remote task listed twice is planned once`() {
        val plan = planImport(emptyList(), emptySet(), emptySet(), listOf(remote("g1", "A"), remote("g1", "A")))

        assertEquals(listOf("g1"), plan.creates.map { it.id })
    }

    @Test
    fun `imported tasks go on top in remote order with title, notes and due date`() {
        val existing = openTask("Existing", position = 3)
        val due = TODAY.plusDays(2)
        val first = TaskId.random()
        val second = TaskId.random()
        val tasks = TaskOperations(DEFAULT_CLOCK).addImported(
            tasksOf(existing),
            listOf(
                first to RemoteTask("g1", " First ", "notes", due, updated),
                second to RemoteTask("g2", "", "  ", null, updated),
            ),
        )

        assertEquals(listOf(first, second, existing.id), tasks.values.sortedBy { it.position }.map { it.id })
        val a = tasks.getValue(first)
        assertEquals("First", a.title)
        assertEquals("notes", a.notes)
        assertEquals(due, a.dueDate)
        assertEquals(TaskStatus.OPEN, a.status)
        assertEquals(DEFAULT_CLOCK.instant(), a.createdAt)
        val b = tasks.getValue(second)
        assertEquals(UNTITLED, b.title)
        assertEquals(null, b.notes)
    }

    @Test
    fun `importing nothing returns the same tasks`() {
        val tasks = tasksOf(openTask("A"))

        assertSame(tasks, TaskOperations(DEFAULT_CLOCK).addImported(tasks, emptyList()))
    }

    @Test
    fun `importing an existing id is rejected`() {
        val existing = openTask("A")

        assertThrows(IllegalArgumentException::class.java) {
            TaskOperations(DEFAULT_CLOCK).addImported(tasksOf(existing), listOf(existing.id to remote("g1", "A")))
        }
    }
}
