package com.david.taskmanager.ui.lists

import com.david.taskmanager.core.MoveDirection
import com.david.taskmanager.data.TODAY
import com.david.taskmanager.data.TaskDatabase
import com.david.taskmanager.data.TaskRepository
import com.david.taskmanager.data.clockOn
import com.david.taskmanager.data.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TaskListsViewModelTest {

    private val clock = clockOn(TODAY)
    private val today = MutableStateFlow(TODAY)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: TaskDatabase
    private lateinit var repo: TaskRepository
    private lateinit var viewModel: TaskListsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDatabase()
        repo = TaskRepository(db, clock)
        viewModel = TaskListsViewModel(repo, clock, today, appScope)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun stateWhere(predicate: (ListsUiState) -> Boolean) =
        viewModel.state.filterNotNull().first(predicate)

    @Test
    fun `add trims the title and puts the task on top of Available`() = runTest {
        viewModel.add("First")!!.join()
        viewModel.add("  Second  ")!!.join()

        val state = stateWhere { it.lists.available.size == 2 }
        assertEquals(listOf("Second", "First"), state.lists.available.map { it.title })
    }

    @Test
    fun `blank titles are ignored`() = runTest {
        assertNull(viewModel.add("   "))
    }

    @Test
    fun `complete moves a task to Done and uncomplete brings it back on top`() = runTest {
        val a = repo.addTask("A")
        repo.addTask("B")

        viewModel.complete(a).join()
        assertEquals(listOf(a), stateWhere { it.lists.done.isNotEmpty() }.lists.done.map { it.id })

        viewModel.uncomplete(a).join()
        val state = stateWhere { it.lists.done.isEmpty() }
        assertEquals(a, state.lists.available.first().id)
    }

    @Test
    fun `move reorders the available list`() = runTest {
        val bottom = repo.addTask("Bottom")
        val top = repo.addTask("Top")

        viewModel.move(bottom, MoveDirection.UP).join()

        val state = stateWhere { it.lists.available.firstOrNull()?.id == bottom }
        assertEquals(listOf(bottom, top), state.lists.available.map { it.id })
    }

    @Test
    fun `a new day re-evaluates the lists`() = runTest {
        val a = repo.addTask("A", availableFrom = TODAY.plusDays(1))
        assertEquals(listOf(a), stateWhere { it.lists.locked.isNotEmpty() }.lists.locked.map { it.task.id })

        today.value = TODAY.plusDays(1)

        val state = stateWhere { it.today == TODAY.plusDays(1) }
        assertEquals(listOf(a), state.lists.available.map { it.id })
    }
}
