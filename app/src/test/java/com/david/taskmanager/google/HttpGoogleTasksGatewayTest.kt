package com.david.taskmanager.google

import com.david.taskmanager.core.sync.RemoteTask
import com.david.taskmanager.core.sync.RemoteTaskList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/** Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class HttpGoogleTasksGatewayTest {

    private class Authorizer : GoogleAuthorizer {
        var token = "t1"
        val invalidated = mutableListOf<String>()
        override suspend fun accessToken() = token
        override suspend fun invalidate(token: String) {
            invalidated += token
            this.token = "t2"
        }
    }

    /** Answers from a URL → (status, body) table and records every request. */
    private class Recorder(private val responses: Map<String, List<HttpResponse>>) : HttpTransport {
        val requests = mutableListOf<Pair<String, String>>()
        private val served = mutableMapOf<String, Int>()
        override suspend fun get(url: String, accessToken: String): HttpResponse {
            requests += url to accessToken
            val forUrl = responses[url] ?: error("Unexpected request $url")
            val index = served.merge(url, 1, Int::plus)!! - 1
            return forUrl[minOf(index, forUrl.lastIndex)]
        }
    }

    private val authorizer = Authorizer()

    private fun gateway(vararg responses: Pair<String, List<HttpResponse>>) =
        Recorder(responses.toMap()).let { it to HttpGoogleTasksGateway(authorizer, it) }

    private fun ok(body: String) = HttpResponse(200, body)

    private val listsUrl = "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=100"
    private val tasksUrl = "https://tasks.googleapis.com/tasks/v1/lists/L%2B1/tasks" +
        "?maxResults=100&showCompleted=false&showHidden=false&showDeleted=false"

    @Test
    fun `task lists follow pagination`() = runTest {
        val (recorder, gateway) = gateway(
            listsUrl to listOf(ok("""{"items":[{"id":"a","title":"My Tasks"}],"nextPageToken":"p 2"}""")),
            "$listsUrl&pageToken=p+2" to listOf(ok("""{"items":[{"id":"b","title":"Work"}]}""")),
        )

        assertEquals(listOf(RemoteTaskList("a", "My Tasks"), RemoteTaskList("b", "Work")), gateway.taskLists())
        assertEquals(listOf("t1", "t1"), recorder.requests.map { it.second })
    }

    @Test
    fun `tasks are parsed with date-only due dates`() = runTest {
        val (_, gateway) = gateway(
            tasksUrl to listOf(
                ok(
                    """
                    {"items":[
                      {"id":"x","title":"Buy milk","notes":"2 litres","status":"needsAction",
                       "due":"2026-12-01T00:00:00.000Z","updated":"2026-11-27T09:30:00.000Z","position":"00000000000000000001"},
                      {"id":"y","title":"","status":"needsAction","updated":"2026-11-27T09:31:00.000Z","position":"00000000000000000002"}
                    ]}
                    """,
                ),
            ),
        )

        assertEquals(
            listOf(
                RemoteTask("x", "Buy milk", "2 litres", LocalDate.of(2026, 12, 1), Instant.parse("2026-11-27T09:30:00Z")),
                RemoteTask("y", "", null, null, Instant.parse("2026-11-27T09:31:00Z")),
            ),
            gateway.openTasks("L+1"),
        )
    }

    @Test
    fun `tasks come back in Google order with subtasks after their parent, deleted ones dropped`() = runTest {
        val (_, gateway) = gateway(
            tasksUrl to listOf(
                ok(
                    """
                    {"items":[
                      {"id":"child2","title":"c2","parent":"p1","position":"00000000000000000002"},
                      {"id":"p2","title":"p2","position":"00000000000000000002"},
                      {"id":"gone","title":"gone","position":"00000000000000000000","deleted":true},
                      {"id":"child1","title":"c1","parent":"p1","position":"00000000000000000001"},
                      {"id":"p1","title":"p1","position":"00000000000000000001"},
                      {"id":"orphan","title":"o","parent":"completedParent","position":"00000000000000000003"}
                    ]}
                    """,
                ),
            ),
        )

        assertEquals(listOf("p1", "child1", "child2", "p2", "orphan"), gateway.openTasks("L+1").map { it.id })
    }

    @Test
    fun `a 401 invalidates the token and retries once`() = runTest {
        val (recorder, gateway) = gateway(
            listsUrl to listOf(HttpResponse(401, "{}"), ok("""{"items":[]}""")),
        )

        assertEquals(emptyList<RemoteTaskList>(), gateway.taskLists())
        assertEquals(listOf("t1"), authorizer.invalidated)
        assertEquals(listOf("t1", "t2"), recorder.requests.map { it.second })
    }

    @Test
    fun `error statuses raise GoogleApiException with Google's message`() = runTest {
        val (_, gateway) = gateway(
            listsUrl to listOf(HttpResponse(403, """{"error":{"code":403,"message":"Tasks API has not been used"}}""")),
        )

        val error = runCatching { gateway.taskLists() }.exceptionOrNull()
        assertTrue(error is GoogleApiException)
        assertEquals(403, (error as GoogleApiException).status)
        assertTrue(error.message!!.contains("Tasks API has not been used"))
    }

    @Test
    fun `consent errors from the authorizer pass through`() = runTest {
        val gateway = HttpGoogleTasksGateway(
            object : GoogleAuthorizer {
                override suspend fun accessToken(): String = throw IllegalStateException("no account")
                override suspend fun invalidate(token: String) = Unit
            },
            { _, _ -> error("must not be called") },
        )

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { gateway.taskLists() } }
    }
}
