package com.david.taskmanager.ui.lists

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.david.taskmanager.core.LockReason
import com.david.taskmanager.core.LockedTask
import com.david.taskmanager.core.MoveDirection
import com.david.taskmanager.core.TaskLists
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.data.TODAY
import com.david.taskmanager.ui.uiTask
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.david.taskmanager.ui.UI_TEST_SDK
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [UI_TEST_SDK])
class TaskListsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val completed = mutableListOf<TaskId>()
    private val uncompleted = mutableListOf<TaskId>()
    private val added = mutableListOf<String>()
    private val moved = mutableListOf<Pair<TaskId, MoveDirection>>()

    private fun show(lists: TaskLists) {
        compose.setContent {
            TaskListsScreen(
                state = ListsUiState(lists, TODAY),
                onAdd = { added += it },
                onComplete = { completed += it },
                onUncomplete = { uncompleted += it },
                onMove = { id, direction -> moved += id to direction },
                onOpenTask = {},
            )
        }
    }

    @Test
    fun `available tasks and tab counts are shown`() {
        val a = uiTask("Buy milk", dueDate = TODAY.plusDays(2))
        show(TaskLists(listOf(a), emptyList(), emptyList()))

        compose.onNodeWithText("Buy milk").assertIsDisplayed()
        compose.onNodeWithText("Due 3 Dec").assertIsDisplayed()
        compose.onNodeWithText("Available (1)").assertIsDisplayed()
        compose.onNodeWithText("Locked (0)").assertIsDisplayed()
    }

    @Test
    fun `checking a task completes it and Undo uncompletes it`() {
        val a = uiTask("Buy milk")
        show(TaskLists(listOf(a), emptyList(), emptyList()))

        compose.onNodeWithContentDescription("Mark complete").performClick()
        assertEquals(listOf(a.id), completed)

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf(a.id), uncompleted)
    }

    @Test
    fun `the overflow menu moves a task`() {
        val a = uiTask("A", position = 0)
        val b = uiTask("B", position = 1)
        show(TaskLists(listOf(a, b), emptyList(), emptyList()))

        compose.onAllNodesWithContentDescriptionAt("More options", 1).performClick()
        compose.onNodeWithText("Move up").performClick()

        assertEquals(listOf(b.id to MoveDirection.UP), moved)
    }

    @Test
    fun `locked tab shows reasons and the unlock date`() {
        val dep = uiTask("Buy paint")
        val waiting = uiTask("Paint wall")
        val dated = uiTask("File taxes")
        val unlock = TODAY.plusDays(4)
        show(
            TaskLists(
                available = listOf(dep),
                locked = listOf(
                    LockedTask(waiting, listOf(LockReason.WaitingOn(dep.id, "Buy paint", 0)), null),
                    LockedTask(dated, listOf(LockReason.DelayAfter(dep.id, "Plant", 2, unlock)), unlock),
                ),
                done = emptyList(),
            ),
        )

        compose.onNodeWithText("Locked (2)").performClick()

        compose.onNodeWithText("Waiting on: Buy paint").assertIsDisplayed()
        compose.onNodeWithText("Available 2 days after Plant (5 Dec)").assertIsDisplayed()
        compose.onNodeWithText("Unlocks 5 Dec").assertIsDisplayed()
    }

    @Test
    fun `done tab unchecks a task`() {
        val done = uiTask("Done one", completedAt = Instant.parse("2026-11-30T10:00:00Z"))
        show(TaskLists(emptyList(), emptyList(), listOf(done)))

        compose.onNodeWithText("Done (1)").performClick()
        compose.onNodeWithContentDescription("Mark not complete").performClick()

        assertEquals(listOf(done.id), uncompleted)
    }

    @Test
    fun `quick add submits on Enter and clears the field`() {
        compose.setContent { QuickAddField(onAdd = { added += it }) }

        compose.onNodeWithText("New task").performTextInput("Call mum")
        compose.onNodeWithText("Call mum").performImeAction()

        assertEquals(listOf("Call mum"), added)
        compose.onNodeWithText("New task").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionAt(
        description: String,
        index: Int,
    ) = onAllNodes(androidx.compose.ui.test.hasContentDescription(description))[index]
}
