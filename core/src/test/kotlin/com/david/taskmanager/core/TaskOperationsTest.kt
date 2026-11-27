package com.david.taskmanager.core

import com.david.taskmanager.core.LockReason.WaitingOn
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TaskOperationsTest {

    private val now = DEFAULT_CLOCK.instant()
    private val ops = TaskOperations(DEFAULT_CLOCK)
    private val evaluator = AvailabilityEvaluator(DEFAULT_CLOCK)

    private fun Map<TaskId, Task>.stateOf(task: Task) = evaluator.evaluate(getValue(task.id), this)
    private fun Map<TaskId, Task>.positionOf(task: Task) = getValue(task.id).position

    // --- complete --------------------------------------------------------------------------

    @Test
    fun `complete marks the task done now`() {
        val a = openTask("A")
        val result = ops.complete(tasksOf(a), a.id).getValue(a.id)
        assertEquals(TaskStatus.DONE, result.status)
        assertEquals(now, result.completedAt)
        assertEquals(now, result.updatedAt)
        assertEquals(a.position, result.position)
    }

    @Test
    fun `completing a done task is a no-op`() {
        val a = doneTask("A", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0)))
        val tasks = tasksOf(a)
        assertSame(tasks, ops.complete(tasks, a.id))
    }

    @Test
    fun `completing an unknown task is a no-op`() {
        val tasks = tasksOf(openTask("A"))
        assertSame(tasks, ops.complete(tasks, TaskId.random()))
    }

    @Test
    fun `completing the last dependency unlocks the dependent and moves it to the top`() {
        val top = openTask("Top", position = 10)
        val dep = openTask("Dep", position = 20)
        val dependent = openTask("Dependent", position = 30).dependsOn(dep)

        val result = ops.complete(tasksOf(top, dep, dependent), dep.id)

        assertEquals(TaskState.Available, result.stateOf(dependent))
        assertTrue(result.positionOf(dependent) < result.positionOf(top))
        assertEquals(10L, result.positionOf(top))
        // Moving to the top is not a content edit: updatedAt is untouched.
        assertEquals(dependent.updatedAt, result.getValue(dependent.id).updatedAt)
    }

    @Test
    fun `dependent that becomes available is placed above locked and done tasks too`() {
        val locked = openTask("Locked", position = 1, availableFrom = TODAY.plusDays(5))
        val dep = openTask("Dep", position = 5)
        val dependent = openTask("Dependent", position = 9).dependsOn(dep)

        val result = ops.complete(tasksOf(locked, dep, dependent), dep.id)

        assertTrue(result.values.filter { it.id != dependent.id }.all { it.position > result.positionOf(dependent) })
    }

    @Test
    fun `several tasks unlocked at once go to the top keeping their relative order`() {
        val top = openTask("Top", position = 0)
        val dep = openTask("Dep", position = 1)
        val second = openTask("Second", position = 7).dependsOn(dep)
        val first = openTask("First", position = 3).dependsOn(dep)

        val result = ops.complete(tasksOf(top, dep, first, second), dep.id)

        assertTrue(result.positionOf(first) < result.positionOf(second))
        assertTrue(result.positionOf(second) < result.positionOf(top))
    }

    @Test
    fun `dependent with a pending delay is not unlocked or moved`() {
        val dep = openTask("Dep", position = 1)
        val dependent = openTask("Dependent", position = 2).dependsOn(dep, delayDays = 2)

        val result = ops.complete(tasksOf(dep, dependent), dep.id)

        val unlock = TODAY.plusDays(2)
        assertEquals(
            TaskState.Locked(listOf(LockReason.DelayAfter(dep.id, "Dep", 2, unlock)), unlock),
            result.stateOf(dependent),
        )
        assertEquals(2L, result.positionOf(dependent))
    }

    @Test
    fun `dependent still waiting on another task is not moved`() {
        val dep = openTask("Dep", position = 1)
        val other = openTask("Other", position = 2)
        val dependent = openTask("Dependent", position = 3).dependsOn(dep).dependsOn(other)

        val result = ops.complete(tasksOf(dep, other, dependent), dep.id)

        assertEquals(TaskState.Locked(listOf(WaitingOn(other.id, "Other", 0)), null), result.stateOf(dependent))
        assertEquals(3L, result.positionOf(dependent))
    }

    // --- uncomplete ------------------------------------------------------------------------

    @Test
    fun `uncomplete reopens the task and moves it to the top`() {
        val a = doneTask("A", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0)), position = 4)
        val other = openTask("Other", position = 1)
        val result = ops.uncomplete(tasksOf(a, other), a.id)
        val reopened = result.getValue(a.id)
        assertEquals(TaskStatus.OPEN, reopened.status)
        assertNull(reopened.completedAt)
        assertEquals(now, reopened.updatedAt)
        assertEquals(TaskState.Available, result.stateOf(a))
        assertTrue(reopened.position < result.positionOf(other))
        assertEquals(1L, result.positionOf(other))
    }

    @Test
    fun `uncompleting a task that is locked by its own rules does not move it`() {
        // Its dependency was reopened after it was completed, so the reopened task goes to Locked.
        val dep = openTask("Dep", position = 1)
        val a = doneTask("A", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0)), position = 4).dependsOn(dep)

        val result = ops.uncomplete(tasksOf(dep, a), a.id)

        assertEquals(TaskState.Locked(listOf(WaitingOn(dep.id, "Dep", 0)), null), result.stateOf(a))
        assertEquals(4L, result.positionOf(a))
    }

    @Test
    fun `uncompleting an open task is a no-op`() {
        val tasks = tasksOf(openTask("A"))
        assertSame(tasks, ops.uncomplete(tasks, tasks.keys.single()))
    }

    @Test
    fun `undo re-locks open dependents`() {
        val dep = openTask("Dep")
        val dependent = openTask("Dependent").dependsOn(dep)

        val completed = ops.complete(tasksOf(dep, dependent), dep.id)
        assertEquals(TaskState.Available, completed.stateOf(dependent))

        val undone = ops.uncomplete(completed, dep.id)
        assertEquals(TaskState.Locked(listOf(WaitingOn(dep.id, "Dep", 0)), null), undone.stateOf(dependent))
    }

    @Test
    fun `undo does not reopen a dependent that is already done`() {
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0)))
        val dependent = doneTask("Dependent", instantAt(LocalDateTime.of(2026, 11, 30, 13, 0))).dependsOn(dep)

        val result = ops.uncomplete(tasksOf(dep, dependent), dep.id)

        assertEquals(TaskState.Done, result.stateOf(dependent))
    }

    // --- delete ----------------------------------------------------------------------------

    @Test
    fun `delete removes the task and drops it from dependency lists`() {
        val dep = openTask("Dep")
        val other = openTask("Other")
        val dependent = openTask("Dependent").dependsOn(dep).dependsOn(other)

        val result = ops.delete(tasksOf(dep, other, dependent), dep.id)

        assertFalse(dep.id in result)
        assertEquals(listOf(Dependency(other.id)), result.getValue(dependent.id).dependencies)
    }

    @Test
    fun `deleting an unknown task is a no-op`() {
        val tasks = tasksOf(openTask("A"))
        assertSame(tasks, ops.delete(tasks, TaskId.random()))
    }

    @Test
    fun `deleting an open dependency frees the dependent and moves it to the top`() {
        val top = openTask("Top", position = 0)
        val dep = openTask("Dep", position = 1)
        val dependent = openTask("Dependent", position = 2).dependsOn(dep, delayDays = 3)

        val result = ops.delete(tasksOf(top, dep, dependent), dep.id)

        assertEquals(TaskState.Available, result.stateOf(dependent))
        assertNull(result.getValue(dependent.id).availableFrom)
        assertTrue(result.positionOf(dependent) < result.positionOf(top))
    }

    @Test
    fun `deleting a done dependency inside its delay window keeps the unlock date`() {
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 30, 20, 0)))
        val dependent = openTask("Dependent").dependsOn(dep, delayDays = 3)
        val tasks = tasksOf(dep, dependent)
        val unlock = LocalDate.of(2026, 12, 3)
        assertEquals(unlock, (tasks.stateOf(dependent) as TaskState.Locked).unlockDate)

        val result = ops.delete(tasks, dep.id)

        val after = result.getValue(dependent.id)
        assertEquals(emptyList<Dependency>(), after.dependencies)
        assertEquals(unlock, after.availableFrom)
        assertEquals(TaskState.Locked(listOf(LockReason.NotBefore(unlock)), unlock), result.stateOf(dependent))
    }

    @Test
    fun `deleting a done dependency keeps a later existing availableFrom`() {
        val later = LocalDate.of(2026, 12, 20)
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 30, 20, 0)))
        val dependent = openTask("Dependent", availableFrom = later).dependsOn(dep, delayDays = 3)

        val result = ops.delete(tasksOf(dep, dependent), dep.id)

        assertEquals(later, result.getValue(dependent.id).availableFrom)
    }

    @Test
    fun `deleting a done dependency replaces an earlier existing availableFrom`() {
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 30, 20, 0)))
        val dependent = openTask("Dependent", availableFrom = LocalDate.of(2026, 12, 2)).dependsOn(dep, delayDays = 3)

        val result = ops.delete(tasksOf(dep, dependent), dep.id)

        assertEquals(LocalDate.of(2026, 12, 3), result.getValue(dependent.id).availableFrom)
    }

    @Test
    fun `deleting a done dependency whose delay has elapsed leaves availableFrom alone`() {
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 20, 20, 0)))
        val dependent = openTask("Dependent", position = 5).dependsOn(dep, delayDays = 3)

        val result = ops.delete(tasksOf(dep, dependent), dep.id)

        val after = result.getValue(dependent.id)
        assertNull(after.availableFrom)
        assertEquals(TaskState.Available, result.stateOf(dependent))
        // It was already available, so it does not move.
        assertEquals(5L, after.position)
    }

    @Test
    fun `deleting a done dependency in its window still applies when another dependency is open`() {
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 30, 20, 0)))
        val other = openTask("Other")
        val dependent = openTask("Dependent").dependsOn(dep, delayDays = 3).dependsOn(other)

        val result = ops.delete(tasksOf(dep, other, dependent), dep.id)

        val unlock = LocalDate.of(2026, 12, 3)
        assertEquals(unlock, result.getValue(dependent.id).availableFrom)
        assertEquals(
            TaskState.Locked(listOf(LockReason.NotBefore(unlock), WaitingOn(other.id, "Other", 0)), null),
            result.stateOf(dependent),
        )
    }

    @Test
    fun `deleting a done dependency does not touch done dependents`() {
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 30, 20, 0)))
        val dependent = doneTask("Dependent", instantAt(LocalDateTime.of(2026, 11, 30, 21, 0))).dependsOn(dep, 3)

        val result = ops.delete(tasksOf(dep, dependent), dep.id)

        assertNull(result.getValue(dependent.id).availableFrom)
        assertEquals(emptyList<Dependency>(), result.getValue(dependent.id).dependencies)
    }

    // --- setDependencies -------------------------------------------------------------------

    @Test
    fun `setDependencies replaces the list and bumps updatedAt`() {
        val b = openTask("B")
        val a = openTask("A")
        val result = ops.setDependencies(tasksOf(a, b), a.id, listOf(Dependency(b.id, 2))).getValue(a.id)
        assertEquals(listOf(Dependency(b.id, 2)), result.dependencies)
        assertEquals(now, result.updatedAt)
    }

    @Test
    fun `setDependencies rejects a cycle`() {
        val a = openTask("A")
        val b = openTask("B").dependsOn(a)
        assertThrows(DependencyException.Cycle::class.java) {
            ops.setDependencies(tasksOf(a, b), a.id, listOf(Dependency(b.id)))
        }
    }

    @Test
    fun `setDependencies rejects a self dependency`() {
        val a = openTask("A")
        assertThrows(DependencyException.SelfDependency::class.java) {
            ops.setDependencies(tasksOf(a), a.id, listOf(Dependency(a.id)))
        }
    }

    @Test
    fun `removing the last blocking dependency moves the task to the top`() {
        val top = openTask("Top", position = 0)
        val dep = openTask("Dep", position = 1)
        val a = openTask("A", position = 2).dependsOn(dep)

        val result = ops.setDependencies(tasksOf(top, dep, a), a.id, emptyList())

        assertEquals(TaskState.Available, result.stateOf(a))
        assertTrue(result.positionOf(a) < result.positionOf(top))
    }

    @Test
    fun `adding a dependency locks an available task without moving it`() {
        val dep = openTask("Dep", position = 0)
        val a = openTask("A", position = 3)

        val result = ops.setDependencies(tasksOf(dep, a), a.id, listOf(Dependency(dep.id)))

        assertEquals(TaskState.Locked(listOf(WaitingOn(dep.id, "Dep", 0)), null), result.stateOf(a))
        assertEquals(3L, result.positionOf(a))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setDependencies on an unknown task fails`() {
        ops.setDependencies(tasksOf(openTask("A")), TaskId.random(), emptyList())
    }

    // --- refreshForDate (midnight) ---------------------------------------------------------

    @Test
    fun `refresh moves tasks unlocked by date to the top`() {
        val top = openTask("Top", position = 0)
        val dated = openTask("Dated", position = 5, availableFrom = TODAY)
        val dep = doneTask("Dep", instantAt(LocalDateTime.of(2026, 11, 29, 15, 0)), position = 6)
        val delayed = openTask("Delayed", position = 4).dependsOn(dep, delayDays = 2)
        val future = openTask("Future", position = 7, availableFrom = TODAY.plusDays(1))

        val result = ops.refreshForDate(tasksOf(top, dated, dep, delayed, future), previousDate = TODAY.minusDays(1))

        assertEquals(TaskState.Available, result.stateOf(dated))
        assertEquals(TaskState.Available, result.stateOf(delayed))
        assertTrue(result.positionOf(delayed) < result.positionOf(dated))
        assertTrue(result.positionOf(dated) < result.positionOf(top))
        assertEquals(0L, result.positionOf(top))
        assertEquals(7L, result.positionOf(future))
    }

    @Test
    fun `refresh covers several missed days`() {
        val top = openTask("Top", position = 0)
        val dated = openTask("Dated", position = 5, availableFrom = TODAY.minusDays(2))

        val result = ops.refreshForDate(tasksOf(top, dated), previousDate = TODAY.minusDays(4))

        assertTrue(result.positionOf(dated) < result.positionOf(top))
    }

    @Test
    fun `refresh on the same day changes nothing`() {
        val tasks = tasksOf(openTask("Top", position = 0), openTask("Dated", position = 5, availableFrom = TODAY))
        assertEquals(tasks, ops.refreshForDate(tasks, previousDate = TODAY))
    }

    // --- add -------------------------------------------------------------------------------

    @Test
    fun `add creates an open task at the top`() {
        val top = openTask("Top", position = 3)
        val id = TaskId.random()
        val due = TODAY.plusDays(7)

        val result = ops.add(tasksOf(top), id, "New", notes = "n", dueDate = due)

        val added = result.getValue(id)
        assertEquals("New", added.title)
        assertEquals("n", added.notes)
        assertEquals(due, added.dueDate)
        assertEquals(TaskStatus.OPEN, added.status)
        assertEquals(now, added.createdAt)
        assertEquals(now, added.updatedAt)
        assertTrue(added.position < result.positionOf(top))
        assertEquals(3L, result.positionOf(top))
    }

    @Test
    fun `add into an empty set works`() {
        val id = TaskId.random()
        assertEquals(setOf(id), ops.add(emptyMap(), id, "First").keys)
    }

    @Test
    fun `add stores availableFrom and dependencies`() {
        val dep = openTask("Dep")
        val id = TaskId.random()
        val from = TODAY.plusDays(2)

        val result = ops.add(tasksOf(dep), id, "New", availableFrom = from, dependencies = listOf(Dependency(dep.id, 1)))

        val added = result.getValue(id)
        assertEquals(from, added.availableFrom)
        assertEquals(listOf(Dependency(dep.id, 1)), added.dependencies)
        assertEquals(
            TaskState.Locked(listOf(LockReason.NotBefore(from), WaitingOn(dep.id, "Dep", 1)), null),
            result.stateOf(added),
        )
    }

    @Test
    fun `add rejects invalid dependencies`() {
        val id = TaskId.random()
        assertThrows(DependencyException.UnknownTask::class.java) {
            ops.add(emptyMap(), id, "New", dependencies = listOf(Dependency(TaskId.random())))
        }
        assertThrows(DependencyException.SelfDependency::class.java) {
            ops.add(emptyMap(), id, "New", dependencies = listOf(Dependency(id)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `add rejects an existing id`() {
        val a = openTask("A")
        ops.add(tasksOf(a), a.id, "Again")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `add rejects a blank title`() {
        ops.add(emptyMap(), TaskId.random(), " ")
    }

    // --- move ------------------------------------------------------------------------------

    @Test
    fun `move up swaps positions with the previous task in the list`() {
        val a = openTask("A", position = 1)
        val b = openTask("B", position = 5)
        val c = openTask("C", position = 9)
        val order = listOf(a.id, b.id, c.id)

        val result = ops.move(tasksOf(a, b, c), c.id, order, MoveDirection.UP)

        assertEquals(5L, result.positionOf(c))
        assertEquals(9L, result.positionOf(b))
        assertEquals(1L, result.positionOf(a))
        assertEquals(c.updatedAt, result.getValue(c.id).updatedAt)
    }

    @Test
    fun `move down swaps positions with the next task in the list`() {
        val a = openTask("A", position = 1)
        val b = openTask("B", position = 5)

        val result = ops.move(tasksOf(a, b), a.id, listOf(a.id, b.id), MoveDirection.DOWN)

        assertEquals(5L, result.positionOf(a))
        assertEquals(1L, result.positionOf(b))
    }

    @Test
    fun `move only considers the given list, skipping tasks outside it`() {
        val a = openTask("A", position = 1)
        val hidden = openTask("Hidden", position = 2, availableFrom = TODAY.plusDays(1))
        val b = openTask("B", position = 3)

        val result = ops.move(tasksOf(a, hidden, b), b.id, listOf(a.id, b.id), MoveDirection.UP)

        assertEquals(1L, result.positionOf(b))
        assertEquals(3L, result.positionOf(a))
        assertEquals(2L, result.positionOf(hidden))
    }

    @Test
    fun `moving past either end is a no-op`() {
        val a = openTask("A", position = 1)
        val b = openTask("B", position = 2)
        val tasks = tasksOf(a, b)
        val order = listOf(a.id, b.id)
        assertSame(tasks, ops.move(tasks, a.id, order, MoveDirection.UP))
        assertSame(tasks, ops.move(tasks, b.id, order, MoveDirection.DOWN))
    }

    @Test
    fun `moving a task that is not in the list or unknown is a no-op`() {
        val a = openTask("A", position = 1)
        val b = openTask("B", position = 2)
        val tasks = tasksOf(a, b)
        assertSame(tasks, ops.move(tasks, b.id, listOf(a.id), MoveDirection.UP))
        assertSame(tasks, ops.move(tasks, TaskId.random(), listOf(a.id, b.id), MoveDirection.UP))
    }

    // --- updateDetails ---------------------------------------------------------------------

    @Test
    fun `updateDetails changes the fields and bumps updatedAt`() {
        val a = openTask("A", position = 2)
        val due = TODAY.plusDays(3)

        val result = ops.updateDetails(tasksOf(a), a.id, "A2", notes = "n", dueDate = due, availableFrom = null)

        val updated = result.getValue(a.id)
        assertEquals("A2", updated.title)
        assertEquals("n", updated.notes)
        assertEquals(due, updated.dueDate)
        assertEquals(now, updated.updatedAt)
        assertEquals(2L, updated.position)
    }

    @Test
    fun `updateDetails with unchanged values is a no-op`() {
        val a = openTask("A")
        val tasks = tasksOf(a)
        assertSame(tasks, ops.updateDetails(tasks, a.id, "A", notes = null, dueDate = null, availableFrom = null))
    }

    @Test
    fun `updateDetails on an unknown task is a no-op`() {
        val tasks = tasksOf(openTask("A"))
        assertSame(tasks, ops.updateDetails(tasks, TaskId.random(), "X", null, null, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateDetails rejects a blank title`() {
        val a = openTask("A")
        ops.updateDetails(tasksOf(a), a.id, "", null, null, null)
    }

    @Test
    fun `moving availableFrom to today unlocks the task and moves it to the top`() {
        val top = openTask("Top", position = 0)
        val a = openTask("A", position = 5, availableFrom = TODAY.plusDays(3))

        val result = ops.updateDetails(tasksOf(top, a), a.id, "A", null, null, availableFrom = TODAY)

        assertEquals(TaskState.Available, result.stateOf(a))
        assertTrue(result.positionOf(a) < result.positionOf(top))
    }

    @Test
    fun `moving availableFrom to the future locks the task without moving it`() {
        val a = openTask("A", position = 5)
        val from = TODAY.plusDays(3)

        val result = ops.updateDetails(tasksOf(a), a.id, "A", null, null, availableFrom = from)

        assertEquals(TaskState.Locked(listOf(LockReason.NotBefore(from)), from), result.stateOf(a))
        assertEquals(5L, result.positionOf(a))
    }
}
