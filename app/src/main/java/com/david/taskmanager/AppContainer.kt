package com.david.taskmanager

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.david.taskmanager.widget.TaskWidget
import kotlinx.coroutines.CancellationException
import com.david.taskmanager.core.SystemZoneClock
import com.david.taskmanager.data.TaskDatabase
import com.david.taskmanager.data.TaskRepository
import com.david.taskmanager.google.GoogleTasksGateway
import com.david.taskmanager.google.HttpGoogleTasksGateway
import com.david.taskmanager.google.PlayServicesAuthorizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.LocalDate

/** Manual dependency injection: one instance per process, owned by [TaskManagerApplication]. */
class AppContainer(context: Context) {
    val clock: Clock = SystemZoneClock()

    /**
     * Scope for writes. Writes are launched here rather than in a ViewModel's scope so that leaving a screen
     * (which clears its ViewModel) never cancels a save that is still running.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val appContext: Context = context.applicationContext

    val database: TaskDatabase = TaskDatabase.create(context)

    /** Every committed change also refreshes the home-screen widget(s). */
    val repository: TaskRepository = TaskRepository(database, clock, onChange = ::updateWidgets)

    /** Created on first use, so that starting the app (or a test) never touches Google Play services. */
    val googleTasks: GoogleTasksGateway by lazy { HttpGoogleTasksGateway(PlayServicesAuthorizer(appContext)) }

    private suspend fun updateWidgets() {
        try {
            TaskWidget().updateAll(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A widget refresh failing must never fail the write that triggered it.
            Log.w("AppContainer", "Widget update failed", e)
        }
    }

    private val _today = MutableStateFlow(LocalDate.now(clock))

    /** The local date the UI evaluates availability for. Updated by [refreshDay]. */
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    /** Promotes tasks that unlocked since the last refresh, then moves the UI to the new date. */
    suspend fun refreshDay() {
        repository.refreshForToday()
        _today.value = LocalDate.now(clock)
    }
}

class TaskManagerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
