package com.david.taskmanager.launch

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val main = LaunchState(listOf(Screen.Main))

    @Test
    fun `a plain launch shows the main screen`() {
        assertEquals(main, LaunchIntents.stateFrom(null))
        assertEquals(main, LaunchIntents.stateFrom(LaunchIntents.openApp(context)))
        assertEquals(main, LaunchIntents.stateFrom(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `quick add opens the main screen with the sheet`() {
        assertEquals(LaunchState(listOf(Screen.Main), quickAdd = true), LaunchIntents.stateFrom(LaunchIntents.quickAdd(context)))
    }

    @Test
    fun `opening a task puts its detail on top of the main screen`() {
        val id = TaskId.random()
        assertEquals(
            LaunchState(listOf(Screen.Main, Screen.Detail(id))),
            LaunchIntents.stateFrom(LaunchIntents.openTask(context, id)),
        )
    }

    @Test
    fun `a missing or malformed task id falls back to the main screen`() {
        assertEquals(main, LaunchIntents.stateFrom(LaunchIntents.ACTION_OPEN_TASK, null))
        assertEquals(main, LaunchIntents.stateFrom(LaunchIntents.ACTION_OPEN_TASK, "not-a-uuid"))
    }

    @Test
    fun `launch intents always start a fresh task`() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        listOf(LaunchIntents.openApp(context), LaunchIntents.quickAdd(context), LaunchIntents.openTask(context, TaskId.random()))
            .forEach { assertTrue(it.flags and flags == flags) }
    }

    @Test
    fun `intents for different tasks are distinct`() {
        // PendingIntents compare intents with filterEquals (extras ignored); the data URI keeps them apart.
        val a = LaunchIntents.openTask(context, TaskId.random())
        val b = LaunchIntents.openTask(context, TaskId.random())
        assertTrue(!a.filterEquals(b))
        assertNotEquals(a.data, b.data)
    }
}
