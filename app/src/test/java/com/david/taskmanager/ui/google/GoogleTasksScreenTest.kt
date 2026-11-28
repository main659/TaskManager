package com.david.taskmanager.ui.google

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.david.taskmanager.core.TaskLists
import com.david.taskmanager.core.sync.RemoteTaskList
import com.david.taskmanager.data.ImportResult
import com.david.taskmanager.data.TODAY
import com.david.taskmanager.ui.lists.ListsUiState
import com.david.taskmanager.ui.lists.TaskListsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleTasksScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(state: GoogleTasksUiState) {
        compose.setContent {
            GoogleTasksScreen(
                state = state,
                onBack = { events += "back" },
                onConnect = { events += "connect" },
                onChooseList = { events += "choose ${it.id}" },
                onDisconnect = { events += "disconnect" },
            )
        }
    }

    @Test
    fun `not connected offers to connect and shows errors`() {
        show(GoogleTasksUiState.NotConnected(error = GoogleError.NETWORK))

        compose.onNodeWithText("Couldn't reach Google. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Connect Google account").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("connect", "back"), events)
    }

    @Test
    fun `tapping a list chooses it`() {
        show(GoogleTasksUiState.ChooseList(listOf(RemoteTaskList("L1", "My Tasks"), RemoteTaskList("L2", "Work"))))
        compose.onNodeWithText("Work").performClick()
        assertEquals(listOf("choose L2"), events)
    }

    @Test
    fun `while importing, the list shows progress and choices are disabled`() {
        show(GoogleTasksUiState.ChooseList(listOf(RemoteTaskList("L1", "My Tasks")), importingListId = "L1"))

        compose.onNodeWithText("Importing…").assertIsDisplayed()
        compose.onNodeWithText("My Tasks").performClick()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `connected shows the list, the import result and disconnect`() {
        show(GoogleTasksUiState.Connected("My Tasks", ImportResult(imported = 3, matched = 1)))

        compose.onNodeWithText("Connected to: My Tasks").assertIsDisplayed()
        compose.onNodeWithText("Imported 3 tasks.").assertIsDisplayed()
        compose.onNodeWithText("1 task was already in the app and was linked.").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals(listOf("disconnect"), events)
    }

    @Test
    fun `the main screen's menu opens Google Tasks`() {
        compose.setContent {
            TaskListsScreen(
                state = ListsUiState(TaskLists(emptyList(), emptyList(), emptyList()), TODAY),
                onAdd = {},
                onComplete = {},
                onUncomplete = {},
                onMove = { _, _ -> },
                onOpenTask = {},
                onOpenGoogle = { events += "google" },
            )
        }

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Google Tasks").performClick()

        assertEquals(listOf("google"), events)
    }
}
