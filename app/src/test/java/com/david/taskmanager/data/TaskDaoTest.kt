package com.david.taskmanager.data

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskDaoTest {

    private lateinit var db: TaskDatabase
    private lateinit var dao: TaskDao

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        dao = db.taskDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `tasks and their dependencies round-trip`() = runTest {
        val a = taskEntity("a", position = 2, notes = null)
        val b = taskEntity("b", position = 1, notes = "n", status = "DONE", completedAt = 3_000L)
            .copy(dueDate = 20_000L, availableFrom = 19_999L)
        dao.upsertTasks(listOf(a, b))
        dao.insertDependencies(listOf(DependencyEntity("a", "b", delayDays = 2, sortOrder = 0)))

        val all = dao.getAll()

        assertEquals(listOf(b, a), all.map { it.task })
        assertEquals(listOf(DependencyEntity("a", "b", 2, 0)), all.single { it.task.id == "a" }.dependencies)
        assertEquals(emptyList<DependencyEntity>(), all.single { it.task.id == "b" }.dependencies)
    }

    @Test
    fun `deleting a task cascades to dependency rows on both sides`() = runTest {
        dao.upsertTasks(listOf(taskEntity("a"), taskEntity("b"), taskEntity("c")))
        dao.insertDependencies(
            listOf(DependencyEntity("a", "b", 0, 0), DependencyEntity("b", "c", 0, 0)),
        )

        dao.deleteTasks(listOf("b"))

        val all = dao.getAll()
        assertEquals(listOf("a", "c"), all.map { it.task.id }.sorted())
        assertEquals(0, all.sumOf { it.dependencies.size })
    }

    @Test
    fun `upserting an existing task keeps its dependency rows`() = runTest {
        dao.upsertTasks(listOf(taskEntity("a"), taskEntity("b")))
        dao.insertDependencies(listOf(DependencyEntity("a", "b", 0, 0)))

        dao.upsertTasks(listOf(taskEntity("a", title = "Renamed"), taskEntity("b", title = "Also renamed")))

        val a = dao.getAll().single { it.task.id == "a" }
        assertEquals("Renamed", a.task.title)
        assertEquals(1, a.dependencies.size)
    }

    @Test
    fun `a dependency on an unknown task violates the foreign key`() = runTest {
        dao.upsertTasks(listOf(taskEntity("a")))
        val failure = runCatching { dao.insertDependencies(listOf(DependencyEntity("a", "missing", 0, 0))) }
            .exceptionOrNull()
        assertTrue("expected a constraint violation, got $failure", failure is SQLiteConstraintException)
    }

    @Test
    fun `observeAll emits again after a write`() = runTest {
        val emissions = Channel<List<TaskWithDependencies>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { dao.observeAll().collect { emissions.send(it) } }

        assertEquals(0, emissions.receive().size)
        dao.upsertTasks(listOf(taskEntity("a")))
        assertEquals(listOf("a"), emissions.receive().map { it.task.id })

        job.cancel()
    }

    @Test
    fun `app state get and put`() = runTest {
        assertNull(dao.getState("k"))
        dao.putState(AppStateEntity("k", "v1"))
        dao.putState(AppStateEntity("k", "v2"))
        assertEquals("v2", dao.getState("k"))
    }
}
