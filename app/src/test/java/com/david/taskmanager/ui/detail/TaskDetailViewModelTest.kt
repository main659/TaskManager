package com.david.taskmanager.ui.detail

import androidx.lifecycle.ViewModelStore
import com.david.taskmanager.core.TaskState
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.data.TODAY
import com.david.taskmanager.data.TaskDatabase
import com.david.taskmanager.data.TaskRepository
import com.david.taskmanager.data.clockOn
import com.david.taskmanager.data.inMemoryDatabase
import com.david.taskmanager.ui.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TaskDetailViewModelTest {

    private val clock = clockOn(TODAY)
    private val today = MutableStateFlow(TODAY)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ViewModelStore()
    private lateinit var db: TaskDatabase
    private lateinit var repo: TaskRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = TaskRepository(db, clock)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** Main runs on the test scheduler so the save delay can be advanced in virtual time. */
    private fun TestScope.viewModelFor(id: TaskId): TaskDetailViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return store.create { TaskDetailViewModel(id, repo, clock, today, appScope) }
    }

    private suspend fun TaskDetailViewModel.detailWhere(predicate: (TaskDetail) -> Boolean): TaskDetail =
        state.filterIsInstance<DetailState.Loaded>().first { predicate(it.detail) }.detail

    private suspend fun stored(id: TaskId): Task = repo.tasks.first().single { it.id == id }

    /** Waits for real (non-virtual) background work, such as a write in the app scope, to land. */
    private suspend fun awaitStored(id: TaskId, predicate: (Task) -> Boolean): Task =
        repo.tasks.first { tasks -> tasks.any { it.id == id && predicate(it) } }.single { it.id == id }

    @Test
    fun `loads the task text and its details`() = runTest {
        val dep = repo.addTask("Dep")
        val a = repo.addTask("A", notes = "n", dependencies = listOf(Dependency(dep, 2)))
        val viewModel = viewModelFor(a)

        assertEquals(TextFields("A", "n"), viewModel.text.filterNotNull().first())
        val detail = viewModel.detailWhere { true }
        assertEquals(listOf(DependencyItem(stored(dep), 2)), detail.dependencies)
        assertTrue(detail.state is TaskState.Locked)
    }

    @Test
    fun `title and notes are saved after a pause`() = runTest {
        val a = repo.addTask("A")
        val viewModel = viewModelFor(a)
        viewModel.text.filterNotNull().first()

        viewModel.onTitleChange("A2")
        viewModel.onNotesChange("notes")
        advanceTimeBy(TaskDetailViewModel.SAVE_DELAY_MS + 1)

        val saved = awaitStored(a) { it.title == "A2" }
        assertEquals("notes", saved.notes)
    }

    @Test
    fun `pending text is saved when the screen closes`() = runTest {
        val a = repo.addTask("A")
        val viewModel = viewModelFor(a)
        viewModel.text.filterNotNull().first()

        viewModel.onTitleChange("Closed")
        store.clear()

        assertEquals("Closed", awaitStored(a) { it.title == "Closed" }.title)
    }

    @Test
    fun `a blank title is never saved`() = runTest {
        val a = repo.addTask("A")
        val viewModel = viewModelFor(a)
        viewModel.text.filterNotNull().first()

        viewModel.onTitleChange("   ")
        assertNull(viewModel.saveText())
        store.clear()
        advanceTimeBy(TaskDetailViewModel.SAVE_DELAY_MS + 1)

        assertEquals("A", stored(a).title)
    }

    @Test
    fun `candidates exclude itself, current dependencies and tasks that would create a cycle`() = runTest {
        val a = repo.addTask("A")
        val waitsOnA = repo.addTask("WaitsOnA", dependencies = listOf(Dependency(a)))
        val waitsOnThat = repo.addTask("WaitsOnThat", dependencies = listOf(Dependency(waitsOnA)))
        val free = repo.addTask("Free")
        val current = repo.addTask("Current")
        repo.setDependencies(a, listOf(Dependency(current)))
        val viewModel = viewModelFor(a)

        val detail = viewModel.detailWhere { true }

        assertEquals(listOf(free), detail.candidates.map { it.id })
        assertEquals(listOf(waitsOnA), detail.dependents.map { it.id })
        assertTrue(waitsOnThat !in detail.candidates.map { it.id })
    }

    @Test
    fun `dependencies can be added, delayed and removed`() = runTest {
        val dep = repo.addTask("Dep")
        val a = repo.addTask("A")
        val viewModel = viewModelFor(a)

        viewModel.addDependency(dep).join()
        assertEquals(listOf(Dependency(dep)), stored(a).dependencies)

        viewModel.setDelay(dep, 3).join()
        assertEquals(listOf(Dependency(dep, 3)), stored(a).dependencies)

        viewModel.setDelay(dep, -5).join()
        assertEquals(listOf(Dependency(dep, 0)), stored(a).dependencies)

        viewModel.removeDependency(dep).join()
        assertEquals(emptyList<Dependency>(), stored(a).dependencies)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `a rejected dependency is reported as an error`() = runTest {
        val a = repo.addTask("A")
        val b = repo.addTask("B", dependencies = listOf(Dependency(a)))
        val viewModel = viewModelFor(a)

        viewModel.addDependency(b).join()

        assertEquals("Circular dependency: A → B → A", viewModel.error.value)
        assertEquals(emptyList<Dependency>(), stored(a).dependencies)
    }

    @Test
    fun `dates are saved immediately`() = runTest {
        val a = repo.addTask("A")
        val viewModel = viewModelFor(a)

        viewModel.setDueDate(TODAY.plusDays(2)).join()
        viewModel.setAvailableFrom(TODAY.plusDays(1)).join()

        assertEquals(TODAY.plusDays(2), stored(a).dueDate)
        assertEquals(TODAY.plusDays(1), stored(a).availableFrom)
    }

    @Test
    fun `delete removes the task and the state becomes NotFound`() = runTest {
        val a = repo.addTask("A")
        val viewModel = viewModelFor(a)
        viewModel.detailWhere { true }

        viewModel.delete().join()

        assertEquals(DetailState.NotFound, viewModel.state.first { it == DetailState.NotFound })
        assertTrue(repo.tasks.first().isEmpty())
    }
}
