package com.david.taskmanager.google

import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.RemoteTaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** A minimal HTTP GET, so that the gateway can be tested with recorded responses. */
fun interface HttpTransport {
    suspend fun get(url: String, accessToken: String): HttpResponse
}

data class HttpResponse(val status: Int, val body: String)

/** [HttpTransport] over [HttpURLConnection]. */
object UrlConnectionTransport : HttpTransport {
    override suspend fun get(url: String, accessToken: String): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 20_000
}

/**
 * Google Tasks REST API v1 (https://developers.google.com/workspace/tasks/reference/rest), read-only for now.
 * A 401 means the cached token expired or was revoked: it is invalidated and the request retried once.
 */
class HttpGoogleTasksGateway(
    private val authorizer: GoogleAuthorizer,
    private val transport: HttpTransport = UrlConnectionTransport,
) : GoogleTasksGateway {

    override suspend fun taskLists(): List<RemoteTaskList> =
        pages("$BASE/users/@me/lists?maxResults=100").map { item ->
            RemoteTaskList(id = item.getString("id"), title = item.optString("title"))
        }

    override suspend fun openTasks(listId: String): List<RemoteTask> {
        val items = pages(
            "$BASE/lists/${encode(listId)}/tasks?maxResults=100&showCompleted=false&showHidden=false&showDeleted=false",
        )
        return inGoogleOrder(items.filter { !it.optBoolean("deleted") }).map(::parseTask)
    }

    /** Follows `nextPageToken` and returns the `items` of every page. */
    private suspend fun pages(url: String): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var pageToken: String? = null
        do {
            val page = JSONObject(getJson(if (pageToken == null) url else "$url&pageToken=${encode(pageToken)}"))
            page.optJSONArray("items")?.let { array -> for (i in 0 until array.length()) items += array.getJSONObject(i) }
            pageToken = page.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return items
    }

    private suspend fun getJson(url: String): String {
        var token = authorizer.accessToken()
        var response = transport.get(url, token)
        if (response.status == 401) {
            authorizer.invalidate(token)
            token = authorizer.accessToken()
            response = transport.get(url, token)
        }
        if (response.status !in 200..299) throw GoogleApiException(response.status, errorMessage(response.body))
        return response.body
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(200))

    companion object {
        private const val BASE = "https://tasks.googleapis.com/tasks/v1"

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /**
         * The API does not promise any order. Google Tasks shows top-level tasks by `position`, each followed by
         * its subtasks by `position` (positions are zero-padded strings, so they sort as text).
         */
        internal fun inGoogleOrder(items: List<JSONObject>): List<JSONObject> {
            val ids = items.mapTo(mutableSetOf()) { it.getString("id") }
            // A subtask whose parent is not in the result (e.g. a completed parent) is shown as top-level.
            val (subtasks, topLevel) = items.partition { it.optString("parent").let { p -> p.isNotEmpty() && p in ids } }
            val children = subtasks.groupBy { it.getString("parent") }
            return topLevel.sortedBy { it.optString("position") }.flatMap { parent ->
                listOf(parent) + children[parent.getString("id")].orEmpty().sortedBy { it.optString("position") }
            }
        }

        internal fun parseTask(item: JSONObject): RemoteTask = RemoteTask(
            id = item.getString("id"),
            title = item.optString("title"),
            notes = item.optString("notes").ifEmpty { null },
            dueDate = item.optString("due").ifEmpty { null }?.let(::parseDue),
            updated = item.optString("updated").ifEmpty { null }?.let { OffsetDateTime.parse(it).toInstant() }
                ?: Instant.EPOCH,
            completed = item.optString("status") == "completed",
        )

        /**
         * Google stores a due date as midnight UTC of that date ("2026-12-01T00:00:00.000Z"). The date part is
         * the date the user picked; converting to the local zone would move it a day back west of UTC.
         */
        internal fun parseDue(due: String): LocalDate = LocalDate.parse(due.substring(0, 10))
    }
}
