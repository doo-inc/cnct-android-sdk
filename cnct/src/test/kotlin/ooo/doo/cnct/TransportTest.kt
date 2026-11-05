package ooo.doo.cnct

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TransportTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun transport(config: CnctConfig = CnctConfig(baseUrl = server.url("/").toString())) = CnctTransport(config)

    private fun answer(status: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test
    fun `sends the SDK's headers and the caller's, and decodes JSON`() = runTest {
        answer(200, """{"ok":true,"n":2,"items":["a"]}""")
        val config = CnctConfig(baseUrl = server.url("/").toString(), headers = mapOf("x-trace" to "t-1"))
        val result = transport(config).get("/api/x", headers = mapOf("authorization" to "Bearer kaer_sk_x"))

        assertEquals(true, result.jsonObject["ok"]?.jsonPrimitive?.boolean)
        assertEquals(2, result.jsonObject["n"]?.jsonPrimitive?.int)
        assertEquals("a", result.jsonObject["items"]?.jsonArray?.get(0)?.jsonPrimitive?.content)
        val seen = server.takeRequest()
        assertEquals("/api/x", seen.path)
        assertEquals("application/json", seen.getHeader("accept"))
        assertEquals("cnct-android-sdk/${CnctConfig.SDK_VERSION}", seen.getHeader("user-agent"))
        assertEquals("t-1", seen.getHeader("x-trace"))
        assertEquals("Bearer kaer_sk_x", seen.getHeader("authorization"))
    }

    @Test
    fun `keeps the base URL's path prefix`() = runTest {
        answer(200, "{}")
        transport(CnctConfig(baseUrl = server.url("/support/").toString())).get("/api/x")
        assertEquals("/support/api/x", server.takeRequest().path)
    }

    @Test
    fun `posts a JSON body`() = runTest {
        answer(201, """{"id":"m1"}""")
        val body = buildJsonObject {
            put("text", "hello")
            put("n", 1)
        }
        val result = transport().post("/api/x", body = body)
        assertEquals("m1", result.jsonObject["id"]?.jsonPrimitive?.content)
        val seen = server.takeRequest()
        assertEquals("POST", seen.method)
        assertTrue(seen.getHeader("content-type")!!.startsWith("application/json"))
        assertEquals(body, Json.parseToJsonElement(seen.body.readUtf8()))
    }

    @Test
    fun `a POST with no body still goes`() = runTest {
        answer(204)
        assertEquals(JsonNull, transport().post("/api/close"))
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `answers null for a 204 or an empty body`() = runTest {
        answer(204)
        assertEquals(JsonNull, transport().delete("/api/x"))
        answer(200, "")
        assertEquals(JsonNull, transport().get("/api/x"))
    }

    @Test
    fun `maps statuses to codes`() = runTest {
        val cases = mapOf(
            400 to CnctErrorCode.INVALID,
            401 to CnctErrorCode.UNAUTHENTICATED,
            403 to CnctErrorCode.UNAUTHENTICATED,
            404 to CnctErrorCode.NOT_FOUND,
            409 to CnctErrorCode.CONFLICT,
            413 to CnctErrorCode.TOO_LARGE,
            429 to CnctErrorCode.RATE_LIMITED,
            500 to CnctErrorCode.SERVER_ERROR,
            503 to CnctErrorCode.SERVER_ERROR,
            418 to CnctErrorCode.REQUEST_FAILED,
        )
        for ((status, expected) in cases) {
            answer(status, """{"error":"nope"}""")
            val error = assertFailsWith<CnctException> { transport().get("/api/x") }
            assertEquals(expected, error.code, "status $status")
            assertEquals(status, error.status)
            assertEquals("nope", error.message)
            assertEquals(JsonPrimitive("nope"), error.details?.jsonObject?.get("error"))
        }
    }

    @Test
    fun `the server's own code wins over the status`() = runTest {
        answer(409, """{"error":"closed","code":"conversation_closed"}""")
        val error = assertFailsWith<CnctException> { transport().post("/api/x") }
        assertEquals(CnctErrorCode.CONVERSATION_CLOSED, error.code)
    }

    @Test
    fun `a validation refusal keeps the fields and says it was refused`() = runTest {
        answer(400, """{"error":{"fieldErrors":{"phone":["bad"]},"formErrors":[]}}""")
        val error = assertFailsWith<CnctException> { transport().post("/api/x") }
        assertEquals(CnctErrorCode.INVALID, error.code)
        assertEquals("That request was refused.", error.message)
        val phone = error.details!!.jsonObject["error"]!!.jsonObject["fieldErrors"]!!.jsonObject["phone"]!!
        assertEquals("bad", phone.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `problem+json title becomes the message`() = runTest {
        answer(429, """{"title":"Slow down","code":"rate_limited"}""")
        val error = assertFailsWith<CnctException> { transport().get("/api/x") }
        assertEquals("Slow down", error.message)
        assertEquals(CnctErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `a 200 that is not JSON is a bad response`() = runTest {
        answer(200, "<html>login</html>")
        assertEquals(CnctErrorCode.BAD_RESPONSE, assertFailsWith<CnctException> { transport().get("/api/x") }.code)
    }

    @Test
    fun `an error that is not JSON keeps the raw body as the message`() = runTest {
        answer(502, "Bad gateway")
        val error = assertFailsWith<CnctException> { transport().get("/api/x") }
        assertEquals(CnctErrorCode.SERVER_ERROR, error.code)
        assertEquals("Bad gateway", error.message)
    }

    @Test
    fun `no network is offline and retryable, not a refusal`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val error = assertFailsWith<CnctException> { transport().get("/api/x") }
        assertEquals(CnctErrorCode.OFFLINE, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `a slow answer is a timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val config = CnctConfig(baseUrl = server.url("/").toString(), connectTimeout = 200.milliseconds)
        val error = assertFailsWith<CnctException> { transport(config).get("/api/x") }
        assertEquals(CnctErrorCode.TIMEOUT, error.code)
    }

    @Test
    fun `downloads bytes with the same error mapping`() = runTest {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        assertContentEquals(byteArrayOf(1, 2, 3), transport().bytes("/api/file"))
        answer(401, """{"error":"expired"}""")
        assertTrue(assertFailsWith<CnctException> { transport().bytes("/api/file") }.isUnauthenticated)
    }
}
