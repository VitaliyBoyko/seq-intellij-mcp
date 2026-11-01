// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp

import com.vitaliiboiko.seqmcp.services.SeqApiClient
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
import com.vitaliiboiko.seqmcp.services.SeqSqlQueryRequest
import com.vitaliiboiko.seqmcp.services.resolveSeqApiLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Exercises the production Java HTTP/WebSocket client over real TLS sockets. */
class SeqTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var http: HttpClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            protocols = listOf(Protocol.HTTP_1_1)
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        http = HttpClient.newBuilder()
            .sslContext(clientCertificates.sslContext())
            .connectTimeout(Duration.ofSeconds(2))
            .build()
    }

    @After
    fun tearDown() {
        http.shutdownNow()
        server.shutdown()
    }

    private fun client(timeout: Duration = Duration.ofSeconds(2), transport: HttpClient = http) = SeqApiClient(
        serverUrl = { server.url("/seq/").toString() },
        apiKey = { workspace -> if (workspace == "staging") "workspace-key" else "read-key" },
        httpClient = transport,
        requestTimeout = timeout,
    )

    private fun discovery(
        group: String = "Events", links: String = """
        "Scan":"api/events/scan{?filter,count,render,fromDateUtc,toDateUtc,afterId}",
        "Stream":"api/events/stream{?filter,render}"
    """.trimIndent()
    ) {
        server.enqueue(MockResponse().setBody("""{"Links":{"${group}Resources":"api/resources"}}"""))
        server.enqueue(MockResponse().setBody("""{"Links":{$links}}"""))
    }

    private fun stream(vararg events: String, closeCode: Int? = 1000) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.forEach(webSocket::send)
                closeCode?.let { webSocket.close(it, "test completion") }
            }
        }))
    }

    @Test(timeout = 10_000)
    fun authenticatedWssSearchPreservesBasePathAndEscapedFilter() = runBlocking {
        discovery()
        stream("""{"Id":"event-1"}""")
        val filter = "Message = 'a&b+c/%?#'"
        val events = client().searchEvents(SeqSearchRequest(filter, count = 10, workspace = "staging"))
        assertEquals("event-1", events.single().jsonObject["Id"]!!.jsonPrimitive.content)
        repeat(3) { index ->
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("workspace-key", request.getHeader("X-Seq-ApiKey"))
            if (index == 2) {
                assertEquals("/seq/api/events/scan", request.requestUrl!!.encodedPath)
                assertEquals(filter, request.requestUrl!!.queryParameter("filter"))
                assertEquals("10", request.requestUrl!!.queryParameter("count"))
                assertEquals("websocket", request.getHeader("Upgrade")?.lowercase())
            }
        }
    }

    @Test(timeout = 10_000)
    fun mixedCaseHttpsSchemesWorkForConfiguredUrlsAndDiscoveryLinks() = runBlocking {
        val baseUrl = server.url("/seq/").toString().replaceFirst("https:", "HtTpS:")
        val resourcesUrl = server.url("/seq/api/resources").toString().replaceFirst("https:", "HTTPS:")
        server.enqueue(MockResponse().setBody("""{"Links":{"EventsResources":"$resourcesUrl"}}"""))
        server.enqueue(MockResponse().setBody("""{"Links":{"Scan":"api/events/scan{?filter,count,render}"}}"""))
        stream("""{"Id":"event-1"}""")

        val events = SeqApiClient({ baseUrl }, { "read-key" }, http)
            .searchEvents(SeqSearchRequest("", count = 10))

        assertEquals("event-1", events.single().jsonObject["Id"]!!.jsonPrimitive.content)
        repeat(3) {
            assertEquals("read-key", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("X-Seq-ApiKey"))
        }
    }

    @Test
    fun sameOriginLinksAcceptMixedCaseSchemesAndEquivalentDefaultPorts() {
        for ((scheme, port) in listOf("http" to 80, "https" to 443)) {
            val base = URI("$scheme://seq.example:$port/seq/")
            val target = "${scheme.uppercase()}://SEQ.EXAMPLE/seq/api/events?filter=a%26b"
            assertEquals(URI(target), resolveSeqApiLink(base, target))
            assertEquals(base, resolveSeqApiLink(URI(target), base.toString()))

            val error = assertThrows(SeqApiException::class.java) {
                resolveSeqApiLink(base, "${scheme.uppercase()}://seq.example:${port + 1}/api")
            }
            assertEquals("INVALID_API_LINK", error.errorCode)
        }
    }

    @Test(timeout = 10_000)
    fun sqlUsesHttpsPostAndHonorsExplicitQueryBudget() = runBlocking {
        discovery("Data", """"Query":"api/data{?q,timeoutMS}"""")
        server.enqueue(
            MockResponse().setBody("""{"Columns":["Count"],"Rows":[[1]]}""").setBodyDelay(1500, TimeUnit.MILLISECONDS)
        )
        val result = client(Duration.ofSeconds(1)).querySql(
            SeqSqlQueryRequest(
                "select count(*) from stream",
                timeoutSeconds = 1
            )
        )
        assertEquals("1", result["Rows"]!!.jsonArray.single().jsonArray.single().jsonPrimitive.content)
        repeat(2) { server.takeRequest() }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("read-key", request.getHeader("X-Seq-ApiKey"))
        assertEquals("1000", request.requestUrl!!.queryParameter("timeoutMS"))
    }

    @Test(timeout = 10_000)
    fun httpPermissionFailuresHaveActionableDiagnostics() = runBlocking {
        for (status in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"Error":"Denied"}"""))
            val error = expectApiFailure { client().searchEvents(SeqSearchRequest("")) }
            assertEquals(status, error.statusCode)
            assertEquals("HTTP_ERROR", error.errorCode)
            assertTrue(error.message!!.contains(if (status == 401) "API key" else "Read permission"))
        }
    }

    @Test(timeout = 10_000)
    fun proxyRejectingWebSocketUpgradeReportsHttpStatus() = runBlocking {
        discovery()
        server.enqueue(MockResponse().setResponseCode(502))
        val error = expectApiFailure { client().searchEvents(SeqSearchRequest("")) }
        assertEquals("WEBSOCKET_HANDSHAKE", error.errorCode)
        assertEquals(502, error.statusCode)
        assertTrue(error.message!!.contains("Upgrade/Connection"))
    }

    @Test(timeout = 10_000)
    fun abnormalCloseDiscardsPartialResults() = runBlocking {
        discovery()
        stream("""{"Id":"partial"}""", closeCode = 1011)
        val error = expectApiFailure { client().searchEvents(SeqSearchRequest("", count = 10)) }
        assertEquals("STREAM_CLOSED", error.errorCode)
        assertTrue(error.message!!.contains("1011"))
    }

    @Test(timeout = 10_000)
    fun unfinishedSearchTimesOutInsteadOfReturningPartialSuccess() = runBlocking {
        discovery()
        stream("""{"Id":"partial"}""", closeCode = null)
        val error = expectApiFailure { client().searchEvents(SeqSearchRequest("", count = 10, timeoutSeconds = 1)) }
        assertEquals("SEARCH_TIMEOUT", error.errorCode)
    }

    @Test(timeout = 10_000)
    fun liveCaptureMayFinishItsWindowWithoutEvents() = runBlocking {
        discovery()
        stream(closeCode = null)
        assertTrue(client().waitForEvents(null, 1, null).isEmpty())
    }

    @Test(timeout = 10_000)
    fun deadlineCoversStalledResponseHeaders() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = expectApiFailure { client(Duration.ofMillis(300)).listSignals(null) }
        assertEquals("TIMEOUT", error.errorCode)
    }

    @Test(timeout = 10_000)
    fun deadlineCoversStalledResponseBody() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
        val error = expectApiFailure { client(Duration.ofMillis(300)).listSignals(null) }
        assertEquals("TIMEOUT", error.errorCode)
    }

    @Test(timeout = 10_000)
    fun cancellationInterruptsPendingHttpRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { client(Duration.ofSeconds(30)).listSignals(null) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test(timeout = 10_000)
    fun cancellationInterruptsWebSocketWait() = runBlocking {
        discovery()
        stream(closeCode = null)
        val job = launch { client().searchEvents(SeqSearchRequest("", timeoutSeconds = 300)) }
        withContext(Dispatchers.IO) { repeat(3) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) } }
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test(timeout = 10_000)
    fun untrustedCertificateHasTlsDiagnostic() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        // IntelliJ replaces the JVM default SSL context during platform tests.
        val unrelatedCertificate = HeldCertificate.Builder().build()
        val certificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(unrelatedCertificate.certificate)
            .build()
        val untrusted = HttpClient.newBuilder()
            .sslContext(certificates.sslContext())
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        try {
            val error = expectApiFailure { client(transport = untrusted).listSignals(null) }
            assertEquals("TLS_ERROR", error.errorCode)
        } finally {
            untrusted.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun discoveryCannotForwardCredentialsToAnotherOrigin() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Links":{"SignalsResources":"https://other.example/api/signals"}}"""))
        val error = expectApiFailure { client().listSignals(null) }
        assertEquals("INVALID_API_LINK", error.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 10_000)
    fun redirectsAreReportedWithoutForwardingCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/signin"))
        val error = expectApiFailure { client().listSignals(null) }
        assertEquals(302, error.statusCode)
        assertTrue(error.message!!.contains("final Seq API URL"))
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 10_000)
    fun oversizedWebSocketEventIsRejected() = runBlocking {
        discovery()
        stream("""{"Message":"${"x".repeat(1_048_576)}"}""", closeCode = null)
        val error = expectApiFailure { client().searchEvents(SeqSearchRequest("")) }
        assertEquals("RESULT_TOO_LARGE", error.errorCode)
    }

    @Test(timeout = 10_000)
    fun oversizedHttpResponseIsRejectedBeforeJsonParsing() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(8 * 1024 * 1024 + 1)))
        val error = expectApiFailure { client().listSignals(null) }
        assertEquals("RESULT_TOO_LARGE", error.errorCode)
    }

    private suspend fun expectApiFailure(action: suspend () -> Any?): SeqApiException {
        try {
            action()
        } catch (error: SeqApiException) {
            return error
        }
        throw AssertionError("Expected a SeqApiException")
    }
}
