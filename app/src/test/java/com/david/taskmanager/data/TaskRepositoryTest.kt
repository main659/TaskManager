package com.david.taskmanager.data

import com.david.taskmanager.core.AvailabilityEvaluator
import com.david.taskmanager.core.DependencyException
import com.david.taskmanager.core.TaskState
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryTest {

    private val clock = clockOn(TODAY)
    private lateinit var db: TaskDatabase
    private lateinit var repo: TaskRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = TaskRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun all(): List<Task> = repo.tasks.first()
    private suspend fun task(id: TaskId): Task = all().single { it.id == id }
    private suspend fun state(id: TaskId, at: Clock = clock): TaskState {
        val tasks = all().associateBy { it.id }
        return AvailabilityEvaluator(at).evaluate(tasks.getValue(id), tasks)
    }

    @Test
    fun `added tasks are persisted with all fields, newest on top`() = runTest {
        val a = repo.addTask("A")
        val due = TODAY.plusDays(10)
        val from = TODAY.plusDays(2)
        val b = repo.addTask("B", notes = "n", dueDate = due, availableFrom = from, dependencies = listOf(Dependency(a, 1)))

        assertEquals(listOf(b, a), all().map { it.id })
        val stored = task(b)
        assertEquals("n", stored.notes)
        assertEquals(due, stored.dueDate)
        assertEquals(from, stored.availableFrom)
        assertEquals(listOf(Dependency(a, 1)), stored.dependencies)
        assertEquals(clock.instant(), stored.createdAt)
    }

    @Test
    fun `completing the last dependency persists the unlock and the move to the top`() = runTest {
        val dep = repo.addTask("Dep")
        val dependent = repo.addTask("Dependent", dependencies = listOf(Dependency(dep)))
        val top = repo.addTask("Top")

        repo.complete(dep)

        assertEquals(TaskStatus.DONE, task(dep).status)
        assertEquals(clock.instant(), task(dep).completedAt)
        assertEquals(TaskState.Available, state(dependent))
        assertEquals(dependent, all().first().id)
        assertTrue(all().map { it.id }.indexOf(top) > 0)
    }

    @Test
    fun `uncomplete persists the reopened task at the top and re-locks dependents`() = runTest {
        val a = repo.addTask("A")
        val dependent = repo.addTask("Dependent", dependencies = listOf(Dependency(a)))
        repo.addTask("Top")
        repo.complete(a)

        repo.uncomplete(a)

        assertEquals(TaskStatus.OPEN, task(a).status)
        assertNull(task(a).completedAt)
        assertEquals(a, all().first().id)
        assertTrue(state(dependent) is TaskState.Locked)
    }

    @Test
    fun `deleting an open task removes it from dependency lists`() = runTest {
        val dep = repo.addTask("Dep")
        val other = repo.addTask("Other")
        val dependent = repo.addTask("Dependent", dependencies = listOf(Dependency(dep), Dependency(other)))

        repo.delete(dep)

        assertEquals(setOf(other, dependent), all().map { it.id }.toSet())
        assertEquals(listOf(Dependency(other)), task(dependent).dependencies)
    }

    @Test
    fun `deleting a done task inside the delay window stores the converted availableFrom`() = runTest {
        val dep = repo.addTask("Dep")
        val dependent = repo.addTask("Dependent", dependencies = listOf(Dependency(dep, delayDays = 3)))
        repo.complete(dep)

        repo.delete(dep)

        val stored = task(dependent)
        assertEquals(emptyList<Dependency>(), stored.dependencies)
        assertEquals(TODAY.plusDays(3), stored.availableFrom)
    }

    @Test
    fun `a rejected cycle leaves the database unchanged`() = runTest {
        val a = repo.addTask("A")
        val b = repo.addTask("B", dependencies = listOf(Dependency(a)))
        val before = all()

        val failure = runCatching { repo.setDependencies(a, listOf(Dependency(b))) }.exceptionOrNull()

        assertTrue("expected a cycle error, got $failure", failure is DependencyException.Cycle)
        assertEquals(before, all())
    }

    @Test
    fun `setDependencies persists several dependencies in order`() = runTest {
        val x = repo.addTask("X")
        val y = repo.addTask("Y")
        val z = repo.addTask("Z")
        val a = repo.addTask("A")
        val deps = listOf(Dependency(z, 2), Dependency(x), Dependency(y, 5))

        repo.setDependencies(a, deps)
        assertEquals(deps, task(a).dependencies)

        repo.setDependencies(a, listOf(Dependency(y)))
        assertEquals(listOf(Dependency(y)), task(a).dependencies)
    }

    @Test
    fun `updateDetails persists edits`() = runTest {
        val a = repo.addTask("A")
        val due = TODAY.plusDays(4)

        repo.updateDetails(a, "A2", "notes", due, availableFrom = null)

        val stored = task(a)
        assertEquals("A2", stored.title)
        assertEquals("notes", stored.notes)
        assertEquals(due, stored.dueDate)
    }

    @Test
    fun `repeating an operation changes nothing the second time`() = runTest {
        val a = repo.addTask("A")
        repo.complete(a)
        val afterFirst = all()

        TaskRepository(db, clockAt(TODAY.atTime(18, 0))).complete(a)

        assertEquals(afterFirst, all())

        repo.delete(a)
        repo.delete(a)
        assertEquals(emptyList<Task>(), all())
    }

    @Test
    fun `first refresh only records today`() = runTest {
        repo.addTask("A")
        val before = all()

        repo.refreshForToday()

        assertEquals(before, all())
        assertEquals(TODAY.toString(), db.taskDao().getState(TaskRepository.KEY_LAST_EVALUATED_DATE))
    }

    @Test
    fun `refresh on a later day moves tasks unlocked by date to the top`() = runTest {
        val tomorrow = TODAY.plusDays(1)
        val dated = repo.addTask("Dated", availableFrom = tomorrow)
        val top = repo.addTask("Top")
        repo.refreshForToday()
        assertEquals(top, all().first().id)

        val nextDayClock = clockAt(LocalDateTime.of(2026, 12, 2, 0, 1))
        TaskRepository(db, nextDayClock).refreshForToday()

        assertEquals(dated, all().first().id)
        assertEquals(TaskState.Available, state(dated, at = nextDayClock))
        assertEquals(tomorrow.toString(), db.taskDao().getState(TaskRepository.KEY_LAST_EVALUATED_DATE))
    }

    @Test
    fun `refresh after the date moved back promotes nothing`() = runTest {
        val dated = repo.addTask("Dated", availableFrom = TODAY.minusDays(1))
        repo.addTask("Top")
        db.taskDao().putState(AppStateEntity(TaskRepository.KEY_LAST_EVALUATED_DATE, LocalDate.of(2026, 12, 5).toString()))
        val before = all()

        repo.refreshForToday()

        assertEquals(before, all())
        assertTrue(all().first().id != dated)
        assertEquals(TODAY.toString(), db.taskDao().getState(TaskRepository.KEY_LAST_EVALUATED_DATE))
    }
}
