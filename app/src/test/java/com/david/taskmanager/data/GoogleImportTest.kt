package com.david.taskmanager.data

import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.RemoteTaskList
import com.david.taskmanager.core.model.Task
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class GoogleImportTest {

    private val clock = clockOn(TODAY)
    private val list = RemoteTaskList("L1", "My Tasks")
    private val updated = Instant.parse("2026-11-27T09:00:00Z")
    private var changes = 0
    private lateinit var db: TaskDatabase
    private lateinit var repo: TaskRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = TaskRepository(db, clock, onChange = { changes++ })
    }

    @After
    fun tearDown() = db.close()

    private fun remote(id: String, title: String) = RemoteTask(id, title, notes = null, dueDate = null, updated = updated)

    private suspend fun all(): List<Task> = repo.tasks.first()

    private suspend fun links() = db.taskDao().getGoogleLinks().associate { it.googleTaskId to it.taskId }

    @Test
    fun `import creates the tasks on top, links them and records the connection`() = runTest {
        val existing = repo.addTask("Existing")

        val result = repo.importFromGoogle(list, listOf(remote("g1", "First"), remote("g2", "Second")))

        assertEquals(ImportResult(imported = 2, matched = 0), result)
        assertEquals(listOf("First", "Second", "Existing"), all().map { it.title })
        val byTitle = all().associateBy { it.title }
        assertEquals(mapOf("g1" to byTitle.getValue("First").id.toString(), "g2" to byTitle.getValue("Second").id.toString()), links())
        assertEquals(updated.toEpochMilli(), db.taskDao().getGoogleLinks().first().googleUpdatedAt)
        assertEquals(GoogleConnection("L1", "My Tasks"), repo.googleConnection.first())
        assertTrue(existing in all().map { it.id })
    }

    @Test
    fun `importing again only adds tasks that are new in Google`() = runTest {
        repo.importFromGoogle(list, listOf(remote("g1", "First")))
        val before = all()
        val changesBefore = changes

        val again = repo.importFromGoogle(list, listOf(remote("g1", "First"), remote("g2", "New")))

        assertEquals(ImportResult(imported = 1, matched = 0), again)
        assertEquals(listOf("New") + before.map { it.title }, all().map { it.title })
        assertEquals(changesBefore + 1, changes)
        assertEquals(setOf("g1", "g2"), links().keys)
    }

    @Test
    fun `a Google task with the title of a local task is linked, not duplicated`() = runTest {
        val local = repo.addTask("Buy milk")

        val result = repo.importFromGoogle(list, listOf(remote("g1", "buy milk")))

        assertEquals(ImportResult(imported = 0, matched = 1), result)
        assertEquals(listOf("Buy milk"), all().map { it.title })
        assertEquals(mapOf("g1" to local.toString()), links())
    }

    @Test
    fun `the widget is only refreshed when tasks were created`() = runTest {
        repo.addTask("Buy milk")
        val before = changes

        repo.importFromGoogle(list, listOf(remote("g1", "Buy milk")))

        assertEquals(before, changes)
    }

    @Test
    fun `importing another list while connected is refused and changes nothing`() = runTest {
        repo.importFromGoogle(list, listOf(remote("g1", "First")))
        val before = all()

        val failure = runCatching {
            repo.importFromGoogle(RemoteTaskList("L2", "Work"), listOf(remote("w1", "Other")))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(before, all())
        assertEquals(setOf("g1"), links().keys)
    }

    @Test
    fun `disconnect forgets the list and the links but keeps the tasks`() = runTest {
        repo.importFromGoogle(list, listOf(remote("g1", "First")))

        repo.disconnectGoogle()

        assertNull(repo.googleConnection.first())
        assertEquals(emptyMap<String, String>(), links())
        assertEquals(listOf("First"), all().map { it.title })
        // Reconnecting links the task again instead of duplicating it.
        assertEquals(ImportResult(imported = 0, matched = 1), repo.importFromGoogle(list, listOf(remote("g1", "First"))))
    }

    @Test
    fun `links survive a local delete so the Google copy is not imported again`() = runTest {
        repo.importFromGoogle(list, listOf(remote("g1", "First")))
        repo.delete(all().single().id)

        val again = repo.importFromGoogle(list, listOf(remote("g1", "First")))

        assertEquals(ImportResult(imported = 0, matched = 0), again)
        assertEquals(emptyList<Task>(), all())
        assertEquals(setOf("g1"), links().keys)
    }
}
