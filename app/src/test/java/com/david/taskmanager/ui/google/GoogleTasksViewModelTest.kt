package com.david.taskmanager.ui.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.RemoteTaskList
import com.david.taskmanager.data.ImportResult
import com.david.taskmanager.data.TODAY
import com.david.taskmanager.data.TaskDatabase
import com.david.taskmanager.data.TaskRepository
import com.david.taskmanager.data.clockOn
import com.david.taskmanager.data.inMemoryDatabase
import com.david.taskmanager.google.ConsentRequiredException
import com.david.taskmanager.google.FakeGoogleTasksGateway
import com.david.taskmanager.google.GoogleApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoogleTasksViewModelTest {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val list = RemoteTaskList("L1", "My Tasks")
    private val gateway = FakeGoogleTasksGateway(
        lists = listOf(list, RemoteTaskList("L2", "Work")),
        tasks = mutableMapOf("L1" to listOf(RemoteTask("g1", "Buy milk", null, null, Instant.parse("2026-11-27T09:00:00Z")))),
    )
    private lateinit var db: TaskDatabase
    private lateinit var repo: TaskRepository
    private lateinit var viewModel: GoogleTasksViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDatabase()
        repo = TaskRepository(db, clockOn(TODAY))
        viewModel = GoogleTasksViewModel(repo, gateway, appScope)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    // No withTimeout: under runTest it counts virtual time, which skips ahead while Room works on a real thread.
    private suspend inline fun <reified S : GoogleTasksUiState> awaitState(): S =
        viewModel.state.first { it is S } as S

    private fun pendingIntent(): PendingIntent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
    }

    @Test
    fun `starts not connected`() = runTest {
        assertEquals(GoogleTasksUiState.NotConnected(), awaitState<GoogleTasksUiState.NotConnected>())
    }

    @Test
    fun `connect then choose a list imports it and shows the result`() = runTest {
        viewModel.connect().join()
        assertEquals(gateway.lists, awaitState<GoogleTasksUiState.ChooseList>().lists)

        viewModel.chooseList(list).join()

        assertEquals(
            GoogleTasksUiState.Connected("My Tasks", ImportResult(imported = 1, matched = 0)),
            awaitState<GoogleTasksUiState.Connected>(),
        )
        assertEquals(listOf("Buy milk"), repo.tasks.first().map { it.title })
    }

    @Test
    fun `an existing connection is shown on open`() = runTest {
        repo.importFromGoogle(list, emptyList())

        assertEquals(GoogleTasksUiState.Connected("My Tasks"), awaitState<GoogleTasksUiState.Connected>())
    }

    @Test
    fun `network failures are reported and connect can be retried`() = runTest {
        gateway.failure = IOException("offline")
        viewModel.connect().join()
        assertEquals(GoogleError.NETWORK, awaitState<GoogleTasksUiState.NotConnected>().error)

        gateway.failure = null
        viewModel.connect().join()
        awaitState<GoogleTasksUiState.ChooseList>()
    }

    @Test
    fun `an import failure keeps the list choice with an error`() = runTest {
        viewModel.connect().join()
        gateway.failure = GoogleApiException(500, "boom")

        viewModel.chooseList(list).join()

        val state = awaitState<GoogleTasksUiState.ChooseList>()
        assertEquals(GoogleError.OTHER, state.error)
        assertNull(state.importingListId)
        assertNull(repo.googleConnection.first())
    }

    @Test
    fun `consent is requested, then granting it loads the lists`() = runTest {
        gateway.failure = ConsentRequiredException(pendingIntent())
        viewModel.connect().join()
        assertNotNull(viewModel.consentRequest.value)
        assertEquals(true, awaitState<GoogleTasksUiState.NotConnected>().working)

        gateway.failure = null
        viewModel.onConsentResult(granted = true)

        assertNull(viewModel.consentRequest.value)
        awaitState<GoogleTasksUiState.ChooseList>()
    }

    @Test
    fun `declining consent is reported`() = runTest {
        gateway.failure = ConsentRequiredException(pendingIntent())
        viewModel.connect().join()

        viewModel.onConsentResult(granted = false)

        assertEquals(GoogleError.CONSENT_DENIED, awaitState<GoogleTasksUiState.NotConnected>().error)
    }

    @Test
    fun `disconnect returns to not connected and keeps the tasks`() = runTest {
        viewModel.connect().join()
        viewModel.chooseList(list).join()
        awaitState<GoogleTasksUiState.Connected>()

        viewModel.disconnect().join()

        awaitState<GoogleTasksUiState.NotConnected>()
        assertNull(repo.googleConnection.first())
        assertEquals(listOf("Buy milk"), repo.tasks.first().map { it.title })
    }
}
