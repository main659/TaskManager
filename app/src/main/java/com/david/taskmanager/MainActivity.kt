package com.david.taskmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.david.taskmanager.core.durationUntilNextMidnight
import com.david.taskmanager.ui.TaskManagerApp
import com.david.taskmanager.ui.theme.TaskManagerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TaskManagerApplication).container

        // While the app is visible: refresh now (covers returning to the app on a later day), then again at
        // each local midnight. When the app is not visible, the slice 4 worker takes over.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    container.refreshDay()
                    delay(durationUntilNextMidnight(container.clock).toMillis() + MIDNIGHT_MARGIN_MS)
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            TaskManagerTheme {
                TaskManagerApp(container)
            }
        }
    }

    private companion object {
        const val MIDNIGHT_MARGIN_MS = 1_000L
    }
}
