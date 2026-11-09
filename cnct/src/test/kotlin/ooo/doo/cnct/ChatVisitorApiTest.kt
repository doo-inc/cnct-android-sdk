package ooo.doo.cnct

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import ooo.doo.cnct.ChatClientTest.Companion.PUBLIC_KEY
import ooo.doo.cnct.ChatClientTest.Companion.SESSION
import ooo.doo.cnct.ChatClientTest.Companion.messageJson
import ooo.doo.cnct.support.FakeHttp
import ooo.doo.cnct.support.FakeSocket
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The visitor API the hosted widget uses and the 0.x SDKs did not have: the inbox's widget settings,
 * the full message, files both ways, signing in, the visitor's conversations, read receipts, ratings
 * and the profile — and the socket frames that go with them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatVisitorApiTest {
    private val socket = FakeSocket()

    /** Path → what the platform answers there. Each test sets what it needs. */
    private val answers = HashMap<String, (FakeHttp.Seen) -> FakeHttp.Answer>()

    private val server = FakeHttp { request ->
        answers[request.path]?.invoke(request) ?: when (request.path) {
            "/api/chat/public/$PUBLIC_KEY" -> FakeHttp.Answer(200, INBOX)
            "/api/chat/public/$PUBLIC_KEY/session" -> FakeHttp.Answer(200, SESSION_WITH_ID)
            "/api/chat/public/session" -> FakeHttp.Answer(200, SESSION_WITH_ID.replace("\"token\":\"visitor-token-1\",", ""))
            "/api/chat/public/messages" -> FakeHttp.Answer(
                200,
                messageJson(
                    id = "msg_stored",
                    speaker = "CALLER",
                    body = request.json["body"]!!.jsonPrimitive.content,
                    clientKey = request.json["clientKey"]?.jsonPrimitive?.content,
                ),
            )
            "/api/chat/public/close" -> FakeHttp.Answer(200, """{"ok":true}""")
            else -> FakeHttp.Answer(404, """{"error":"not found"}""")
        }
    }

    private val seen get() = server.seen

    private fun TestScope.clientWith(store: CnctTokenStore = CnctTokenStore.memory()): CnctChatClient {
        val config = CnctConfig(baseUrl = "https://cnct.test")
        return CnctChatClient(
            config = config,
            credentials = CnctChatPublicKey(PUBLIC_KEY),
            tokenStore = store,
            transport = CnctTransport(config, server.client),
            socketFactory = socket.factory,
            scope = backgroundScope,
        )
    }

    private fun TestScope.eventsOf(chat: CnctChatClient): List<CnctChatEvent> {
        val events = mutableListOf<CnctChatEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { chat.events.collect { events += it } }
        return events
    }

    private fun TestScope.goLive(accepts: String = """["ping","typing","send"]""") {
        socket.push("""{"type":"authenticated","accepts":$accepts}""")
        runCurrent()
    }

    private fun frame(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun posted(path: String) = seen.filter { it.method == "POST" && it.path == path }

    // ── The inbox ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `boot reads the widget settings, the team and the availability`() = runTest {
        val inbox = clientWith().boot()

        assertEquals("#d7262e", inbox.widget.brand.color)
        assertEquals(CnctAppearance.SYSTEM, inbox.widget.brand.appearance)
        assertEquals(CnctLanguage.AR, inbox.widget.brand.language)
        assertEquals("https://cdn.test/logo.png", inbox.widget.brand.avatarUrl)
        assertFalse(inbox.widget.brand.showBranding)
        assertTrue(inbox.widget.chat.allowEndConversation)
        assertEquals(CnctChatStart.MESSAGE, inbox.widget.chat.start)
        // A section the server did not send keeps its defaults, field by field.
        assertTrue(inbox.widget.chat.allowAttachments)
        assertTrue(inbox.widget.chat.askForRating)

        assertEquals(listOf("Sara", "Omar"), inbox.team.map { it.name })
        assertEquals(CnctSenderKind.PERSON, inbox.team.first().kind)
        assertEquals("fox-clay", inbox.team.first().avatar)

        val availability = inbox.availability!!
        assertEquals(false, availability.open)
        assertEquals(Instant.parse("2026-11-10T05:00:00Z"), availability.opensAt)
        assertTrue(availability.assistant)
        assertNull(availability.replyMinutes)
    }

    @Test
    fun `an inbox from a server that predates the widget reads as the defaults`() = runTest {
        answers["/api/chat/public/$PUBLIC_KEY"] = {
            FakeHttp.Answer(200, """{"business":"Your business","isActive":true,"widget":{"brand":{"color":"red","appearance":"neon"}}}""")
        }
        val inbox = clientWith().boot()

        // A colour that is not #rrggbb is the business's typo, not a reason to draw an unstyled screen.
        assertEquals(CnctWidgetBrand.DEFAULT_COLOR, inbox.widget.brand.colorOrDefault)
        assertEquals(CnctAppearance.LIGHT, inbox.widget.brand.appearance)
        assertEquals(CnctLanguage.AUTO, inbox.widget.brand.language)
        assertEquals(CnctChatStart.DETAILS, inbox.widget.chat.start)
        assertTrue(inbox.team.isEmpty())
        assertNull(inbox.availability)
    }

    // ── The message ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a message carries its sender, flow, suggestions, quote and files`() {
        val message = decodeModel(
            CnctChatMessage.serializer(),
            Json.parseToJsonElement(
                """
                {"id":"m1","speaker":"AI","type":"TEXT","body":"1. Book a visit\n2. Talk to a person\nReply with the number",
                 "createdAt":"2026-11-09T10:00:00.000Z",
                 "sender":{"kind":"ai","name":"Assistant","mascot":"drop","colour":"teal"},
                 "flow":{"text":"Would you like to book a visit?","layout":"buttons",
                         "choices":[{"id":"c1","title":"Book a visit"},{"id":"c2","title":"Talk to a person","description":"Someone from the team"},{"title":"no id"}],
                         "footer":"Open 2pm–10pm","media":{"kind":"image","url":"https://cdn.test/a.png"},"expects":"telepathy"},
                 "suggestions":["Yes","No",3],
                 "replyTo":{"messageId":"m0","mine":true,"excerpt":"Do you open on Fridays?","attachmentKind":"IMAGE"},
                 "attachments":[{"id":"a1","kind":"IMAGE","mimeType":"image/jpeg","filename":"photo.jpg","bytes":1200000}],
                 "somethingNew":{"x":1}}
                """,
            ),
        )

        assertEquals(CnctSenderKind.AI, message.sender?.kind)
        assertEquals("Assistant", message.sender?.name)
        assertTrue(message.drawsAsFlow)
        assertEquals("Would you like to book a visit?", message.words)
        assertEquals(listOf("c1", "c2", ""), message.flow!!.choices.map { it.id })
        assertEquals(CnctFlowMediaKind.IMAGE, message.flow!!.media?.kind)
        // An answer kind this SDK has not met is no keyboard at all, rather than a broken message.
        assertNull(message.flow!!.expects)
        assertEquals(listOf("Yes", "No", "3"), message.suggestions)
        assertEquals("m0", message.replyTo?.messageId)
        assertTrue(message.replyTo!!.mine)
        assertEquals(CnctAttachmentKind.IMAGE, message.replyTo?.attachmentKind)
        assertEquals(1_200_000L, message.attachments.single().bytes)
    }

    @Test
    fun `the state knows what a flow is waiting for, and offers suggestions only when nothing else is`() {
        val conversation = CnctChatConversation(id = "c", status = CnctConversationStatus.OPEN)
        val ask = CnctChatMessage(
            id = "m2",
            speaker = CnctSpeaker.AI,
            body = "numbered",
            flow = CnctChatFlow(
                text = "Pick one",
                choices = listOf(CnctFlowChoice("a", "Book a visit"), CnctFlowChoice("", "No id"), CnctFlowChoice("b", "Talk to a person")),
            ),
            suggestions = listOf("Never shown beside choices"),
        )
        val live = CnctChatState(status = CnctChatStatus.LIVE, conversation = conversation, messages = listOf(ask))

        val awaiting = live.awaiting!!
        assertEquals(listOf("a", "b"), awaiting.choices.map { it.id })
        assertEquals(CnctFlowLayout.BUTTONS, awaiting.layout)
        assertTrue(live.suggestions.isEmpty())

        // A description needs the width: a list.
        val described = ask.copy(flow = ask.flow!!.copy(choices = listOf(CnctFlowChoice("a", "One", description = "More words"))))
        assertEquals(CnctFlowLayout.LIST, live.copy(messages = listOf(described)).awaiting!!.layout)

        // An open question asks for a kind of answer instead.
        val email = ask.copy(flow = CnctChatFlow(text = "Your email?", expects = CnctFlowExpects.EMAIL))
        assertEquals(CnctFlowExpects.EMAIL, live.copy(messages = listOf(email)).awaiting!!.expects)

        // Anything said after it moves the conversation on.
        val answered = live.copy(messages = listOf(ask, CnctChatMessage.optimistic("k", "Book a visit")))
        assertNull(answered.awaiting)

        // Suggestions are read back over the assistant's trailing messages.
        val offered = CnctChatMessage(id = "m3", speaker = CnctSpeaker.AI, body = "Yes", suggestions = listOf("Book", "", "Hours", "Price", "Menu"))
        val picture = CnctChatMessage(id = "m4", speaker = CnctSpeaker.AI, attachments = listOf(CnctChatAttachment(id = "a")))
        assertEquals(listOf("Book", "Hours", "Price"), live.copy(messages = listOf(offered, picture)).suggestions)
        // And not once the conversation is over.
        assertTrue(live.copy(status = CnctChatStatus.ENDED, messages = listOf(offered)).suggestions.isEmpty())
    }

    // ── The session ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `resume and the reload after a reconnect both send the device's time zone`() = runTest {
        val store = CnctTokenStore.memory()
        store.write("cnct_chat_$PUBLIC_KEY", "visitor-token-1")
        val chat = clientWith(store)

        assertTrue(chat.resume())
        assertEquals("timezone=${TimeZone.getDefault().id}", seen.single().query)

        seen.clear()
        goLive()
        assertEquals("timezone=${TimeZone.getDefault().id}", seen.single().query)
        assertEquals("c_1", chat.state.value.conversation?.id)
    }

    // ── Sending ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `files go with a message as attachmentIds, and may go without words`() = runTest {
        answers["/api/chat/public/attachments"] = { FakeHttp.Answer(201, ATTACHMENT) }
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val photo = chat.uploadAttachment(ByteArray(10), "photo.jpg", "image/jpeg")

        val pending = async { chat.send(attachmentIds = listOf(photo.id)) }
        runCurrent()
        pending.await()

        val body = posted("/api/chat/public/messages").single().json
        assertEquals("", body["body"]!!.jsonPrimitive.content)
        assertEquals(listOf("att_9"), body["attachmentIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(body["replyId"])
    }

    @Test
    fun `the optimistic bubble shows the files this client uploaded`() = runTest {
        answers["/api/chat/public/attachments"] = { FakeHttp.Answer(201, ATTACHMENT) }
        answers["/api/chat/public/messages"] = { FakeHttp.Answer(500, """{"error":"boom"}""") }
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val photo = chat.uploadAttachment(ByteArray(10), "photo.jpg", "image/jpeg")

        assertFailsWith<CnctException> { chat.send("Here", attachmentIds = listOf(photo.id)) }

        val failed = chat.state.value.failed.single()
        assertEquals("photo.jpg", failed.attachments.single().filename)
        assertEquals(CnctAttachmentKind.IMAGE, failed.attachments.single().kind)
    }

    @Test
    fun `nothing to send is refused here, and so is an eleventh file`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")

        assertEquals(CnctErrorCode.EMPTY, assertFailsWith<CnctException> { chat.send("  ") }.code)
        val eleven = (1..11).map { "att_$it" }
        assertEquals(CnctErrorCode.INVALID, assertFailsWith<CnctException> { chat.send("x", attachmentIds = eleven) }.code)
        assertTrue(posted("/api/chat/public/messages").isEmpty())
    }

    @Test
    fun `choosing a flow choice sends its title with its id as replyId`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")

        chat.choose(CnctFlowChoice(id = "choice_book", title = "Book a visit"))

        val body = posted("/api/chat/public/messages").single().json
        assertEquals("Book a visit", body["body"]!!.jsonPrimitive.content)
        assertEquals("choice_book", body["replyId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `over the socket, the send frame carries the files and the choice too`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        goLive()
        socket.sent.clear()

        val pending = async { chat.send("Book a visit", attachmentIds = listOf("att_1", "att_2"), replyId = "c1") }
        runCurrent()

        val sent = frame(socket.sent.single())
        assertEquals("send", sent["type"]!!.jsonPrimitive.content)
        assertEquals(sent["id"], sent["clientKey"])
        assertEquals(listOf("att_1", "att_2"), sent["attachmentIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("c1", sent["replyId"]!!.jsonPrimitive.content)
        val key = sent["clientKey"]!!.jsonPrimitive.content
        socket.push("""{"type":"ack","id":"$key","message":${messageJson(id = "m9", speaker = "CALLER", body = "Book a visit", clientKey = key)}}""")
        runCurrent()
        assertEquals("m9", pending.await().id)
    }

    @Test
    fun `a retry sends the same files and the same choice again`() = runTest {
        var refuse = true
        answers["/api/chat/public/messages"] = { request ->
            if (refuse) {
                FakeHttp.Answer(500, """{"error":"boom"}""")
            } else {
                FakeHttp.Answer(200, messageJson(id = "m5", speaker = "CALLER", body = "x", clientKey = request.json["clientKey"]!!.jsonPrimitive.content))
            }
        }
        val chat = clientWith()
        chat.start(displayName = "Layla")
        assertFailsWith<CnctException> { chat.send("Book", attachmentIds = listOf("att_1"), replyId = "c1") }
        val key = chat.state.value.failed.single().clientKey!!

        refuse = false
        chat.retry(key)

        val (first, second) = posted("/api/chat/public/messages").map { it.json }
        assertEquals(first, second)
        assertEquals("c1", second["replyId"]!!.jsonPrimitive.content)
        assertTrue(chat.state.value.failed.isEmpty())
    }

    // ── Files ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an upload is multipart with one part named file, and reports its progress to 1`() = runBlocking {
        val received = CopyOnWriteArrayList<RecordedRequest>()
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                return when (request.path) {
                    "/api/chat/public/$PUBLIC_KEY/session" -> MockResponse().setBody(SESSION_WITH_ID)
                    "/api/chat/public/attachments" -> MockResponse().setResponseCode(201).setBody(ATTACHMENT)
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        }
        mock.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val config = CnctConfig(baseUrl = mock.url("/").toString())
            val chat = CnctChatClient(
                config = config,
                credentials = CnctChatPublicKey(PUBLIC_KEY),
                socketFactory = socket.factory,
                scope = scope,
            )
            chat.start(displayName = "Layla")
            val progress = CopyOnWriteArrayList<Float>()
            val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }

            val attachment = chat.uploadAttachment(bytes, "فاتورة.pdf", "application/pdf") { progress += it }

            assertEquals("att_9", attachment.id)
            val upload = received.single { it.path == "/api/chat/public/attachments" }
            assertEquals("POST", upload.method)
            assertEquals("visitor-token-1", upload.getHeader("x-chat-token"))
            assertNull(upload.getHeader("authorization"))
            val contentType = upload.getHeader("content-type")!!
            assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType)
            val boundary = contentType.substringAfter("boundary=")
            val raw = upload.body.readByteArray()
            val head = String(raw, 0, 400, Charsets.UTF_8)
            assertTrue(head.startsWith("--$boundary\r\n"), head)
            // One part, named `file`, with its own name and type — an Arabic file name included.
            assertTrue(head.contains("Content-Disposition: form-data; name=\"file\"; filename=\"فاتورة.pdf\""), head)
            assertTrue(head.contains("Content-Type: application/pdf"), head)
            assertEquals(1, String(raw, Charsets.ISO_8859_1).split("--$boundary\r\n").size - 1)
            assertTrue(raw.size > bytes.size)

            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertEquals(progress.sorted(), progress.toList())
            assertTrue(progress.size > 3, "progress was reported as it went, not only at the ends: $progress")
        } finally {
            scope.cancel()
            mock.shutdown()
        }
    }

    @Test
    fun `a file is checked before a byte is sent`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        seen.clear()

        val big = assertFailsWith<CnctException> {
            chat.uploadAttachment(CnctAttachmentRules.MAX_BYTES + 1, "film.mp4", "video/mp4") { error("never opened") }
        }
        assertEquals(CnctErrorCode.TOO_LARGE, big.code)
        val heic = assertFailsWith<CnctException> { chat.uploadAttachment(ByteArray(4), "IMG_0001.HEIC", "image/heic") }
        assertEquals(CnctErrorCode.INVALID, heic.code)
        assertTrue(heic.message.contains("JPEG"))
        val exe = assertFailsWith<CnctException> { chat.uploadAttachment(ByteArray(4), "setup.exe", "application/x-msdownload") }
        assertEquals(CnctErrorCode.INVALID, exe.code)

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an upload before a session is a named refusal`() = runTest {
        val error = assertFailsWith<CnctException> { clientWith().uploadAttachment(ByteArray(4), "a.png", "image/png") }
        assertEquals(CnctErrorCode.NO_SESSION, error.code)
    }

    @Test
    fun `the rules know the types, the limits and the spellings`() {
        assertTrue(CnctAttachmentRules.isAccepted("image/jpeg"))
        assertTrue(CnctAttachmentRules.isAccepted("IMAGE/JPG"))
        assertTrue(CnctAttachmentRules.isAccepted("application/octet-stream", "Invoice.PDF"))
        assertTrue(CnctAttachmentRules.isAccepted(null, "sheet.xlsx"))
        assertTrue(CnctAttachmentRules.isAccepted("text/csv; charset=utf-8"))
        assertFalse(CnctAttachmentRules.isAccepted("image/heic"))
        assertTrue(CnctAttachmentRules.needsConversion("image/heif"))
        assertTrue(CnctAttachmentRules.needsConversion(null, "IMG_0001.heic"))
        assertFalse(CnctAttachmentRules.isAccepted("application/zip"))
        assertNull(CnctAttachmentRules.check("a.pdf", CnctAttachmentRules.MAX_BYTES, "application/pdf"))
        assertEquals(10, CnctAttachmentRules.MAX_FILES)
    }

    @Test
    fun `the server's refusals map to the usual codes, keep its sentence, and never clear the session`() = runTest {
        var status = 409
        answers["/api/chat/public/attachments"] = { FakeHttp.Answer(status, """{"error":"A sentence for a person."}""") }
        val store = CnctTokenStore.memory()
        val chat = clientWith(store)
        chat.start(displayName = "Layla")

        val expected = mapOf(
            403 to CnctErrorCode.UNAUTHENTICATED,
            413 to CnctErrorCode.TOO_LARGE,
            422 to CnctErrorCode.INVALID,
            503 to CnctErrorCode.SERVER_ERROR,
            409 to CnctErrorCode.CONVERSATION_CLOSED,
        )
        for ((answer, code) in expected) {
            status = answer
            val error = assertFailsWith<CnctException> { chat.uploadAttachment(ByteArray(4), "a.png", "image/png") }
            assertEquals(code, error.code, "for $answer")
            assertEquals("A sentence for a person.", error.message)
            assertEquals(answer, error.status)
            assertTrue(chat.hasSession, "after $answer")
        }
        assertEquals("visitor-token-1", store.read("cnct_chat_$PUBLIC_KEY"))
        // 409 is the conversation ending under them, and the screen says so.
        assertEquals(CnctChatStatus.ENDED, chat.state.value.status)
        assertEquals(CnctConversationStatus.CLOSED, chat.state.value.conversation?.status)
    }

    // ── Signed-in users ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `identify carries the current token, and its token replaces the stored one`() = runTest {
        answers["/api/chat/public/$PUBLIC_KEY/identify"] = {
            FakeHttp.Answer(200, """{"token":"signed-in-token","visitor":{"displayName":"Layla","anonymous":false,"identified":true}}""")
        }
        val store = CnctTokenStore.memory()
        store.write("cnct_chat_$PUBLIC_KEY", "anonymous-token")
        val chat = clientWith(store)

        val visitor = chat.identify(CnctChatIdentity("user_42", "abc123", name = "Layla", email = " layla@example.com "))

        assertTrue(visitor.identified)
        val call = posted("/api/chat/public/$PUBLIC_KEY/identify").single()
        // The anonymous token goes with it, so a chat started before sign-in moves across.
        assertEquals("anonymous-token", call.headers["x-chat-token"])
        assertEquals(
            """{"userId":"user_42","userHash":"abc123","name":"Layla","email":"layla@example.com"}""",
            call.body,
        )
        assertEquals("signed-in-token", store.read("cnct_chat_$PUBLIC_KEY"))

        chat.resume()
        assertEquals("signed-in-token", seen.last().headers["x-chat-token"])
    }

    @Test
    fun `identify on an open conversation reconnects as the signed-in visitor and reloads`() = runTest {
        answers["/api/chat/public/$PUBLIC_KEY/identify"] = { FakeHttp.Answer(200, """{"token":"signed-in-token","visitor":{"identified":true}}""") }
        val chat = clientWith()
        chat.start(displayName = "Layla")
        goLive()
        seen.clear()

        chat.identify(CnctChatIdentity("user_42", "abc123"))

        assertEquals(1000, socket.closed)
        assertEquals(2, socket.opened)
        assertEquals("signed-in-token", frame(socket.sent.last())["token"]!!.jsonPrimitive.content)
        val reload = seen.single { it.path == "/api/chat/public/session" }
        assertEquals("signed-in-token", reload.headers["x-chat-token"])
        assertEquals(1, chat.state.value.messages.size)
    }

    @Test
    fun `a failed identify leaves the anonymous session exactly as it was`() = runTest {
        var status = 401
        answers["/api/chat/public/$PUBLIC_KEY/identify"] = { FakeHttp.Answer(status, """{"error":"That signature did not verify."}""") }
        val store = CnctTokenStore.memory()
        val chat = clientWith(store)
        chat.start(displayName = "Layla")

        assertEquals(CnctErrorCode.UNAUTHENTICATED, assertFailsWith<CnctException> { chat.identify(CnctChatIdentity("u", "bad")) }.code)
        status = 409
        assertEquals(CnctErrorCode.CONFLICT, assertFailsWith<CnctException> { chat.identify(CnctChatIdentity("u", "h")) }.code)

        assertTrue(chat.hasSession)
        assertEquals("visitor-token-1", store.read("cnct_chat_$PUBLIC_KEY"))
        assertEquals(1, socket.opened)
    }

    @Test
    fun `an identity never prints its hash`() {
        val identity = CnctChatIdentity("user_123456", "0123456789abcdef", email = "layla@example.com")
        assertFalse(identity.toString().contains("0123456789abcdef"))
        assertFalse(identity.toString().contains("layla@example.com"))
        assertEquals(CnctErrorCode.INVALID, assertFailsWith<CnctException> { CnctChatIdentity("u", " ") }.code)
    }

    // ── The visitor's conversations ──────────────────────────────────────────────────────────────

    @Test
    fun `the conversations list, and one conversation with its messages`() = runTest {
        answers["/api/chat/public/conversations"] = {
            FakeHttp.Answer(
                200,
                """{"visitor":{"displayName":"Layla","anonymous":false},"conversations":[
                    {"id":"c_2","status":"OPEN","startedAt":"2026-11-09T09:00:00.000Z","lastMessageAt":"2026-11-09T09:05:00.000Z",
                     "assignee":{"kind":"person","name":"Sara","avatar":null},
                     "preview":${messageJson(id = "p", speaker = "OPERATOR", body = "Thanks")}},
                    {"id":"c_1","status":"CLOSED","startedAt":"2026-11-01T09:00:00.000Z","closedAt":"2026-11-01T09:30:00.000Z",
                     "rating":{"score":5,"comment":null},"ratable":false}]}""",
            )
        }
        answers["/api/chat/public/conversations/c_0"] = {
            FakeHttp.Answer(
                200,
                """{"conversation":{"id":"c_0","status":"CLOSED","startedAt":"2026-11-01T09:00:00.000Z"},
                    "messages":[${messageJson(id = "x1", body = "Old")},${messageJson(id = "x2", body = "Older")}]}""",
            )
        }
        val chat = clientWith()
        chat.start(displayName = "Layla")

        val list = chat.conversations()
        assertEquals(listOf("c_2", "c_1"), list.map { it.id })
        assertEquals("Sara", list.first().assignee?.name)
        assertEquals("Thanks", list.first().preview?.body)
        assertEquals(5, list.last().rating?.score)
        assertFalse(list.last().isLive)
        assertEquals(Instant.parse("2026-11-01T09:30:00Z"), list.last().closedAt)

        val old = chat.conversation("c_0")
        assertEquals(listOf("Old", "Older"), old.messages.map { it.body })
        assertEquals("c_0", old.conversation.id)
        // An old conversation is read, not opened: the one on screen is still the live one.
        assertEquals("c_1", chat.state.value.conversation?.id)
        assertEquals(listOf("Hello — how can we help?"), chat.state.value.messages.map { it.body })
    }

    @Test
    fun `reading the conversations with a dead token forgets it`() = runTest {
        answers["/api/chat/public/conversations"] = { FakeHttp.Answer(401, """{"error":"expired"}""") }
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val events = eventsOf(chat)

        assertFailsWith<CnctException> { chat.conversations() }

        assertFalse(chat.hasSession)
        assertEquals(listOf<CnctChatEvent>(CnctChatEvent.Unauthenticated), events)
    }

    // ── Read ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `read is posted once per team reply, and again only for a newer one`() = runTest {
        answers["/api/chat/public/conversations/c_1/read"] = { FakeHttp.Answer(204, "") }
        val chat = clientWith()
        chat.start(displayName = "Layla")

        assertTrue(chat.read())
        assertFalse(chat.read())
        assertFalse(chat.read())
        assertEquals(1, posted("/api/chat/public/conversations/c_1/read").size)

        // The visitor's own words are nothing to report.
        chat.send("Thanks")
        assertFalse(chat.read())

        socket.push("""{"type":"chat.message","payload":{"message":${messageJson(id = "reply_2", speaker = "OPERATOR", body = "You're welcome")}}}""")
        runCurrent()
        assertTrue(chat.read())
        assertFalse(chat.read())
        assertEquals(2, posted("/api/chat/public/conversations/c_1/read").size)
        assertEquals("", posted("/api/chat/public/conversations/c_1/read").last().body)
    }

    @Test
    fun `a read that failed is tried again`() = runTest {
        var status = 500
        answers["/api/chat/public/conversations/c_1/read"] = { FakeHttp.Answer(status, if (status == 204) "" else """{"error":"boom"}""") }
        val chat = clientWith()
        chat.start(displayName = "Layla")

        assertFailsWith<CnctException> { chat.read() }
        status = 204
        assertTrue(chat.read())
        assertEquals(2, posted("/api/chat/public/conversations/c_1/read").size)
    }

    // ── Rating and profile ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a rating is the score, then the comment as a second answer`() = runTest {
        answers["/api/chat/public/conversations/c_1/rating"] = { request ->
            FakeHttp.Answer(200, """{"conversation":{"id":"c_1","status":"CLOSED","rating":${request.body},"ratable":true}}""")
        }
        val chat = clientWith()
        chat.start(displayName = "Layla")

        val rated = chat.rate(4)
        assertEquals("""{"score":4}""", posted("/api/chat/public/conversations/c_1/rating").last().body)
        assertEquals(4, rated.rating?.score)
        assertEquals(4, chat.state.value.conversation?.rating?.score)
        // Fields the answer left out are kept, not reset.
        assertEquals(Instant.parse("2026-09-08T09:59:00.000Z"), chat.state.value.conversation?.startedAt)

        chat.rate(4, comment = "Quick and kind.")
        assertEquals("""{"score":4,"comment":"Quick and kind."}""", posted("/api/chat/public/conversations/c_1/rating").last().body)
        assertEquals("Quick and kind.", chat.state.value.conversation?.rating?.comment)

        assertEquals(CnctErrorCode.INVALID, assertFailsWith<CnctException> { chat.rate(6) }.code)
    }

    @Test
    fun `a rating too late is a conflict`() = runTest {
        answers["/api/chat/public/conversations/c_1/rating"] = { FakeHttp.Answer(409, """{"error":"It is too late to rate this."}""") }
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val error = assertFailsWith<CnctException> { chat.rate(5) }
        assertEquals(CnctErrorCode.CONFLICT, error.code)
        assertEquals("It is too late to rate this.", error.message)
    }

    @Test
    fun `the profile saves a name and a phone number`() = runTest {
        answers["/api/chat/public/profile"] = { FakeHttp.Answer(200, """{"visitor":{"displayName":"Layla","anonymous":false}}""") }
        val chat = clientWith()
        chat.start()

        val visitor = chat.updateProfile(" Layla ", phone = "+973 3311 2233")

        assertEquals("""{"displayName":"Layla","phone":"+973 3311 2233"}""", posted("/api/chat/public/profile").single().body)
        assertFalse(visitor.anonymous)
        assertEquals("Layla", chat.state.value.visitor?.displayName)
        assertEquals(CnctErrorCode.EMPTY, assertFailsWith<CnctException> { chat.updateProfile(" ") }.code)
    }

    // ── Frames ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `chat message updated replaces the message in place`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val events = eventsOf(chat)
        socket.push("""{"type":"chat.message","payload":{"message":${messageJson(id = "m2", speaker = "OPERATOR", body = "Here is the file")}}}""")
        runCurrent()

        socket.push("""{"type":"chat.message.updated","payload":{"message":${messageJson(id = "m2", speaker = "OPERATOR", body = "Here is the file (edited)")}}}""")
        socket.push("""{"type":"chat.message.updated","payload":{"message":${messageJson(id = "never_seen", body = "Not news")}}}""")
        runCurrent()

        assertEquals(listOf("msg_1", "m2"), chat.state.value.messages.map { it.id })
        assertEquals("Here is the file (edited)", chat.state.value.messages.last().body)
        assertEquals("Here is the file (edited)", assertIs<CnctChatEvent.MessageUpdated>(events.last()).message.body)
        assertEquals(1, events.filterIsInstance<CnctChatEvent.MessageUpdated>().size)
    }

    @Test
    fun `chat read says Seen under the visitor's last words, and only those`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        chat.send("Are you there?")
        val events = eventsOf(chat)
        assertFalse(chat.state.value.seen)

        socket.push("""{"type":"chat.read","payload":{"at":"2026-09-08T10:00:01.000Z"}}""")
        runCurrent()

        assertEquals(Instant.parse("2026-09-08T10:00:01Z"), chat.state.value.conversation?.seenAt)
        assertTrue(chat.state.value.seen)
        assertEquals(Instant.parse("2026-09-08T10:00:01Z"), assertIs<CnctChatEvent.Read>(events.single()).at)

        // A read from before they spoke does not cover it.
        socket.push("""{"type":"chat.read","payload":{"at":"2026-09-08T09:00:00.000Z"}}""")
        runCurrent()
        assertFalse(chat.state.value.seen)
    }

    @Test
    fun `a flat conversation update patches what it names and keeps the rest`() = runTest {
        val chat = clientWith()
        chat.start(displayName = "Layla")
        val events = eventsOf(chat)

        socket.push("""{"type":"chat.conversation.updated","payload":{"withPerson":true,"assignee":{"kind":"person","name":"Sara"}}}""")
        runCurrent()

        val conversation = chat.state.value.conversation!!
        assertEquals("c_1", conversation.id)
        assertEquals(CnctConversationStatus.OPEN, conversation.status)
        assertTrue(conversation.withPerson)
        assertEquals("Sara", conversation.assignee?.name)
        assertIs<CnctChatEvent.ConversationUpdated>(events.single())

        socket.push("""{"type":"chat.conversation.updated","payload":{"assignee":null,"withPerson":false}}""")
        runCurrent()
        assertNull(chat.state.value.conversation?.assignee)
    }

    @Test
    fun `when it closes, the client asks the server whether it can be rated`() = runTest {
        answers["/api/chat/public/conversations"] = {
            FakeHttp.Answer(200, """{"conversations":[{"id":"c_1","status":"CLOSED","closedAt":"2026-09-08T11:00:00.000Z","ratable":true}]}""")
        }
        val chat = clientWith()
        chat.start(displayName = "Layla")

        socket.push("""{"type":"chat.closed"}""")
        runCurrent()

        val conversation = chat.state.value.conversation!!
        assertEquals(CnctChatStatus.ENDED, chat.state.value.status)
        assertEquals(CnctConversationStatus.CLOSED, conversation.status)
        assertTrue(conversation.ratable)
        assertEquals(Instant.parse("2026-09-08T11:00:00Z"), conversation.closedAt)
        assertFalse(chat.state.value.isLive)
    }

    @Test
    fun `ending it is the same, from the visitor's side`() = runTest {
        answers["/api/chat/public/conversations"] = { FakeHttp.Answer(200, """{"conversations":[{"id":"c_1","status":"CLOSED","ratable":true}]}""") }
        val chat = clientWith()
        chat.start(displayName = "Layla")

        chat.end()
        runCurrent()

        assertEquals(CnctChatStatus.ENDED, chat.state.value.status)
        assertTrue(chat.state.value.conversation!!.ratable)
        assertTrue(chat.state.value.conversation!!.closedAt != null)
    }

    private companion object {
        val INBOX = """
            {"business":"Your business","inbox":"Website","isActive":true,"greeting":"Hello. How can we help?","requirePhone":false,
             "widget":{"brand":{"color":"#d7262e","appearance":"auto","businessName":null,"avatarUrl":"https://cdn.test/logo.png",
                                "language":"ar","showBranding":false},
                       "launcher":{"side":"left"},
                       "chat":{"allowEndConversation":true,"start":"message"}},
             "team":[{"kind":"person","name":"Sara","avatar":"fox-clay"},{"kind":"person","name":"Omar","avatar":null}],
             "availability":{"open":false,"opensAt":"2026-11-10T05:00:00.000Z","assistant":true,"replyMinutes":null}}
        """

        val SESSION_WITH_ID = SESSION.replace("\"status\":\"OPEN\"", "\"id\":\"c_1\",\"status\":\"OPEN\"")

        const val ATTACHMENT = """{"attachment":{"id":"att_9","kind":"IMAGE","mimeType":"image/jpeg","filename":"photo.jpg","bytes":10}}"""
    }
}
