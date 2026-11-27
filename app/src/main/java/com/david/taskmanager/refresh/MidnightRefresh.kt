package com.david.taskmanager.refresh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.david.taskmanager.TaskManagerApplication
import com.david.taskmanager.core.durationUntilNextMidnight
import com.david.taskmanager.widget.TaskWidget
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Keeps one `midnight-refresh` job queued for the next local midnight (+ [MARGIN]). Each run queues the next
 * one, so the chain follows local midnight through DST changes.
 */
object MidnightScheduler {
    const val WORK_NAME = "midnight-refresh"
    val MARGIN: Duration = Duration.ofMinutes(1)

    /** On app start: queue the next run unless one is already pending or running. */
    fun ensureScheduled(context: Context, clock: Clock) = enqueue(context, delayFor(clock), ExistingWorkPolicy.KEEP)

    /** From the worker itself: queue the next run after the current one finishes. */
    fun scheduleNext(context: Context, clock: Clock) =
        enqueue(context, delayFor(clock), ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** After the clock or time zone changed: refresh now; that run then queues the next midnight. */
    fun refreshNow(context: Context) = enqueue(context, Duration.ZERO, ExistingWorkPolicy.REPLACE)

    fun delayFor(clock: Clock): Duration = durationUntilNextMidnight(clock) + MARGIN

    private fun enqueue(context: Context, delay: Duration, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<MidnightRefreshWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}

/** Promotes tasks that unlocked overnight, moves the app to the new date, updates the widget. */
class MidnightRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TaskManagerApplication).container
        return try {
            container.refreshDay()
            TaskWidget().updateAll(applicationContext)
            // Only queue the next run on success: a retried run queues it when it finally succeeds, so the
            // chain never gets two pending runs.
            MidnightScheduler.scheduleNext(applicationContext, container.clock)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** The midnight the queued job aims at moves when the user changes the time or time zone. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) {
            MidnightScheduler.refreshNow(context)
        }
    }
}
