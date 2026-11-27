package com.david.taskmanager.core

import com.david.taskmanager.core.LockReason.DelayAfter
import com.david.taskmanager.core.LockReason.NotBefore
import com.david.taskmanager.core.LockReason.WaitingOn
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AvailabilityEvaluatorTest {

    private val evaluator = AvailabilityEvaluator(DEFAULT_CLOCK)

    // --- availableFrom ---------------------------------------------------------------------

    @Test
    fun `open task without rules is available`() {
        val a = openTask("A")
        assertEquals(TaskState.Available, evaluator.evaluate(a, tasksOf(a)))
    }

    @Test
    fun `availableFrom in the past is available`() {
        val a = openTask("A", availableFrom = TODAY.minusDays(1))
        assertEquals(TaskState.Available, evaluator.evaluate(a, tasksOf(a)))
    }

    @Test
    fun `availableFrom today is available`() {
        val a = openTask("A", availableFrom = TODAY)
        assertEquals(TaskState.Available, evaluator.evaluate(a, tasksOf(a)))
    }

    @Test
    fun `availableFrom in the future is locked until that date`() {
        val from = LocalDate.of(2026, 12, 3)
        val a = openTask("A", availableFrom = from)
        assertEquals(
            TaskState.Locked(listOf(NotBefore(from)), unlockDate = from),
            evaluator.evaluate(a, tasksOf(a)),
        )
    }

    @Test
    fun `done task is done even if its rules are not met`() {
        val dep = openTask("Dep")
        val a = doneTask("A", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0))).dependsOn(dep)
            .copy(availableFrom = TODAY.plusDays(10))
        assertEquals(TaskState.Done, evaluator.evaluate(a, tasksOf(a, dep)))
    }

    // --- dependencies ----------------------------------------------------------------------

    @Test
    fun `open dependency locks with no computable unlock date`() {
        val dep = openTask("Buy paint")
        val a = openTask("Paint wall").dependsOn(dep)
        assertEquals(
            TaskState.Locked(listOf(WaitingOn(dep.id, "Buy paint", delayDays = 0)), unlockDate = null),
            evaluator.evaluate(a, tasksOf(a, dep)),
        )
    }

    @Test
    fun `open dependency with delay reports the delay`() {
        val dep = openTask("Plant")
        val a = openTask("Water").dependsOn(dep, delayDays = 3)
        assertEquals(
            TaskState.Locked(listOf(WaitingOn(dep.id, "Plant", delayDays = 3)), unlockDate = null),
            evaluator.evaluate(a, tasksOf(a, dep)),
        )
    }

    @Test
    fun `done dependency with zero delay completed today satisfies immediately`() {
        val dep = doneTask("Dep", instantAt(TODAY.atTime(9, 59)))
        val a = openTask("A").dependsOn(dep)
        assertEquals(TaskState.Available, evaluator.evaluate(a, tasksOf(a, dep)))
    }

    @Test
    fun `done dependency inside its delay window locks until completion date plus delay`() {
        val dep = doneTask("Plant", instantAt(LocalDateTime.of(2026, 11, 30, 18, 0)))
        val a = openTask("Water").dependsOn(dep, delayDays = 2)
        val unlock = LocalDate.of(2026, 12, 2)
        assertEquals(
            TaskState.Locked(listOf(DelayAfter(dep.id, "Plant", 2, unlock)), unlockDate = unlock),
            evaluator.evaluate(a, tasksOf(a, dep)),
        )
    }

    @Test
    fun `delay window ending today is satisfied`() {
        val dep = doneTask("Plant", instantAt(LocalDateTime.of(2026, 11, 29, 23, 0)))
        val a = openTask("Water").dependsOn(dep, delayDays = 2)
        assertEquals(TaskState.Available, evaluator.evaluate(a, tasksOf(a, dep)))
    }

    @Test
    fun `delay is counted in calendar days not 24h periods`() {
        // Completed at 23:30 with a 1-day delay: available from 00:00 the next day.
        val completed = instantAt(LocalDateTime.of(2026, 11, 30, 23, 30))
        val dep = doneTask("Dep", completed)
        val a = openTask("A").dependsOn(dep, delayDays = 1)
        val tasks = tasksOf(a, dep)

        val lateEvening = AvailabilityEvaluator(clockAt(LocalDateTime.of(2026, 11, 30, 23, 59)))
        val justAfterMidnight = AvailabilityEvaluator(clockAt(LocalDateTime.of(2026, 12, 1, 0, 0)))

        assertEquals(
            TaskState.Locked(listOf(DelayAfter(dep.id, "Dep", 1, TODAY)), TODAY),
            lateEvening.evaluate(a, tasks),
        )
        assertEquals(TaskState.Available, justAfterMidnight.evaluate(a, tasks))
    }

    @Test
    fun `completion date uses the clock time zone`() {
        // 23:30 on 30 Nov in Paris is 07:30 on 1 Dec in Tokyo.
        val completed = instantAt(LocalDateTime.of(2026, 11, 30, 23, 30), PARIS)
        val dep = doneTask("Dep", completed)
        val a = openTask("A").dependsOn(dep, delayDays = 1)
        val tasks = tasksOf(a, dep)

        val tokyo = ZoneId.of("Asia/Tokyo")
        val tokyoEvaluator = AvailabilityEvaluator(clockAt(LocalDateTime.of(2026, 12, 1, 12, 0), tokyo))
        val parisEvaluator = AvailabilityEvaluator(clockAt(LocalDateTime.of(2026, 12, 1, 12, 0), PARIS))

        val tokyoUnlock = LocalDate.of(2026, 12, 2)
        assertEquals(
            TaskState.Locked(listOf(DelayAfter(dep.id, "Dep", 1, tokyoUnlock)), tokyoUnlock),
            tokyoEvaluator.evaluate(a, tasks),
        )
        assertEquals(TaskState.Available, parisEvaluator.evaluate(a, tasks))
    }

    @Test
    fun `multiple dependencies all must be satisfied`() {
        val done = doneTask("Done", instantAt(LocalDateTime.of(2026, 11, 20, 12, 0)))
        val open = openTask("Open")
        val a = openTask("A").dependsOn(done).dependsOn(open)
        assertEquals(
            TaskState.Locked(listOf(WaitingOn(open.id, "Open", 0)), unlockDate = null),
            evaluator.evaluate(a, tasksOf(a, done, open)),
        )
    }

    @Test
    fun `all dependencies satisfied makes task available`() {
        val d1 = doneTask("D1", instantAt(LocalDateTime.of(2026, 11, 20, 12, 0)))
        val d2 = doneTask("D2", instantAt(LocalDateTime.of(2026, 11, 28, 12, 0)))
        val a = openTask("A").dependsOn(d1, 5).dependsOn(d2, 3)
        assertEquals(TaskState.Available, evaluator.evaluate(a, tasksOf(a, d1, d2)))
    }

    @Test
    fun `unlock date is the latest of all date constraints`() {
        val from = LocalDate.of(2026, 12, 4)
        val d1 = doneTask("D1", instantAt(LocalDateTime.of(2026, 11, 30, 12, 0)))
        val d2 = doneTask("D2", instantAt(LocalDateTime.of(2026, 12, 1, 8, 0)))
        val a = openTask("A", availableFrom = from).dependsOn(d1, 2).dependsOn(d2, 6)
        assertEquals(
            TaskState.Locked(
                listOf(
                    NotBefore(from),
                    DelayAfter(d1.id, "D1", 2, LocalDate.of(2026, 12, 2)),
                    DelayAfter(d2.id, "D2", 6, LocalDate.of(2026, 12, 7)),
                ),
                unlockDate = LocalDate.of(2026, 12, 7),
            ),
            evaluator.evaluate(a, tasksOf(a, d1, d2)),
        )
    }

    @Test
    fun `any open dependency makes the unlock date unknown even with dated constraints`() {
        val from = LocalDate.of(2026, 12, 4)
        val open = openTask("Open")
        val a = openTask("A", availableFrom = from).dependsOn(open)
        assertEquals(
            TaskState.Locked(listOf(NotBefore(from), WaitingOn(open.id, "Open", 0)), unlockDate = null),
            evaluator.evaluate(a, tasksOf(a, open)),
        )
    }

    @Test
    fun `availableFrom and dependencies both must hold`() {
        val done = doneTask("Done", instantAt(LocalDateTime.of(2026, 11, 20, 12, 0)))
        val from = TODAY.plusDays(1)
        val a = openTask("A", availableFrom = from).dependsOn(done)
        assertEquals(TaskState.Locked(listOf(NotBefore(from)), from), evaluator.evaluate(a, tasksOf(a, done)))
    }

    @Test
    fun `evaluating for an explicit date ignores the clock date`() {
        val a = openTask("A", availableFrom = TODAY)
        assertEquals(
            TaskState.Locked(listOf(NotBefore(TODAY)), TODAY),
            evaluator.evaluate(a, tasksOf(a), today = TODAY.minusDays(1)),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `missing dependency is a data error`() {
        val a = openTask("A").copy(dependencies = listOf(Dependency(TaskId.random())))
        evaluator.evaluate(a, tasksOf(a))
    }
}
