package com.david.taskmanager.core

import com.david.taskmanager.core.LockReason.NotBefore
import com.david.taskmanager.core.LockReason.WaitingOn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TaskListsTest {

    private val evaluator = AvailabilityEvaluator(DEFAULT_CLOCK)

    @Test
    fun `empty input gives empty lists`() {
        assertEquals(TaskLists(emptyList(), emptyList(), emptyList()), TaskLists.from(emptyList(), evaluator))
    }

    @Test
    fun `tasks are grouped by state`() {
        val dep = openTask("Dep", position = 2)
        val waiting = openTask("Waiting", position = 1).dependsOn(dep)
        val done = doneTask("Done", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0)), position = 0)

        val lists = TaskLists.from(listOf(waiting, done, dep), evaluator)

        assertEquals(listOf(dep), lists.available)
        assertEquals(listOf(LockedTask(waiting, listOf(WaitingOn(dep.id, "Dep", 0)), null)), lists.locked)
        assertEquals(listOf(done), lists.done)
    }

    @Test
    fun `available tasks are sorted by position`() {
        val c = openTask("C", position = 3)
        val a = openTask("A", position = -5)
        val b = openTask("B", position = 0)
        assertEquals(listOf(a, b, c), TaskLists.from(listOf(c, a, b), evaluator).available)
    }

    @Test
    fun `locked tasks are sorted by unlock date, then unknown dates, then position`() {
        val dep = openTask("Dep", position = 0)
        val waitingLate = openTask("WaitingLate", position = 9).dependsOn(dep)
        val waitingEarly = openTask("WaitingEarly", position = 1).dependsOn(dep)
        val inFive = openTask("InFive", position = 2, availableFrom = TODAY.plusDays(5))
        val inTwoB = openTask("InTwoB", position = 8, availableFrom = TODAY.plusDays(2))
        val inTwoA = openTask("InTwoA", position = 3, availableFrom = TODAY.plusDays(2))

        val locked = TaskLists.from(listOf(dep, waitingLate, waitingEarly, inFive, inTwoB, inTwoA), evaluator).locked

        assertEquals(listOf(inTwoA, inTwoB, inFive, waitingEarly, waitingLate), locked.map { it.task })
        assertEquals(TODAY.plusDays(2), locked.first().unlockDate)
        assertEquals(listOf(NotBefore(TODAY.plusDays(2))), locked.first().reasons)
    }

    @Test
    fun `done tasks are sorted most recently completed first`() {
        val older = doneTask("Older", instantAt(LocalDateTime.of(2026, 11, 20, 12, 0)), position = 0)
        val newer = doneTask("Newer", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0)), position = 1)
        assertEquals(listOf(newer, older), TaskLists.from(listOf(older, newer), evaluator).done)
    }

    @Test
    fun `lists can be built for an explicit date`() {
        val a = openTask("A", availableFrom = LocalDate.of(2026, 12, 3))
        val lists = TaskLists.from(listOf(a), evaluator, today = LocalDate.of(2026, 12, 3))
        assertEquals(listOf(a), lists.available)
    }
}
