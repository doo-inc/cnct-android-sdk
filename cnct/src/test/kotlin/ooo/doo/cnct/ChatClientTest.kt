package ooo.doo.cnct

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ooo.doo.cnct.support.FakeHttp
import ooo.doo.cnct.support.FakeSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ChatClientTest {
    private val socket = FakeSocket()

    // What the platform answers. Each test changes what it needs before making its client.
    private var sessionOverride: String? = null
    private var resumeStatus = 200
    private var messagesStatus = 200
    private var messagesBody: String? = null

    /** The platform, as far as these tests are concerned. */
    private val server = FakeHttp { request ->
        when (request.path) {
            "/api/chat/public/$PUBLIC_KEY" -> FakeHttp.Answer(
                200,
                """{"business":"Al Waha","inbox":"Website","isActive":true,"greeting":"Hello — how can we help?","requirePhone":false}""",
            )
            "/api/chat/public/$PUBLIC_KEY/session" -> FakeHttp.Answer(200, sessionOverride ?: SESSION)
            "/api/chat/public/session" ->
                if (resumeStatus == 200) FakeHttp.Answer(200, RESUMED) else FakeHttp.Answer(resumeStatus, """{"error":"expired"}""")
            "/api/chat/public/messages" -> FakeHttp.Answer(
                messagesStatus,
                messagesBody ?: messageJson(
                    id = "msg_stored",
                    speaker = "CALLER",
                    body = "Do you open on Fridays?",
                    clientKey = request.json["clientKey"]?.jsonPrimitive?.content,
                ),
            )
            "/api/chat/public/close" -> FakeHttp.Answer(200, """{"ok":true}""")
            "/api/chat/public/attachments/att_1" -> FakeHttp.Answer(200, "%PDF", contentType = "application/pdf")
            else -> FakeHttp.Answer(404, """{"error":"not found"}""")
        }
    }

    private val seen get() = server.seen

    private fun TestScope.clientWith(
        store: CnctTokenStore = CnctTokenStore.memory(),
        scope: CoroutineScope = backgroundScope,
    ): CnctChatClient {
        val config = CnctConfig(baseUrl = "https://cnct.test")
        return CnctChatClient(
            config = config,
            credentials = CnctChatPublicKey(PUBLIC_KEY),
            tokenStore = store,
            transport = CnctTransport(config, server.client),
            socketFactory = socket.factory,
            scope = scope,
        )
    }

    /** Everything the client emits from here on, collected eagerly. */
    private fun TestScope.eventsOf(chat: CnctChatClient): List<CnctChatEvent> {
        val events = mutableListOf<CnctChatEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { chat.events.collect { events += it } }
        return events
    }

    private fun frame(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun TestScope.goLive(accepts: String = """["ping","typing","send"]""") {
        socket.push("""{"type":"authenticated","accepts":$accepts}""")
        runCurrent()
    }

    @Test
    fun `boot reads the inbox without a session`() = runTest {
        val chat = clientWith()
        val inbox = chat.boot()
        assertEquals("Al Waha", inbox.business)
        assertTrue(inbox.isActive)
        assertEquals("Hello — how can we help?", chat.state.value.inbox?.greeting)
        assertNull(seen.single().headers["x-chat-token"])
    }

    @Test
    fun `start adopts the session, stores the token and authenticates the socket`() = runTest {
        val store = CnctTokenStore.memory()
        val chat = clientWith(store)

        chat.start(displayName = "Layla")

        assertTrue(chat.hasSession)
        assertEquals("visitor-token-1", store.read("cnct_chat_$PUBLIC_KEY"))
        assertEquals("Layla", chat.state.value.visitor?.displayName)
        assertEquals(1, chat.state.value.messages.size)
        // The device's own zone rides along, so the team sees the customer's clock.
        assertEquals("""{"displayName":"Layla","timezone":"${java.util.TimeZone.getDefault().id}"}""", seen.single().body)

        // The handshake goes out immediately: the server closes a socket that has not authenticated
        // within five seconds, so it must not wait behind a suspension.
        val auth = frame(socket.sent.single())
        assertEquals("auth", auth["type"]!!.jsonPrimitive.content)
        assertEquals("visitor-token-1", auth["token"]!!.jsonPrimitive.content)
        assertEquals("wss://cnct.test/ws/chat", socket.url)
        // The socket carries the SDK's own headers, and never the token.
        assertEquals(CnctConfig().userAgent, socket.headers["user-agent"])
        assertFalse(socket.headers.values.any { it.contains("visitor-token-1") })
    }

    @Test
    fun `the session token never travels as an Authorization header`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        chat.send("Do you open on Fridays?")
        val post = seen.first { it.path == "/api/chat/public/messages" }
        assertEquals("visitor-token-1", post.headers["x-chat-token"])
        assertNull(post.headers["authorization"])
    }

    @Test
    fun `going live refetches the transcript rather than trusting the stream`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        seen.clear()

        goLive()

        assertEquals(CnctChatStatus.LIVE, chat.state.value.status)
        assertTrue(seen.any { it.path == "/api/chat/public/session" })
    }

    @Test
    fun `a send goes over the socket when the server offers it, and resolves on the ack`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        goLive()
        socket.sent.clear()

        val pending = async { chat.send("Do you open on Fridays?") }
        runCurrent()

        // The optimistic bubble is on screen before anything has been acknowledged.
        val optimistic = chat.state.value.messages.last()
        assertTrue(optimistic.pending)
        assertEquals("Do you open on Fridays?", optimistic.body)

        val sent = frame(socket.sent.single())
        assertEquals("send", sent["type"]!!.jsonPrimitive.content)
        // The frame id, the clientKey and the server's idempotency key are one value, so the three
        // can never disagree about which message this is.
        assertEquals(sent["id"], sent["clientKey"])
        assertEquals("Do you open on Fridays?", sent["body"]!!.jsonPrimitive.content)

        val key = sent["clientKey"]!!.jsonPrimitive.content
        val stored = messageJson(id = "msg_stored", speaker = "CALLER", body = "Do you open on Fridays?", clientKey = key)
        socket.push("""{"type":"ack","id":"$key","message":$stored}""")
        runCurrent()

        assertEquals("msg_stored", pending.await().id)
        // Replaced in place rather than appended — the visitor must not see themselves twice.
        assertEquals(1, chat.state.value.messages.count { it.body == "Do you open on Fridays?" })
        assertFalse(chat.state.value.messages.last().pending)
        // Nothing went over HTTP.
        assertTrue(seen.none { it.path == "/api/chat/public/messages" })
    }

    @Test
    fun `a send falls back to HTTP when the server does not offer socket sends`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        goLive(accepts = """["ping","typing"]""")
        socket.sent.clear()

        val stored = chat.send("Do you open on Fridays?")

        assertEquals("msg_stored", stored.id)
        assertTrue(socket.sent.isEmpty())
        assertTrue(seen.any { it.path == "/api/chat/public/messages" })
    }

    @Test
    fun `a refused send leaves the words on screen, marked, so retry can offer itself`() = runTest {
        messagesStatus = 500
        messagesBody = """{"error":"boom"}"""
        val chat = clientWith()
        chat.start(displayName = "Layla")

        assertFailsWith<CnctException> { chat.send("Do you open on Fridays?") }

        val failed = chat.state.value.failed.single()
        assertEquals("Do you open on Fridays?", failed.body)
        assertFalse(failed.pending)
        assertEquals(CnctErrorCode.SERVER_ERROR, chat.state.value.error?.code)
    }

    @Test
    fun `retry reuses the same clientKey, so the server cannot write it twice`() = runTest {
        messagesStatus = 500
        messagesBody = """{"error":"boom"}"""
        val chat = clientWith()
        chat.start(displayName = "Layla")
        assertFailsWith<CnctException> { chat.send("Try me") }
        val key = chat.state.value.failed.single().clientKey!!

        assertFailsWith<CnctException> { chat.retry(key) }

        val keys = seen.filter { it.path == "/api/chat/public/messages" }
            .map { it.json["clientKey"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(setOf(key), keys)
        assertEquals(1, chat.state.value.messages.count { it.body == "Try me" })
    }

    @Test
    fun `a message pushed by the server lands in state and on the event stream`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val events = eventsOf(chat)

        val message = messageJson(id = "msg_2", speaker = "OPERATOR", body = "We open at nine.")
        socket.push("""{"type":"chat.message","payload":{"message":$message}}""")
        runCurrent()

        assertEquals("We open at nine.", chat.state.value.messages.last().body)
        assertEquals(CnctSpeaker.OPERATOR, chat.state.value.messages.last().speaker)
        assertEquals(1, events.filterIsInstance<CnctChatEvent.MessageReceived>().size)
    }

    @Test
    fun `typing clears itself, because the platform sends a start and never a stop`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")

        socket.push("""{"type":"chat.typing"}""")
        runCurrent()
        assertTrue(chat.state.value.theyAreTyping)

        advanceTimeBy(4.seconds)
        runCurrent()
        assertFalse(chat.state.value.theyAreTyping)
    }

    @Test
    fun `typing() is throttled rather than sent per keystroke`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        socket.sent.clear()

        repeat(20) { chat.typing() }
        runCurrent()

        assertEquals(1, socket.sent.size)
        assertEquals("typing", frame(socket.sent.single())["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a 1008 close forgets the session instead of looping on a dead token`() = runTest {
        val store = CnctTokenStore.memory()
        val chat = clientWith(store)
        chat.boot()
        chat.start(displayName = "Layla")
        val events = eventsOf(chat)

        socket.serverClose(1008)
        runCurrent()

        assertFalse(chat.hasSession)
        assertNull(store.read("cnct_chat_$PUBLIC_KEY"))
        assertEquals(1, events.filterIsInstance<CnctChatEvent.Unauthenticated>().size)
        assertEquals(CnctChatStatus.IDLE, chat.state.value.status)
        // The inbox survives: it was never part of the session.
        assertEquals("Al Waha", chat.state.value.inbox?.business)
        // And nothing tries again.
        advanceTimeBy(60.seconds)
        assertEquals(1, socket.opened)
    }

    @Test
    fun `resume with no stored token is false rather than an error`() = runTest {
        val chat = clientWith()
        assertFalse(chat.resume())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `resume picks a stored session back up`() = runTest {
        val store = CnctTokenStore.memory()
        store.write("cnct_chat_$PUBLIC_KEY", "visitor-token-1")
        val chat = clientWith(store)

        assertTrue(chat.resume())
        assertEquals(1, chat.state.value.messages.size)
        assertEquals("Layla", chat.state.value.visitor?.displayName)
        assertEquals("visitor-token-1", seen.single().headers["x-chat-token"])
        assertEquals(1, socket.opened)
    }

    @Test
    fun `closing the conversation ends it and stops the socket`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        chat.end()
        assertEquals(CnctChatStatus.ENDED, chat.state.value.status)
        assertFalse(chat.state.value.canSend)
        assertEquals(1000, socket.closed)
    }

    @Test
    fun `sending before a session is a named refusal, not a null error`() = runTest {
        val chat = clientWith()
        val error = assertFailsWith<CnctException> { chat.send("hello") }
        assertEquals(CnctErrorCode.NO_SESSION, error.code)
    }

    @Test
    fun `an empty message is refused here rather than on the wire`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val error = assertFailsWith<CnctException> { chat.send("   ") }
        assertEquals(CnctErrorCode.EMPTY, error.code)
        assertTrue(seen.none { it.path == "/api/chat/public/messages" })
    }

    @Test
    fun `an unknown frame is a newer server talking, not a crash`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        socket.push("""{"type":"chat.something.new","payload":{"x":1}}""")
        socket.push("not json at all")
        socket.push("""{"type":"chat.message","payload":{"message":{"createdAt":12,"attachments":"no"}}}""")
        runCurrent()
        assertNull(chat.state.value.error)
        assertEquals(1, chat.state.value.messages.size)
    }

    // ── Beyond the Flutter suite: timers, reconnects and lifetimes, on virtual time ──────────────

    @Test
    fun `a send that is never acknowledged times out, stays on screen, and retries over the same key`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        goLive()
        socket.sent.clear()

        val first = async { runCatching { chat.send("Hello?") } }
        runCurrent()
        advanceTimeBy(CnctConfig().sendTimeout)
        runCurrent()

        val error = assertIs<CnctException>(first.await().exceptionOrNull())
        assertEquals(CnctErrorCode.TIMEOUT, error.code)
        assertTrue(error.isRetryable)
        val key = chat.state.value.failed.single().clientKey!!

        val again = async { chat.retry(key) }
        runCurrent()
        assertEquals(listOf(key, key), socket.sent.map { frame(it)["id"]!!.jsonPrimitive.content })
        socket.push("""{"type":"ack","id":"$key","message":${messageJson(id = "m9", speaker = "CALLER", body = "Hello?", clientKey = key)}}""")
        runCurrent()
        assertEquals("m9", again.await().id)
        assertTrue(chat.state.value.failed.isEmpty())
    }

    @Test
    fun `an error frame fails the send it answers, and says so once`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        goLive()
        val events = eventsOf(chat)
        socket.sent.clear()

        val pending = async { runCatching { chat.send("x".repeat(10)) } }
        runCurrent()
        val id = frame(socket.sent.single())["id"]!!.jsonPrimitive.content
        socket.push("""{"type":"error","id":"$id","error":"Too long.","code":"too_large"}""")
        runCurrent()

        val error = assertIs<CnctException>(pending.await().exceptionOrNull())
        assertEquals(CnctErrorCode.TOO_LARGE, error.code)
        assertEquals("Too long.", error.message)
        assertEquals(CnctErrorCode.TOO_LARGE, chat.state.value.error?.code)
        assertEquals(1, events.filterIsInstance<CnctChatEvent.Errored>().size)
        assertEquals(id, chat.state.value.failed.single().clientKey)
    }

    @Test
    fun `a dropped socket backs off, reconnects, and reloads what it missed`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        goLive()
        val events = eventsOf(chat)
        seen.clear()
        socket.sent.clear()

        socket.serverClose(1006)
        runCurrent()
        assertEquals(CnctChatStatus.OFFLINE, chat.state.value.status)
        assertEquals(listOf<CnctChatEvent>(CnctChatEvent.Disconnected), events)
        // Offline is not an error state, and sends still go — over HTTP.
        assertNull(chat.state.value.error)
        assertEquals("msg_stored", chat.send("Still there?").id)

        // The first attempt is half a second away, give or take the jitter.
        advanceTimeBy(700.milliseconds)
        runCurrent()
        assertEquals(2, socket.opened)
        assertEquals("auth", frame(socket.sent.first())["type"]!!.jsonPrimitive.content)

        seen.clear()
        goLive()
        assertEquals(CnctChatStatus.LIVE, chat.state.value.status)
        assertEquals(listOf("/api/chat/public/session"), seen.map { it.path })
        assertEquals(CnctChatEvent.Connected, events.last())
    }

    @Test
    fun `a failed bubble survives the reload a reconnect does`() = runTest {
        messagesStatus = 500
        messagesBody = """{"error":"boom"}"""
        val chat = clientWith()
        chat.start(displayName = "Layla")
        assertFailsWith<CnctException> { chat.send("Typed while it was down") }

        goLive()

        assertEquals("Typed while it was down", chat.state.value.failed.single().body)
        assertEquals(2, chat.state.value.messages.size)
    }

    @Test
    fun `a quiet connection is kept alive`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        socket.sent.clear()

        advanceTimeBy(25.seconds)
        runCurrent()

        assertEquals(listOf("ping"), socket.sent.map { frame(it)["type"]!!.jsonPrimitive.content })
        assertEquals(CnctChatStatus.CONNECTING, chat.state.value.status)
    }

    @Test
    fun `a refused token on resume is forgotten and reported, not thrown`() = runTest {
        resumeStatus = 401
        val store = CnctTokenStore.memory()
        store.write("cnct_chat_$PUBLIC_KEY", "visitor-token-old")
        val chat = clientWith(store)
        val events = eventsOf(chat)

        assertFalse(chat.resume())

        assertFalse(chat.hasSession)
        assertNull(store.read("cnct_chat_$PUBLIC_KEY"))
        assertEquals(listOf<CnctChatEvent>(CnctChatEvent.Unauthenticated), events)
        assertEquals(0, socket.opened)
    }

    @Test
    fun `a 409 on an HTTP send is reported as a closed conversation`() = runTest {
        messagesStatus = 409
        messagesBody = """{"error":"This conversation has ended."}"""
        val chat = clientWith()
        chat.start(displayName = "Layla")

        val error = assertFailsWith<CnctException> { chat.send("Hello?") }

        assertEquals(CnctErrorCode.CONFLICT, error.code)
        assertEquals(CnctErrorCode.CONVERSATION_CLOSED, chat.state.value.error?.code)
    }

    @Test
    fun `the business closing the conversation ends it and closes the socket`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val events = eventsOf(chat)

        socket.push("""{"type":"chat.closed"}""")
        runCurrent()

        assertEquals(CnctChatStatus.ENDED, chat.state.value.status)
        assertEquals(listOf<CnctChatEvent>(CnctChatEvent.Closed), events)
        assertEquals(1000, socket.closed)
        advanceTimeBy(60.seconds)
        assertEquals(1, socket.opened)
    }

    @Test
    fun `a conversation update replaces the conversation`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val events = eventsOf(chat)

        socket.push(
            """{"type":"chat.conversation.updated","payload":{"conversation":{"status":"SNOOZED","startedAt":"2026-09-08T09:59:00.000Z","withPerson":true}}}""",
        )
        runCurrent()

        assertEquals(CnctConversationStatus.SNOOZED, chat.state.value.conversation?.status)
        assertTrue(chat.state.value.conversation!!.withPerson)
        assertIs<CnctChatEvent.ConversationUpdated>(events.single())
    }

    @Test
    fun `disconnect stops reconnecting, and connect comes back`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")

        chat.disconnect()
        runCurrent()
        assertEquals(CnctChatStatus.OFFLINE, chat.state.value.status)
        assertEquals(1000, socket.closed)
        advanceTimeBy(60.seconds)
        assertEquals(1, socket.opened)

        chat.connect()
        runCurrent()
        assertEquals(2, socket.opened)
        assertEquals(CnctChatStatus.CONNECTING, chat.state.value.status)
    }

    @Test
    fun `close releases the socket and the client stays closed`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")

        chat.close()
        runCurrent()

        assertEquals(1000, socket.closed)
        assertEquals(CnctChatStatus.OFFLINE, chat.state.value.status)
        chat.connect()
        advanceTimeBy(60.seconds)
        assertEquals(1, socket.opened)
    }

    @Test
    fun `cancelling the scope it was given closes it, and fails a send in flight`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val chat = clientWith(scope = scope)
        chat.start(displayName = "Layla")
        goLive()

        val pending = async { runCatching { chat.send("Leaving now") } }
        runCurrent()
        scope.cancel()
        runCurrent()

        assertEquals(1000, socket.closed)
        assertEquals(CnctErrorCode.OFFLINE, (pending.await().exceptionOrNull() as CnctException).code)
        assertEquals("Leaving now", chat.state.value.failed.single().body)
    }

    @Test
    fun `an attachment comes with the token on its header, and the token is never printed`() = runTest {
        val chat = clientWith()
        assertEquals(CnctErrorCode.NO_SESSION, assertFailsWith<CnctException> { chat.downloadAttachment("att_1") }.code)
        chat.start(displayName = "Layla")

        assertContentEquals("%PDF".encodeToByteArray(), chat.downloadAttachment("att_1"))
        assertEquals("visitor-token-1", seen.last().headers["x-chat-token"])

        val request = chat.attachmentRequest("att 1")
        assertEquals("https://cnct.test/api/chat/public/attachments/att%201", request.url.toString())
        assertEquals("visitor-token-1", request.headers["x-chat-token"])
        assertFalse(request.toString().contains("visitor-token-1"))
        assertFalse(chat.toString().contains("visitor-token-1"))
    }

    @Test
    fun `clientFor hands back the client the credential opens`() = runTest {
        val cnct = Cnct()
        val chat = cnct.clientFor(CnctChatPublicKey(PUBLIC_KEY))
        assertIs<CnctChatClient>(chat).close()
        assertIs<CnctAgentClient>(cnct.clientFor(CnctApiKey("kaer_sk_test_abc")))
        val made = cnct.chat(CnctChatPublicKey(PUBLIC_KEY), scope = backgroundScope)
        assertFalse(made.hasSession)
    }

    companion object {
        const val PUBLIC_KEY = "inbox-public-key"

        fun messageJson(
            id: String = "msg_1",
            speaker: String = "AI",
            type: String = "TEXT",
            body: String = "Hello",
            clientKey: String? = null,
        ): String = buildJsonObject {
            put("id", id)
            put("speaker", speaker)
            put("type", type)
            put("body", body)
            put("createdAt", "2026-09-08T10:00:00.000Z")
            put("clientKey", clientKey)
            put("attachments", kotlinx.serialization.json.JsonArray(emptyList()))
        }.toString()

        private val CONVERSATION = """{"status":"OPEN","startedAt":"2026-09-08T09:59:00.000Z","withPerson":false}"""

        val SESSION = """
            {"token":"visitor-token-1","visitor":{"displayName":"Layla"},"conversation":$CONVERSATION,
             "messages":[${messageJson(body = "Hello — how can we help?")}]}
        """

        val RESUMED = """
            {"visitor":{"displayName":"Layla"},"conversation":$CONVERSATION,
             "messages":[${messageJson(body = "Hello — how can we help?")}]}
        """
    }
}
