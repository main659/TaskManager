package com.david.taskmanager.refresh

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.david.taskmanager.data.clockAt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MidnightRefreshTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workManager get() = WorkManager.getInstance(context)

    @Before
    fun setUp() {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun all(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(MidnightScheduler.WORK_NAME).get()

    private fun pending(): List<WorkInfo> = all().filter { !it.state.isFinished }

    /**
     * CoroutineWorker runs on real background threads, not the test executor; wait for a run to finish so it
     * cannot touch the next test's WorkManager.
     */
    private fun awaitFinished(id: UUID): WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val info = workManager.getWorkInfoById(id).get()!!
            if (info.state.isFinished || System.currentTimeMillis() > deadline) return info
            Thread.sleep(20)
        }
    }

    @Test
    fun `the delay aims at the next local midnight plus a margin`() {
        assertEquals(Duration.ofMinutes(91), MidnightScheduler.delayFor(clockAt(LocalDateTime.of(2026, 12, 1, 22, 30))))
    }

    @Test
    fun `ensureScheduled queues one delayed run for the next midnight`() {
        MidnightScheduler.ensureScheduled(context, clockAt(LocalDateTime.of(2026, 12, 1, 22, 30)))

        val run = pending().single()
        assertEquals(WorkInfo.State.ENQUEUED, run.state)
        assertEquals(Duration.ofMinutes(91).toMillis(), run.initialDelayMillis)
    }

    @Test
    fun `ensureScheduled keeps an existing pending run`() {
        MidnightScheduler.ensureScheduled(context, clockAt(LocalDateTime.of(2026, 12, 1, 22, 30)))
        val first = pending().single().id

        MidnightScheduler.ensureScheduled(context, clockAt(LocalDateTime.of(2026, 12, 1, 23, 0)))

        assertEquals(first, pending().single().id)
    }

    @Test
    fun `refreshNow replaces the pending run with an immediate one that queues the next midnight`() {
        MidnightScheduler.ensureScheduled(context, clockAt(LocalDateTime.of(2026, 12, 1, 22, 30)))
        val old = pending().single().id

        MidnightScheduler.refreshNow(context)

        // REPLACE cancels the old run and drops it from the unique name (it may be pruned entirely).
        val oldInfo = workManager.getWorkInfoById(old).get()
        assertTrue(oldInfo == null || oldInfo.state == WorkInfo.State.CANCELLED)
        val immediate = all().single { it.id != old && it.initialDelayMillis == 0L }
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(immediate.id).state)
        // The immediate run queued the next midnight run.
        val next = pending().single()
        assertTrue(next.initialDelayMillis > 0)
    }

    @Test
    fun `the worker refreshes and queues the next midnight`() = runTest {
        val worker = TestListenableWorkerBuilder<MidnightRefreshWorker>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val next = pending().single()
        assertEquals(WorkInfo.State.ENQUEUED, next.state)
        // Real clock here: somewhere within the next day (25 h covers a DST day), plus the margin.
        assertTrue(next.initialDelayMillis in 1..Duration.ofHours(25).plus(MidnightScheduler.MARGIN).toMillis())
    }
}
