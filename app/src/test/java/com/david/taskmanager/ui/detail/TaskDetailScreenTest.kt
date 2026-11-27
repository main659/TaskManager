package com.david.taskmanager.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.david.taskmanager.core.LockReason
import com.david.taskmanager.core.TaskState
import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.data.TODAY
import com.david.taskmanager.ui.uiTask
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var deleted = 0
    private val addedDependencies = mutableListOf<TaskId>()
    private val delays = mutableListOf<Pair<TaskId, Int>>()

    private fun show(detail: TaskDetail) {
        compose.setContent {
            TaskDetailScreen(
                detail = detail,
                text = TextFields(detail.task.title, detail.task.notes.orEmpty()),
                error = null,
                onTitleChange = {},
                onNotesChange = {},
                onDueDateChange = {},
                onAvailableFromChange = {},
                onAddDependency = { addedDependencies += it },
                onRemoveDependency = {},
                onSetDelay = { id, days -> delays += id to days },
                onDelete = { deleted++ },
                onBack = {},
            )
        }
    }

    private fun detail(
        task: Task,
        state: TaskState = TaskState.Available,
        dependencies: List<DependencyItem> = emptyList(),
        candidates: List<Task> = emptyList(),
        dependents: List<Task> = emptyList(),
    ) = TaskDetail(task, state, TODAY, dependencies, candidates, dependents)

    @Test
    fun `delete asks for confirmation and mentions dependents`() {
        show(detail(uiTask("A"), dependents = listOf(uiTask("B"), uiTask("C"))))

        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("This can't be undone. 2 tasks wait on this one and will stop waiting.").assertIsDisplayed()
        assertEquals(0, deleted)

        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun `adding a dependency picks from the candidates`() {
        val candidate = uiTask("Buy paint")
        show(detail(uiTask("Paint wall"), candidates = listOf(candidate)))

        compose.onNodeWithText("Add task to wait on").performScrollTo().performClick()
        compose.onNodeWithText("Buy paint").performClick()

        assertEquals(listOf(candidate.id), addedDependencies)
    }

    @Test
    fun `dependencies show their delay and can be changed`() {
        val dep = uiTask("Plant")
        val task = uiTask("Water", dependencies = listOf(Dependency(dep.id, 2)))
        show(
            detail(
                task,
                state = TaskState.Locked(listOf(LockReason.WaitingOn(dep.id, "Plant", 2)), null),
                dependencies = listOf(DependencyItem(dep, 2)),
            ),
        )

        compose.onNodeWithText("+2 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Waiting on: Plant, then 2 days").assertIsDisplayed()
        compose.onNodeWithContentDescription("More days").performClick()
        compose.onNodeWithContentDescription("Fewer days").performClick()

        assertEquals(listOf(dep.id to 3, dep.id to 1), delays)
    }
}
