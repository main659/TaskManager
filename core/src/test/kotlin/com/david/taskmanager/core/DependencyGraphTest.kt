package com.david.taskmanager.core

import com.david.taskmanager.core.model.Dependency
import com.david.taskmanager.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyGraphTest {

    @Test
    fun `self dependency is rejected`() {
        val a = openTask("Alpha")
        val e = assertThrows(DependencyException.SelfDependency::class.java) {
            DependencyGraph(tasksOf(a)).validate(a.id, listOf(Dependency(a.id)))
        }
        assertTrue(e.message!!.contains("Alpha"))
    }

    @Test
    fun `unknown dependency is rejected`() {
        val a = openTask("A")
        val missing = TaskId.random()
        val e = assertThrows(DependencyException.UnknownTask::class.java) {
            DependencyGraph(tasksOf(a)).validate(a.id, listOf(Dependency(missing)))
        }
        assertEquals(missing, e.taskId)
    }

    @Test
    fun `duplicate dependency is rejected`() {
        val a = openTask("A")
        val b = openTask("B")
        assertThrows(DependencyException.DuplicateDependency::class.java) {
            DependencyGraph(tasksOf(a, b)).validate(a.id, listOf(Dependency(b.id), Dependency(b.id, 2)))
        }
    }

    @Test
    fun `direct cycle is rejected with its path`() {
        val a = openTask("A")
        val b = openTask("B").dependsOn(a)
        val e = assertThrows(DependencyException.Cycle::class.java) {
            DependencyGraph(tasksOf(a, b)).validate(a.id, listOf(Dependency(b.id)))
        }
        assertEquals(listOf(a.id, b.id, a.id), e.path)
        assertEquals("Circular dependency: A → B → A", e.message)
    }

    @Test
    fun `indirect cycle is rejected with its path`() {
        val a = openTask("A")
        val c = openTask("C").dependsOn(a)
        val b = openTask("B").dependsOn(c)
        val e = assertThrows(DependencyException.Cycle::class.java) {
            DependencyGraph(tasksOf(a, b, c)).validate(a.id, listOf(Dependency(b.id)))
        }
        assertEquals(listOf(a.id, b.id, c.id, a.id), e.path)
        assertEquals("Circular dependency: A → B → C → A", e.message)
    }

    @Test
    fun `diamond without cycle is accepted`() {
        val d = openTask("D")
        val b = openTask("B").dependsOn(d)
        val c = openTask("C").dependsOn(d)
        val a = openTask("A")
        DependencyGraph(tasksOf(a, b, c, d)).validate(a.id, listOf(Dependency(b.id), Dependency(c.id)))
    }

    @Test
    fun `replacing dependencies ignores the task's current ones`() {
        // A currently waits on B; giving A the dependency C instead is fine even though C waits on B.
        val b = openTask("B")
        val c = openTask("C").dependsOn(b)
        val a = openTask("A").dependsOn(b)
        DependencyGraph(tasksOf(a, b, c)).validate(a.id, listOf(Dependency(c.id)))
    }

    @Test
    fun `empty dependency list is valid`() {
        val a = openTask("A")
        DependencyGraph(tasksOf(a)).validate(a.id, emptyList())
    }

    @Test
    fun `dependents are the tasks that wait on the given task`() {
        val a = openTask("A")
        val b = openTask("B").dependsOn(a)
        val c = openTask("C").dependsOn(a, 2)
        val d = openTask("D").dependsOn(b)
        val graph = DependencyGraph(tasksOf(a, b, c, d))
        assertEquals(setOf(b.id, c.id), graph.dependentsOf(a.id))
        assertEquals(setOf(d.id), graph.dependentsOf(b.id))
        assertEquals(emptySet<TaskId>(), graph.dependentsOf(d.id))
    }
}
