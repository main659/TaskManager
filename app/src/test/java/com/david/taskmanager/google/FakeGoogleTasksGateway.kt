package com.david.taskmanager.google

import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.RemoteTaskList

/** In-memory Google Tasks. Set [failure] to make the next calls throw it. */
class FakeGoogleTasksGateway(
    var lists: List<RemoteTaskList> = emptyList(),
    val tasks: MutableMap<String, List<RemoteTask>> = mutableMapOf(),
) : GoogleTasksGateway {

    var failure: Exception? = null
    var calls = 0
        private set

    override suspend fun taskLists(): List<RemoteTaskList> {
        calls++
        failure?.let { throw it }
        return lists
    }

    override suspend fun openTasks(listId: String): List<RemoteTask> {
        calls++
        failure?.let { throw it }
        return tasks[listId].orEmpty().filter { !it.completed }
    }
}
