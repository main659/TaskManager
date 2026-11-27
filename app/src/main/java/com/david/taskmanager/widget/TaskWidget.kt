package com.david.taskmanager.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.david.taskmanager.R
import com.david.taskmanager.TaskManagerApplication
import com.david.taskmanager.core.AvailabilityEvaluator
import com.david.taskmanager.core.TaskLists
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.launch.LaunchIntents
import com.david.taskmanager.ui.common.formatDate
import java.time.LocalDate
import java.util.UUID

/** One row of the widget: an available task, in the same order as the app's Available list. */
data class WidgetItem(val id: TaskId, val title: String, val dueDate: LocalDate?)

fun widgetItems(lists: TaskLists): List<WidgetItem> = lists.available.map { WidgetItem(it.id, it.title, it.dueDate) }

/**
 * Stable LazyColumn item id for a task. Glance reserves ids below -2^62, so the UUID is folded into
 * [0, 2^62). Collisions only affect list animation, never which task an action targets.
 */
fun widgetItemId(id: TaskId): Long =
    (id.value.mostSignificantBits xor id.value.leastSignificantBits) and 0x3FFF_FFFF_FFFF_FFFFL

/** Parameters for [CompleteTaskAction] that identify [id]. */
fun completeParameters(id: TaskId): ActionParameters = actionParametersOf(CompleteTaskAction.TaskIdKey to id.toString())

class TaskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as TaskManagerApplication).container
        val evaluator = AvailabilityEvaluator(container.clock)
        provideContent {
            val tasks by container.repository.tasks.collectAsState(initial = null)
            val today by container.today.collectAsState()
            GlanceTheme {
                TaskWidgetContent(tasks?.let { widgetItems(TaskLists.from(it, evaluator, today)) }, today)
            }
        }
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TaskWidget()
}

/** Completes a task from the widget's checkbox. */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey]?.let { runCatching { TaskId(UUID.fromString(it)) }.getOrNull() } ?: return
        val container = (context.applicationContext as TaskManagerApplication).container
        // The repository's onChange refreshes the widget once the write has committed.
        container.repository.complete(id)
    }

    companion object {
        val TaskIdKey = ActionParameters.Key<String>("taskId")
    }
}

/** The widget layout, without data loading, so it can be tested on its own. Null [items] = still loading. */
@Composable
fun TaskWidgetContent(items: List<WidgetItem>?, today: LocalDate) {
    val context = LocalContext.current
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.app_name),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(LaunchIntents.openApp(context))),
            )
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_add),
                contentDescription = context.getString(R.string.add_task),
                onClick = actionStartActivity(LaunchIntents.quickAdd(context)),
            )
        }
        when {
            items == null -> Unit
            items.isEmpty() -> Text(
                context.getString(R.string.widget_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.padding(top = 8.dp),
            )
            else -> LazyColumn {
                items(items, itemId = { widgetItemId(it.id) }) { item ->
                    WidgetRow(item, today)
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(item: WidgetItem, today: LocalDate) {
    val context = LocalContext.current
    val markComplete = context.getString(R.string.widget_mark_complete, item.title)
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<CompleteTaskAction>(completeParameters(item.id)),
            modifier = GlanceModifier.semantics { contentDescription = markComplete },
        )
        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(LaunchIntents.openTask(context, item.id)))) {
            Text(item.title, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
            item.dueDate?.let {
                Text(
                    context.getString(R.string.due_on, formatDate(it, today)),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
        }
    }
}
