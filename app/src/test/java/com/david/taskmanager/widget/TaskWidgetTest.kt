package com.david.taskmanager.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.GlanceId
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.david.taskmanager.core.AvailabilityEvaluator
import com.david.taskmanager.TaskManagerApplication
import com.david.taskmanager.core.TaskLists
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.david.taskmanager.data.TODAY
import com.david.taskmanager.data.clockOn
import com.david.taskmanager.launch.LaunchIntents
import com.david.taskmanager.ui.uiTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class TaskWidgetTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `widget items are the available tasks in list order`() {
        val second = uiTask("Second", position = 5, dueDate = TODAY.plusDays(1))
        val first = uiTask("First", position = 1)
        val locked = uiTask("Locked", position = 0, availableFrom = TODAY.plusDays(3))
        val done = uiTask("Done", position = -1, completedAt = Instant.parse("2026-11-30T10:00:00Z"))
        val lists = TaskLists.from(listOf(second, first, locked, done), AvailabilityEvaluator(clockOn(TODAY)))

        assertEquals(
            listOf(WidgetItem(first.id, "First", null), WidgetItem(second.id, "Second", TODAY.plusDays(1))),
            widgetItems(lists),
        )
    }

    @Test
    fun `rows show titles and due dates`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(300.dp, 200.dp))
        val item = WidgetItem(uiTask("Buy milk").id, "Buy milk", TODAY.plusDays(2))
        provideComposable { TaskWidgetContent(listOf(item), TODAY) }

        onNode(hasText("Buy milk")).assertExists()
        onNode(hasText("Due 3 Dec")).assertExists()
        onNode(hasText("Nothing to do right now")).assertDoesNotExist()
    }

    @Test
    fun `an empty list shows the empty state`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { TaskWidgetContent(emptyList(), TODAY) }

        onNode(hasText("Nothing to do right now")).assertExists()
    }

    @Test
    fun `each row has a clickable checkbox`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val item = WidgetItem(uiTask("Buy milk").id, "Buy milk", null)
        provideComposable { TaskWidgetContent(listOf(item), TODAY) }

        // Glance wraps a checkbox's action in an internal CompoundButtonAction, so the callback itself is
        // checked through completeParameters + CompleteTaskAction below.
        onNode(hasContentDescription("Mark Buy milk complete")).assert(hasClickAction())
    }

    @Test
    fun `the complete action completes the task named by its parameters`() = runTest {
        val repository = (context.applicationContext as TaskManagerApplication).container.repository
        val id = repository.addTask("Buy milk")

        CompleteTaskAction().onAction(context, object : GlanceId {}, completeParameters(id))

        assertEquals(TaskStatus.DONE, repository.tasks.first().single { it.id == id }.status)
    }

    @Test
    fun `the complete action ignores missing or malformed ids`() = runTest {
        CompleteTaskAction().onAction(context, object : GlanceId {}, actionParametersOf())
        CompleteTaskAction().onAction(
            context,
            object : GlanceId {},
            actionParametersOf(CompleteTaskAction.TaskIdKey to "nope"),
        )
    }

    @Test
    fun `item ids stay outside the range Glance reserves`() {
        repeat(10_000) {
            val itemId = widgetItemId(TaskId.random())
            assertTrue(itemId in 0..0x3FFF_FFFF_FFFF_FFFFL)
        }
    }

    @Test
    fun `titles open the task and plus opens quick add`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val item = WidgetItem(uiTask("Buy milk").id, "Buy milk", null)
        provideComposable { TaskWidgetContent(listOf(item), TODAY) }

        onNode(hasStartActivityClickAction(LaunchIntents.openTask(context, item.id))).assertExists()
        onNode(hasStartActivityClickAction(LaunchIntents.quickAdd(context))).assertExists()
        onNode(hasStartActivityClickAction(LaunchIntents.openApp(context))).assertExists()
    }
}
