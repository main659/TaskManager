package com.david.taskmanager.google

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Play services authorization for the Tasks scope. Google identifies the app by its package name and
 * signing certificate (the Android OAuth client in the Cloud project), so no client id is needed here.
 * Once the user has granted access, [accessToken] works without UI, including from background workers.
 */
class PlayServicesAuthorizer(context: Context) : GoogleAuthorizer {

    private val client: AuthorizationClient = Identity.getAuthorizationClient(context.applicationContext)

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(TASKS_SCOPE)))
        .build()

    override suspend fun accessToken(): String {
        val result = client.authorize(request).await()
        if (result.hasResolution()) throw ConsentRequiredException(checkNotNull(result.pendingIntent))
        return checkNotNull(result.accessToken) { "Authorization returned no access token" }
    }

    override suspend fun invalidate(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    companion object {
        const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"
    }
}

/** Waits for a Play services [Task] (avoids adding kotlinx-coroutines-play-services for one function). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
