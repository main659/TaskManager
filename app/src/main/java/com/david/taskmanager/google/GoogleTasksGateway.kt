package com.david.taskmanager.google

import android.app.PendingIntent
import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.RemoteTaskList
import java.io.IOException

/** Access to Google Tasks. The real one is [HttpGoogleTasksGateway]; tests use a fake. */
interface GoogleTasksGateway {
    suspend fun taskLists(): List<RemoteTaskList>

    /** The open tasks of a list, in the order Google Tasks shows them (subtasks right after their parent). */
    suspend fun openTasks(listId: String): List<RemoteTask>
}

/** Provides OAuth access tokens for the Google Tasks scope. */
interface GoogleAuthorizer {
    /**
     * @throws ConsentRequiredException if the user must pick an account or grant access first.
     */
    suspend fun accessToken(): String

    /** Drops a token Google rejected, so that the next [accessToken] fetches a fresh one. */
    suspend fun invalidate(token: String)
}

/** The user has to act (choose an account, consent) before the app may call Google; show [pendingIntent]. */
class ConsentRequiredException(val pendingIntent: PendingIntent) : Exception("Google authorization required")

/** Google answered with an error status. */
class GoogleApiException(val status: Int, message: String) : IOException("Google Tasks API error $status: $message")
