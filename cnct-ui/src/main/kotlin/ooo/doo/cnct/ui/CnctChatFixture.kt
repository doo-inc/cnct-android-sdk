package ooo.doo.cnct.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import ooo.doo.cnct.CnctChatClient
import ooo.doo.cnct.CnctChatPublicKey
import ooo.doo.cnct.CnctConfig
import ooo.doo.cnct.CnctException
import ooo.doo.cnct.CnctLanguage
import ooo.doo.cnct.CnctSocket
import ooo.doo.cnct.CnctSocketFactory
import ooo.doo.cnct.CnctSocketListener
import ooo.doo.cnct.CnctTokenStore
import ooo.doo.cnct.CnctTransport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import okio.blackholeSink
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The scripted conversation every CNCT SDK renders the same way — for screenshots, for tests, for a
 * demo with no key — served by an in-process fake of the platform and its socket. **Nothing here
 * touches a network**, and nothing here is a mock of the screen: it is the real [CnctChat] drawing a
 * real [CnctChatClient], whose server happens to be fake.
 *
 * The conversation: the greeting; "Do you open on Fridays?"; the assistant's answer with two flow
 * buttons; a photo; Sara's invoice; a line with a phone number in it; a message that failed and
 * offers a retry; the team typing; and a composer with two files picked, a photo at 40% and a PDF
 * done. In English or Arabic.
 */
public object CnctChatFixture {
    /**
     * The fixture conversation on screen. [theme] and [onClose] are [CnctChat]'s; the language is
     * pinned to [language].
     */
    @Composable
    public fun Chat(
        modifier: Modifier = Modifier,
        language: CnctLanguage = CnctLanguage.EN,
        theme: CnctChatTheme? = null,
        onClose: (() -> Unit)? = null,
        onEvent: ((CnctChatUIEvent) -> Unit)? = null,
    ) {
        Chat(modifier, language, theme, onClose, onEvent, fresh = false)
    }

    /** [fresh]: a visitor who has never chatted, so the screen opens on the name form. */
    @Composable
    internal fun Chat(
        modifier: Modifier,
        language: CnctLanguage,
        theme: CnctChatTheme?,
        onClose: (() -> Unit)?,
        onEvent: ((CnctChatUIEvent) -> Unit)?,
        fresh: Boolean,
    ) {
        val application = LocalContext.current.applicationContext as Application
        val holder = viewModel(key = "cnct-fixture-${language.name}-$fresh") { holder(application, language, fresh) }
        ChatHost(holder, modifier, theme, language, null, onClose, onEvent)
    }

    /**
     * A headless client already holding the fixture conversation's session, for a test or a preview
     * of your own interface. Close it, or cancel [scope], when done; that also releases the photo the
     * fake server holds at 40%.
     */
    public fun client(context: Context, language: CnctLanguage, scope: CoroutineScope): CnctChatClient =
        FixtureServer(context.applicationContext, language).client(scope)

    private fun holder(application: Application, language: CnctLanguage, fresh: Boolean): ChatHolder {
        val server = FixtureServer(application, language)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = server.client(scope, fresh)
        return ChatHolder(
            application,
            client,
            ownsClient = true,
            identity = null,
            onCleared = {
                server.close()
                scope.cancel()
            },
            afterOpen = { controller -> if (!fresh) server.prime(controller, scope) },
        )
    }
}

/** The platform, as far as the fixture is concerned: canned answers, in process, in one language. */
private class FixtureServer(private val context: Context, private val language: CnctLanguage) {
    private val ar = language == CnctLanguage.AR
    private val now = Instant.now()
    private val held = CountDownLatch(1)
    private val ids = AtomicInteger()
    private val uploaded = HashMap<String, JsonObject>()
    private var listener: CnctSocketListener? = null

    private fun words(en: String, arabic: String) = if (ar) arabic else en
    private fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()

    val failing = words("Can I pay by card?", "هل يمكنني الدفع بالبطاقة؟")
    private val business = words("Your business", "نشاطك التجاري")
    private val sara = buildJsonObject {
        put("kind", "person")
        put("name", words("Sara", "سارة"))
        put("avatar", "fox-clay")
    }
    private val assistant = buildJsonObject {
        put("kind", "ai")
        put("name", words("Assistant", "المساعد"))
        put("mascot", "drop")
    }

    private val inbox = buildJsonObject {
        put("business", business)
        put("inbox", "Website")
        put("isActive", true)
        put("greeting", words("Hello. How can we help?", "مرحبًا. كيف يمكننا مساعدتك؟"))
        put("requirePhone", false)
        putJsonObject("widget") {
            putJsonObject("brand") {
                put("color", "#b52d93")
                put("appearance", "auto")
                put("language", "auto")
                put("showBranding", true)
            }
            putJsonObject("chat") {
                put("allowAttachments", true)
                put("allowEndConversation", true)
                put("start", "details")
                put("askForRating", true)
            }
        }
        put("team", buildJsonArray { add(sara) })
        putJsonObject("availability") {
            put("open", true)
            put("assistant", true)
            put("replyMinutes", 4)
        }
    }

    private val conversation = buildJsonObject {
        put("id", "fixture")
        put("status", "OPEN")
        put("startedAt", ago(14))
        put("withPerson", true)
        put("assignee", sara)
        put("ratable", false)
    }

    private fun message(
        id: String,
        speaker: String,
        body: String,
        minutesAgo: Long,
        sender: JsonObject? = null,
        attachments: List<JsonObject> = emptyList(),
        extra: JsonObject.() -> JsonObject = { this },
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("speaker", speaker)
        put("type", "TEXT")
        put("body", body)
        put("createdAt", ago(minutesAgo))
        if (sender != null) put("sender", sender)
        put("attachments", JsonArray(attachments))
    }.extra()

    private fun attachment(id: String, kind: String, mime: String, name: String, bytes: Long) = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("mimeType", mime)
        put("filename", name)
        put("bytes", bytes)
    }

    private val transcript: List<JsonObject> = listOf(
        message("m1", "SYSTEM", words("Hello. How can we help?", "مرحبًا. كيف يمكننا مساعدتك؟"), 14),
        message("m2", "CALLER", words("Do you open on Fridays?", "هل تفتحون يوم الجمعة؟"), 13),
        message(
            "m3",
            "AI",
            words(
                "Yes, from 2pm to 10pm. Would you like to book a visit?\n1. Book a visit\n2. Talk to a person",
                "نعم، من الساعة 2 ظهرًا حتى 10 مساءً. هل تود حجز زيارة؟\n1. احجز زيارة\n2. تحدث مع موظف",
            ),
            12,
            sender = assistant,
        ) {
            JsonObject(
                this + ("flow" to buildJsonObject {
                    put("text", words("Yes, from 2pm to 10pm. Would you like to book a visit?", "نعم، من الساعة 2 ظهرًا حتى 10 مساءً. هل تود حجز زيارة؟"))
                    put("layout", "buttons")
                    put(
                        "choices",
                        buildJsonArray {
                            add(buildJsonObject { put("id", "book"); put("title", words("Book a visit", "احجز زيارة")) })
                            add(buildJsonObject { put("id", "person"); put("title", words("Talk to a person", "تحدث مع موظف")) })
                        },
                    )
                }),
            )
        },
        message("m4", "CALLER", "", 9, attachments = listOf(attachment("att_photo", "IMAGE", "image/jpeg", "photo.jpg", 1_200_000))),
        message(
            "m5",
            "OPERATOR",
            words("Thanks, here is your invoice.", "شكرًا، هذه فاتورتك."),
            5,
            sender = sara,
            attachments = listOf(attachment("att_invoice", "DOCUMENT", "application/pdf", "invoice.pdf", 220_000)),
        ),
        message("m6", "CALLER", words("Call me on +973 3311 2233 please", "اتصلوا بي على +973 3311 2233 من فضلك"), 2),
    )

    private val session = buildJsonObject {
        put("visitor", buildJsonObject { put("displayName", "Layla"); put("anonymous", false) })
        put("conversation", conversation)
        put("messages", JsonArray(transcript))
    }

    private fun answer(request: okhttp3.Request): Pair<Int, ByteArray> {
        val path = request.url.encodedPath
        fun ok(body: JsonObject, status: Int = 200) = status to body.toString().toByteArray()
        return when {
            path.endsWith("/session") && request.method == "GET" -> ok(session)
            path.endsWith("/session") -> ok(JsonObject(session + ("token" to JsonPrimitive("fixture-token"))))
            path == "/api/chat/public/messages" -> {
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                val sent = Json.parseToJsonElement(body).jsonObject
                val words = sent["body"]?.jsonPrimitive?.content.orEmpty()
                if (words == failing) {
                    503 to """{"error":"The fixture refuses this one, so it can show a retry."}""".toByteArray()
                } else {
                    val files = sent["attachmentIds"]?.jsonArray?.mapNotNull { uploaded[it.jsonPrimitive.content] }.orEmpty()
                    ok(
                        message("v${ids.incrementAndGet()}", "CALLER", words, 0, attachments = files) {
                            JsonObject(this + ("clientKey" to sent["clientKey"]!!) + ("createdAt" to JsonPrimitive(Instant.now().toString())))
                        },
                    )
                }
            }
            path == "/api/chat/public/attachments" -> upload(request)
            path == "/api/chat/public/attachments/att_photo" -> 200 to landscape(1200, 800, 88)
            path == "/api/chat/public/attachments/att_invoice" -> 200 to INVOICE.toByteArray()
            path.startsWith("/api/chat/public/attachments/") -> {
                val id = path.substringAfterLast('/')
                if (id.startsWith("up_")) 200 to landscape(800, 600, 85) else 404 to """{"error":"not found"}""".toByteArray()
            }
            path == "/api/chat/public/conversations" -> ok(
                buildJsonObject {
                    put("conversations", buildJsonArray { add(JsonObject(conversation + ("status" to JsonPrimitive("CLOSED")) + ("ratable" to JsonPrimitive(true)))) })
                },
            )
            path.endsWith("/read") -> 204 to ByteArray(0)
            path.endsWith("/rating") -> {
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                ok(buildJsonObject { put("conversation", buildJsonObject { put("id", "fixture"); put("rating", Json.parseToJsonElement(body)); put("ratable", true) }) })
            }
            path == "/api/chat/public/close" -> ok(buildJsonObject { put("ok", true) })
            path == "/api/chat/public/profile" -> ok(buildJsonObject { put("visitor", buildJsonObject { put("displayName", "Layla") }) })
            path.startsWith("/api/chat/public/") && request.method == "GET" -> ok(inbox)
            else -> 404 to """{"error":"not found"}""".toByteArray()
        }
    }

    /**
     * Files up. The tray's photo is held at 40% — its bytes are read that far and no further, so the
     * progress the screen shows is the client's real progress — until the fixture is closed.
     */
    private fun upload(request: okhttp3.Request): Pair<Int, ByteArray> {
        val multipart = request.body as MultipartBody
        val part = multipart.parts.first()
        val disposition = part.headers?.get("Content-Disposition").orEmpty()
        val name = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1) ?: "file"
        val total = multipart.contentLength()
        val holdAt = if (name == HELD_PHOTO) (total * 0.41).toLong() else Long.MAX_VALUE
        var written = 0L
        val sink: Sink = object : ForwardingSink(blackholeSink()) {
            override fun write(source: Buffer, byteCount: Long) {
                if (written >= holdAt) {
                    held.await()
                    throw IOException("The fixture was closed.")
                }
                super.write(source, byteCount)
                written += byteCount
            }
        }
        sink.buffer().use { multipart.writeTo(it) }
        val type = part.body.contentType()?.toString() ?: "application/octet-stream"
        val id = "up_${ids.incrementAndGet()}"
        val kind = if (type.startsWith("image/")) "IMAGE" else "DOCUMENT"
        val stored = attachment(id, kind, type, name, part.body.contentLength())
        uploaded[id] = stored
        return 201 to buildJsonObject { put("attachment", stored) }.toString().toByteArray()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (status, body) = answer(request)
                val type = if (body.isNotEmpty() && (body[0] == '{'.code.toByte() || body[0] == '['.code.toByte())) "application/json" else "application/octet-stream"
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fixture")
                    .body(body.toResponseBody(type.toMediaType()))
                    .build()
            }
            .build()
    }

    /** The socket: authenticates at once, offers no socket sends (so the failing send goes over HTTP), and types. */
    private val sockets = CnctSocketFactory { _, _, listener ->
        this.listener = listener
        object : CnctSocket {
            override fun send(text: String): Boolean {
                if (text.contains("\"auth\"")) listener.onMessage("""{"type":"authenticated","accepts":["ping","typing"]}""")
                return true
            }

            override fun close(code: Int, reason: String?) {
                listener.onClosed(code, null)
            }
        }
    }

    fun client(scope: CoroutineScope, fresh: Boolean = false): CnctChatClient {
        val config = CnctConfig(baseUrl = "https://fixture.cnct.invalid")
        val store = CnctTokenStore.memory()
        if (!fresh) kotlinx.coroutines.runBlocking { store.write("cnct_chat_fixture", "fixture-token") }
        return CnctChatClient(
            config = config,
            credentials = CnctChatPublicKey("fixture"),
            tokenStore = store,
            storageKey = "cnct_chat_fixture",
            transport = CnctTransport(config, http),
            socketFactory = sockets,
            scope = scope,
        )
    }

    /** After the screen opened: the send that fails, the team typing, and the two files in the tray. */
    suspend fun prime(controller: ChatController, scope: CoroutineScope) {
        try {
            controller.client.send(failing)
        } catch (_: CnctException) {
            // Exactly what the fixture is for: a failed bubble, with its retry.
        }
        scope.launch {
            // The platform sends a start and never a stop; say it again before it clears.
            while (true) {
                listener?.onMessage("""{"type":"chat.typing"}""")
                delay(3_000)
            }
        }
        val photo = landscape(1024, 768, 95)
        controller.addFiles(
            listOf(
                PickedFile(HELD_PHOTO, "image/jpeg", photo.size.toLong()) { photo.inputStream() },
                PickedFile(words("menu.pdf", "القائمة.pdf"), "application/pdf", INVOICE.length.toLong()) { INVOICE.byteInputStream() },
            ),
        ) { file -> thumbnail(file) }
    }

    fun close() {
        held.countDown()
    }

    /** A landscape to look at: sky, sun, sea and hills, drawn rather than shipped. */
    private fun landscape(width: Int, height: Int, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height * 0.62f, 0xFFF6A86B.toInt(), 0xFFF9D6A5.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.62f, paint)
        paint.shader = null
        paint.color = 0xFFFFF1D6.toInt()
        canvas.drawCircle(width * 0.68f, height * 0.42f, height * 0.11f, paint)
        paint.shader = LinearGradient(0f, height * 0.6f, 0f, height.toFloat(), 0xFF3A7CA5.toInt(), 0xFF1F4E6B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height * 0.6f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFF2F4A3A.toInt()
        val hills = Path().apply {
            moveTo(0f, height * 0.64f)
            quadTo(width * 0.18f, height * 0.44f, width * 0.36f, height * 0.62f)
            quadTo(width * 0.5f, height * 0.52f, width * 0.62f, height * 0.63f)
            lineTo(0f, height * 0.66f)
            close()
        }
        canvas.drawPath(hills, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    companion object {
        const val HELD_PHOTO = "IMG_2041.jpg"

        /** A one-page PDF that says what it is. */
        val INVOICE = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
            4 0 obj << /Length 44 >> stream
            BT /F1 18 Tf 24 72 Td (Invoice - fixture) Tj ET
            endstream endobj
            5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
        """.trimIndent()
    }
}
