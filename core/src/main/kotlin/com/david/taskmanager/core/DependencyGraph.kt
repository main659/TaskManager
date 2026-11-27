package com.david.taskmanager.core

import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId

sealed class DependencyException(message: String) : IllegalArgumentException(message) {
    class SelfDependency(val taskId: TaskId, title: String) :
        DependencyException("\"$title\" cannot depend on itself")

    class UnknownTask(val taskId: TaskId) :
        DependencyException("Unknown task $taskId")

    class DuplicateDependency(val taskId: TaskId, title: String) :
        DependencyException("\"$title\" is listed more than once")

    /** [path] starts and ends with the task being edited; each task waits on the next one. */
    class Cycle(val path: List<TaskId>, titles: List<String>) :
        DependencyException("Circular dependency: ${titles.joinToString(" → ")}")
}

/** Read-only view of the dependency edges between [tasks]. An edge A → B means "A waits on B". */
class DependencyGraph(private val tasks: Map<TaskId, Task>) {

    private val dependents: Map<TaskId, Set<TaskId>> by lazy {
        buildMap<TaskId, MutableSet<TaskId>> {
            for (task in tasks.values) {
                for (dependency in task.dependencies) getOrPut(dependency.taskId) { mutableSetOf() }.add(task.id)
            }
        }
    }

    /** Tasks that directly wait on [id]. */
    fun dependentsOf(id: TaskId): Set<TaskId> = dependents[id].orEmpty()

    /**
     * Checks that [taskId] may have exactly [newDependencies] (replacing its current ones).
     *
     * @throws DependencyException
     */
    fun validate(taskId: TaskId, newDependencies: List<Dependency>) {
        val seen = mutableSetOf<TaskId>()
        for (dependency in newDependencies) {
            val target = dependency.taskId
            if (target == taskId) throw DependencyException.SelfDependency(taskId, titleOf(taskId))
            if (target !in tasks) throw DependencyException.UnknownTask(target)
            if (!seen.add(target)) throw DependencyException.DuplicateDependency(target, titleOf(target))
        }
        for (dependency in newDependencies) {
            val pathBack = pathTo(from = dependency.taskId, to = taskId) ?: continue
            val path = listOf(taskId) + pathBack
            throw DependencyException.Cycle(path, path.map(::titleOf))
        }
    }

    /** Depth-first search along "waits on" edges; returns the path from [from] to [to] inclusive, or null. */
    private fun pathTo(from: TaskId, to: TaskId): List<TaskId>? {
        val visited = mutableSetOf<TaskId>()
        fun visit(id: TaskId): List<TaskId>? {
            if (id == to) return listOf(id)
            if (!visited.add(id)) return null
            for (dependency in tasks[id]?.dependencies.orEmpty()) {
                visit(dependency.taskId)?.let { return listOf(id) + it }
            }
            return null
        }
        return visit(from)
    }

    private fun titleOf(id: TaskId): String = tasks[id]?.title ?: id.toString()
}
