package com.david.taskmanager.core.sync

import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** A task as it exists in Google Tasks. Google due dates are date-only. */
data class RemoteTask(
    val id: String,
    val title: String,
    val notes: String?,
    val dueDate: LocalDate?,
    val updated: Instant,
    val completed: Boolean = false,
)

/** A Google Tasks list. */
data class RemoteTaskList(val id: String, val title: String)

/**
 * What the one-time import does: create a local task for each of [creates] (in this order, top first), and
 * link each of [matches] to the existing local task with the same title instead of creating a duplicate.
 */
data class ImportPlan(val creates: List<RemoteTask>, val matches: List<Match>) {
    data class Match(val taskId: TaskId, val remote: RemoteTask)
}

/** Title given to Google tasks that have none (local tasks must have a title). */
const val UNTITLED = "Untitled"

fun importTitle(remote: RemoteTask): String = remote.title.trim().ifEmpty { UNTITLED }

/**
 * Decides what importing [remote] (in Google's order) does to the local tasks.
 *
 * - Completed remote tasks are ignored.
 * - Remote tasks that are already linked ([linkedRemoteIds]) are skipped, so importing again creates nothing.
 *   This includes links whose local task was deleted: the Google copy is then due for deletion, not re-import.
 * - A remote task whose title matches (trimmed, ignoring case) an OPEN local task that is not linked yet
 *   ([linkedTaskIds]) is matched to it; each local task is matched at most once, the topmost first.
 * - Everything else is created.
 */
fun planImport(
    local: Collection<Task>,
    linkedTaskIds: Set<TaskId>,
    linkedRemoteIds: Set<String>,
    remote: List<RemoteTask>,
): ImportPlan {
    val candidates = local
        .filter { it.status == TaskStatus.OPEN && it.id !in linkedTaskIds }
        .sortedBy { it.position }
        .toMutableList()
    val seen = mutableSetOf<String>()
    val creates = mutableListOf<RemoteTask>()
    val matches = mutableListOf<ImportPlan.Match>()
    for (task in remote) {
        if (task.completed || task.id in linkedRemoteIds || !seen.add(task.id)) continue
        val key = matchKey(task.title)
        val match = if (key.isEmpty()) null else candidates.firstOrNull { matchKey(it.title) == key }
        if (match != null) {
            candidates.remove(match)
            matches += ImportPlan.Match(match.id, task)
        } else {
            creates += task
        }
    }
    return ImportPlan(creates, matches)
}

private fun matchKey(title: String): String = title.trim().lowercase(Locale.ROOT)
