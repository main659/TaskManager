package com.david.taskmanager.launch

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.david.taskmanager.MainActivity
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.ui.Screen
import java.util.UUID

/** What the app should show when it is opened (from the launcher or the widget). */
data class LaunchState(val backStack: List<Screen>, val quickAdd: Boolean = false)

/**
 * Intents that open the app. They always start a fresh task (NEW_TASK | CLEAR_TASK), so MainActivity builds its
 * back stack from the intent and never has to merge it into an existing one.
 */
object LaunchIntents {
    const val ACTION_QUICK_ADD = "com.david.taskmanager.action.QUICK_ADD"
    const val ACTION_OPEN_TASK = "com.david.taskmanager.action.OPEN_TASK"
    const val EXTRA_TASK_ID = "com.david.taskmanager.extra.TASK_ID"

    fun openApp(context: Context): Intent = base(context)

    fun quickAdd(context: Context): Intent = base(context).setAction(ACTION_QUICK_ADD)

    /** The task id is also in the data URI so that each task's intent is distinct for PendingIntents. */
    fun openTask(context: Context, id: TaskId): Intent = base(context)
        .setAction(ACTION_OPEN_TASK)
        .setData("taskmanager://task/$id".toUri())
        .putExtra(EXTRA_TASK_ID, id.toString())

    private fun base(context: Context): Intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun stateFrom(intent: Intent?): LaunchState = stateFrom(intent?.action, intent?.getStringExtra(EXTRA_TASK_ID))

    /** Pure parsing, unit-tested. Unknown actions and malformed ids fall back to the main screen. */
    fun stateFrom(action: String?, taskId: String?): LaunchState = when (action) {
        ACTION_QUICK_ADD -> LaunchState(listOf(Screen.Main), quickAdd = true)
        ACTION_OPEN_TASK -> {
            val id = taskId?.let { runCatching { TaskId(UUID.fromString(it)) }.getOrNull() }
            if (id == null) LaunchState(listOf(Screen.Main)) else LaunchState(listOf(Screen.Main, Screen.Detail(id)))
        }
        else -> LaunchState(listOf(Screen.Main))
    }
}
