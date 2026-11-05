package ooo.doo.cnct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Live chat with a CNCT inbox, from an Android app or anything else on the JVM.
 *
 * **Headless on purpose.** There is no view in here, not one, and that is the reason to reach for
 * this rather than an embedded web view: a business building their own app has a design system
 * already, and shipping them a bubble to restyle answers a question they did not ask. This ships
 * state and events; the interface is yours.
 *
 * Three properties are load-bearing and you get them without having to know:
 *
 * **A reconnect reloads.** The platform's fan-out replays nothing after a drop — deliberately — so
 * this client refetches the whole transcript on every reconnect rather than splicing a gap it cannot
 * measure. A UI that renders [state] is always rendering the truth.
 *
 * **A send is idempotent.** Every message carries a `clientKey` that is unique per conversation on
 * the server, so a frame replayed across a reconnect returns the row it already wrote instead of
 * writing a second one. That is what makes [send] safe to reject on a timeout and [retry] safe to
 * call.
 *
 * **It degrades rather than breaks.** The server announces what it accepts on the handshake. Sends go
 * over the socket where that is offered and fall back to HTTP where it is not — which covers both an
 * older server and the ordinary case of the socket being between connections.
 *
 * ```kotlin
 * val chat = CnctChatClient(
 *     config = CnctConfig(), // https://app.doo.ooo
 *     credentials = CnctChatPublicKey("the-inbox-public-key"),
 *     scope = viewModelScope,
 * )
 * chat.state.collect(::render)
 * val inbox = chat.boot()
 * if (inbox.isActive) {
 *     chat.start(displayName = "Layla")
 *     chat.send("Do you open on Fridays?")
 * }
 * ```
 *
 * **Threads.** Every method is safe to call from any thread. The client's own work — frames,
 * timers, the transcript — runs one piece at a time on [scope]'s dispatcher, so it never races
 * itself; HTTP and the socket run on OkHttp's threads. Pass `viewModelScope` (or any scope) and the
 * client lives as long as it does: when that scope is cancelled the socket is closed, as if [close]
 * had been called. Pass `runTest`'s `backgroundScope` and its timers run on virtual time.
 */
public class CnctChatClient(
    config: CnctConfig,
    credentials: CnctChatPublicKey,
    /**
     * Where the visitor's session token is kept between launches. The default is memory, which means
     * a returning customer arrives as a stranger — on Android, pass the Keystore-backed store from
     * `cnct-android`.
     */
    tokenStore: CnctTokenStore = CnctTokenStore.memory(),
    /** The key the token is stored under. Defaults to one per inbox. */
    storageKey: String? = null,
    transport: CnctTransport = CnctTransport(config),
    socketFactory: CnctSocketFactory = CnctSocketFactory.okHttp(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : CnctClient {
    private val config: CnctConfig = config
    private val publicKey: String = credentials.publicKey
    private val redactedKey: String = credentials.redacted
    private val store: CnctTokenStore = tokenStore
    private val storageKey: String = storageKey ?: "cnct_chat_${credentials.publicKey}"
    private val transport: CnctTransport = transport
    private val socketFactory: CnctSocketFactory = socketFactory
    private val random = Random.Default

    /**
     * This client's own lifetime: a child of [scope]'s job, so cancelling the scope ends the client,
     * and a supervisor, so one timer failing does not take the others with it.
     */
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])

    /**
     * The one lane all of the client's own state moves along. Single file, so a frame, a timer and a
     * call never interleave except where they suspend — the same guarantee the Flutter client gets
     * from Dart's event loop, and the reason none of the fields below needs a lock.
     */
    private val confined: CoroutineDispatcher = confine(scope.coroutineContext[ContinuationInterceptor])

    private val inside: CoroutineScope = CoroutineScope(
        scope.coroutineContext + job + confined + CoroutineExceptionHandler { _, error ->
            log(CnctLogLevel.ERROR, "Chat client failure", error)
        },
    )

    private val mutableState = MutableStateFlow(CnctChatState())

    /**
     * The current snapshot, and every change to it. Never null, never half-applied; read
     * `state.value` for now, collect it to render. Like any `StateFlow`, a slow collector sees the
     * latest state rather than every intermediate one — which is what a screen wants.
     */
    public val state: StateFlow<CnctChatState> = mutableState.asStateFlow()

    // A subscriber that falls this far behind is not reading events any more; dropping its oldest is
    // better than blocking the socket for every other subscriber.
    private val mutableEvents = MutableSharedFlow<CnctChatEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Things that happened, for the parts of a UI that act rather than render. Hot: collect it before
     * calling [start] to hear everything.
     */
    public val events: SharedFlow<CnctChatEvent> = mutableEvents.asSharedFlow()

    /** The visitor's session token. A thirty-day secret: never logged, never in [toString]. */
    @Volatile private var token: String? = null

    /** Frame id → the send waiting for its `ack`. Concurrent because [close] may drain it from anywhere. */
    private val awaiting = ConcurrentHashMap<String, CompletableDeferred<CnctChatMessage>>()

    // Everything from here down is touched only on [confined].
    private var tokenLoaded = false
    private var connection: Connection? = null
    private val awaitingTimers = HashMap<String, Job>()
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var typingClearJob: Job? = null
    private var lastTypingSentAt: TimeMark? = null
    private var reconnectAttempt = 0
    private var socketSendSupported = false
    private var closedByUs = false

    /** The open socket, mirrored where a cancelled scope's last word can still reach it. */
    @Volatile private var liveSocket: CnctSocket? = null

    @Volatile private var disposed = false

    init {
        job.invokeOnCompletion { shutDown() }
    }

    /** True when a session token is held — the visitor has been here before and [resume] will work. */
    public val hasSession: Boolean get() = token != null

    // ── The front door ───────────────────────────────────────────────────────────────────────────

    /**
     * What this inbox says before anybody has typed: the greeting, whether it is switched on, and
     * whether it wants a phone number first.
     *
     * Readable without a session, and worth calling before you draw anything — an inbox with
     * [CnctChatInbox.isActive] false will refuse a session, and saying so up front is better than
     * letting somebody type into a composer that cannot deliver.
     */
    public suspend fun boot(): CnctChatInbox = withContext(confined) {
        val json = transport.get("/api/chat/public/${CnctConfig.pathSegment(publicKey)}")
        val inbox = decodeModel(CnctChatInbox.serializer(), asObject(json))
        patch { it.copy(inbox = inbox, error = null) }
        inbox
    }

    /**
     * Begin, or come back as the same person.
     *
     * Sending a stored token reuses that visitor rather than minting a stranger, so somebody who
     * chatted last week is still attached to the contact record the business has for them.
     *
     * Pass [phone] whenever you know it. A visitor is anonymous until they volunteer a number: if your
     * app has already signed this person in, passing the number you hold is the difference between a
     * thread that joins their history and one that starts a second record for them.
     */
    public suspend fun start(displayName: String? = null, phone: String? = null): Unit = withContext(confined) {
        loadToken()
        val body = buildJsonObject {
            displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("displayName", it) }
            phone?.trim()?.takeIf { it.isNotEmpty() }?.let { put("phone", it) }
        }
        val json = transport.post(
            "/api/chat/public/${CnctConfig.pathSegment(publicKey)}/session",
            body = body,
            headers = tokenHeader(),
        )
        adoptSession(asObject(json))
        connectNow()
    }

    /**
     * Pick up where a restart left off. Answers false when there is no session to pick up — which is
     * not an error, just a first-time visitor.
     *
     * A refused token is cleared here rather than thrown: the visitor's thirty days ran out or the
     * business closed the conversation, and neither is something an app can act on beyond going back
     * to its pre-chat screen.
     */
    public suspend fun resume(): Boolean = withContext(confined) { resumeHere() }

    // ── Saying something ─────────────────────────────────────────────────────────────────────────

    /**
     * Say something. Answers once the server has it.
     *
     * The optimistic bubble goes into [state] immediately and is replaced in place when the real row
     * arrives — matched on `clientKey`, which is also the frame id and also the server's idempotency
     * key, so the three can never disagree about which message this is.
     *
     * On failure the bubble stays, marked [CnctChatMessage.failed], and the exception is thrown. Do
     * not clear it: the words somebody typed vanishing is worse than a retry button. Cancelling the
     * call leaves it marked the same way — whether it arrived is then the server's to say, and a
     * retry cannot double-post.
     */
    public suspend fun send(body: String, clientKey: String? = null): CnctChatMessage = withContext(confined) {
        loadToken()
        if (token == null) throw CnctException("Start a conversation first.", code = CnctErrorCode.NO_SESSION)
        val text = body.trim()
        if (text.isEmpty()) throw CnctException("Nothing to send.", code = CnctErrorCode.EMPTY)
        val key = clientKey ?: uid()

        patch { current ->
            val existing = current.messages.indexOfFirst { it.clientKey == key }
            if (existing >= 0) {
                val messages = current.messages.toMutableList()
                messages[existing] = messages[existing].copy(pending = true, failed = false)
                current.copy(messages = messages)
            } else {
                current.copy(messages = current.messages + CnctChatMessage.optimistic(clientKey = key, body = text))
            }
        }

        try {
            if (socketSendSupported && connection != null) return@withContext sendOverSocket(key, text)
            // The fallback, and a real path rather than a formality: it runs against a server that
            // predates socket sends, and whenever the socket happens to be between connections. Same
            // endpoint, same idempotency key.
            val json = transport.post(
                "/api/chat/public/messages",
                body = buildJsonObject {
                    put("body", text)
                    put("clientKey", key)
                },
                headers = tokenHeader(),
            )
            val message = decodeModel(CnctChatMessage.serializer(), asObject(json))
            absorb(message)
            message
        } catch (error: CnctException) {
            markFailed(key)
            fail(
                if (error.status == 409) {
                    CnctException(error.message, code = CnctErrorCode.CONVERSATION_CLOSED, status = error.status)
                } else {
                    error
                },
            )
            throw error
        } catch (cancelled: CancellationException) {
            awaiting.remove(key)
            awaitingTimers.remove(key)?.cancel()
            markFailed(key)
            throw cancelled
        }
    }

    /** Re-send a message that failed. The same `clientKey`, so the server cannot write it twice. */
    public suspend fun retry(clientKey: String): CnctChatMessage {
        val message = state.value.messages.firstOrNull { it.clientKey == clientKey }
            ?: throw CnctException("No message with that key.", code = CnctErrorCode.NOT_FOUND)
        return send(message.body, clientKey = clientKey)
    }

    /**
     * Tell the other side somebody is typing. Throttled to one frame every three seconds here, and
     * again on the server — call it on every keystroke without thinking about it.
     */
    public fun typing() {
        inside.launch {
            val last = lastTypingSentAt
            if (last != null && last.elapsedNow() < TYPING_THROTTLE) return@launch
            if (connection == null) return@launch
            lastTypingSentAt = TimeSource.Monotonic.markNow()
            say(buildJsonObject { put("type", "typing") })
        }
    }

    /** The visitor ends it. Their own conversation, so there is nothing to identify. */
    public suspend fun end(): Unit = withContext(confined) {
        loadToken()
        if (token == null) return@withContext
        transport.post("/api/chat/public/close", headers = tokenHeader())
        closedByUs = true
        patch { it.copy(status = CnctChatStatus.ENDED) }
        teardownSocket()
    }

    // ── Files ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The bytes of a file an operator sent. Scoped to this visitor's own conversation by the server;
     * an id from anywhere else is a 404 rather than a refusal, so a stranger learns nothing.
     */
    public suspend fun downloadAttachment(attachmentId: String): ByteArray = withContext(confined) {
        loadToken()
        if (token == null) throw CnctException("Start a conversation first.", code = CnctErrorCode.NO_SESSION)
        transport.bytes(attachmentPath(attachmentId), headers = tokenHeader())
    }

    /**
     * The URL an attachment lives at, and the headers needed to fetch it.
     *
     * Together, these are what a custom image loader wants — Coil's or Glide's default fetcher cannot
     * carry the session header on its own, so this returns both rather than a URL that would 401 in
     * an image view somebody spent an afternoon debugging. The headers hold the session token: hand
     * them to the loader, never to a log.
     */
    public fun attachmentRequest(attachmentId: String): CnctAttachmentRequest =
        CnctAttachmentRequest(config.resolve(attachmentPath(attachmentId)), config.headers + tokenHeader())

    // ── The socket ───────────────────────────────────────────────────────────────────────────────

    /**
     * Open the socket. Called for you by [start] and [resume]; public for an app that disconnects
     * while backgrounded and wants to come back.
     */
    public fun connect() {
        inside.launch { connectNow() }
    }

    /** Close the socket and stop reconnecting. The session survives — [resume] picks it up again. */
    public fun disconnect() {
        inside.launch { disconnectNow() }
    }

    /**
     * Release everything. Call it from `onCleared()`; the client is unusable afterwards. Cancelling
     * the scope the client was given does the same.
     */
    public fun close() {
        disposed = true
        job.cancel()
    }

    override fun toString(): String = "CnctChatClient($redactedKey, ${state.value.status.name.lowercase()})"

    // ── Internals ────────────────────────────────────────────────────────────────────────────────

    private suspend fun resumeHere(): Boolean {
        loadToken()
        if (token == null) return false
        return try {
            val data = asObject(transport.get("/api/chat/public/session", headers = tokenHeader()))
            adoptSession(data)
            if (state.value.conversation?.status == CnctConversationStatus.CLOSED) {
                patch { it.copy(status = CnctChatStatus.ENDED) }
            } else {
                connectNow()
            }
            true
        } catch (error: CnctException) {
            if (!error.isUnauthenticated) throw error
            forgetSession()
            emit(CnctChatEvent.Unauthenticated)
            false
        }
    }

    private fun connectNow() {
        if (disposed || !job.isActive || token == null || connection != null) return
        closedByUs = false
        patch { it.copy(status = if (it.status == CnctChatStatus.LIVE) CnctChatStatus.LIVE else CnctChatStatus.CONNECTING) }

        val next = Connection()
        val socket = try {
            socketFactory.open("${config.socketBaseUrl}/ws/chat", socketHeaders(), next)
        } catch (error: Exception) {
            log(CnctLogLevel.WARNING, "Could not open the chat socket", error)
            scheduleReconnect()
            return
        }
        next.socket = socket
        connection = next
        liveSocket = socket
        // The scope may have been cancelled while the factory ran; its completion handler has then
        // already looked for a socket to close and found none.
        if (!job.isActive) {
            shutDown()
            return
        }

        // The handshake. Five seconds to authenticate or the server closes the connection, so this
        // goes out the moment the socket exists rather than after any suspension.
        say(
            buildJsonObject {
                put("type", "auth")
                put("token", token)
            },
        )
        startKeepAlive()
    }

    private fun disconnectNow() {
        closedByUs = true
        teardownSocket()
        if (state.value.status != CnctChatStatus.ENDED) patch { it.copy(status = CnctChatStatus.OFFLINE) }
    }

    /** A frame from the current connection. Never suspends, so frames are handled whole and in order. */
    private fun handleFrame(raw: String) {
        val frame = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return

        try {
            when ((frame["type"] as? JsonPrimitive)?.content) {
                "authenticated" -> {
                    reconnectAttempt = 0
                    val accepts = (frame["accepts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                    socketSendSupported = "send" in accepts
                    patch { it.copy(status = CnctChatStatus.LIVE, error = null) }
                    // **The reload that makes this client safe to render directly.** Pub/sub replays
                    // nothing, so anything said while the socket was down is missing and there is no
                    // way to know how much. Refetching is the only answer that cannot be subtly wrong.
                    inside.launch {
                        try {
                            resumeHere()
                        } catch (error: CnctException) {
                            log(CnctLogLevel.WARNING, "Could not reload the transcript after reconnecting", error)
                        }
                        // Said once the transcript is the server's, so a UI that scrolls to the end on
                        // this scrolls to the real end. Not said if the reload found the token refused.
                        if (connection != null) emit(CnctChatEvent.Connected)
                    }
                }
                "pong" -> Unit
                "ack" -> {
                    val id = frame.string("id")
                    val message = (frame["message"] as? JsonObject)?.let { decodeModel(CnctChatMessage.serializer(), it) }
                    if (message != null) absorb(message)
                    if (id != null) {
                        val waiting = awaiting.remove(id)
                        awaitingTimers.remove(id)?.cancel()
                        if (message != null) {
                            waiting?.complete(message)
                        } else {
                            waiting?.completeExceptionally(
                                CnctException("The server acknowledged without a message.", code = CnctErrorCode.BAD_RESPONSE),
                            )
                        }
                    }
                }
                "error" -> {
                    val id = frame.string("id")
                    val error = CnctException(
                        frame.string("error") ?: "That did not work.",
                        code = frame.string("code") ?: CnctErrorCode.REQUEST_FAILED,
                    )
                    val waiting = id?.let { awaiting.remove(it) }
                    if (id != null) {
                        awaitingTimers.remove(id)?.cancel()
                        // Leave the optimistic bubble in place but mark it, so a UI can offer a retry
                        // rather than making the words the person typed disappear.
                        markFailed(id)
                    }
                    // A send that is waiting reports its own failure when this reaches it; reporting
                    // here as well would tell a UI about one failure twice.
                    if (waiting != null) waiting.completeExceptionally(error) else fail(error)
                }
                "chat.message" -> {
                    val payload = (frame["payload"] as? JsonObject)?.get("message") as? JsonObject
                    if (payload != null) {
                        val message = decodeModel(CnctChatMessage.serializer(), payload)
                        absorb(message)
                        emit(CnctChatEvent.MessageReceived(message))
                    }
                }
                "chat.typing" -> {
                    patch { it.copy(theyAreTyping = true) }
                    typingClearJob?.cancel()
                    typingClearJob = inside.launch {
                        delay(TYPING_CLEARS_AFTER)
                        patch { it.copy(theyAreTyping = false) }
                    }
                    emit(CnctChatEvent.TypingReceived)
                }
                "chat.conversation.updated" -> {
                    val payload = (frame["payload"] as? JsonObject)?.get("conversation") as? JsonObject
                    if (payload != null) {
                        val conversation = decodeModel(CnctChatConversation.serializer(), payload)
                        patch { it.copy(conversation = conversation) }
                        emit(CnctChatEvent.ConversationUpdated(conversation))
                    }
                }
                "chat.closed" -> {
                    closedByUs = true
                    patch { it.copy(status = CnctChatStatus.ENDED) }
                    emit(CnctChatEvent.Closed)
                    teardownSocket()
                }
                // An unknown frame is a newer server talking, not an error. Ignored on purpose.
                else -> Unit
            }
        } catch (error: CnctException) {
            // A frame this SDK cannot read is skipped rather than allowed to end the connection.
            log(CnctLogLevel.WARNING, "Skipped a chat frame this SDK could not read", error)
        }
    }

    private suspend fun handleSocketClosed(code: Int?) {
        stopKeepAlive()
        connection = null
        liveSocket = null
        socketSendSupported = false
        failAllAwaiting(CnctException("The connection dropped before that was sent.", code = CnctErrorCode.OFFLINE))

        // 1008 is what the server closes with when the token did not resolve. Reconnecting would loop
        // against a credential that will never work, so this is the one close that ends things rather
        // than backing off.
        if (code == POLICY_VIOLATION) {
            forgetSession()
            emit(CnctChatEvent.Unauthenticated)
            return
        }
        if (state.value.status != CnctChatStatus.ENDED) patch { it.copy(status = CnctChatStatus.OFFLINE) }
        emit(CnctChatEvent.Disconnected)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (closedByUs || disposed || reconnectJob != null) return
        // Exponential with a ceiling and jitter — a thousand phones recovering from the same blip
        // should not all arrive in the same millisecond.
        val base = min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS shl min(reconnectAttempt, 16))
        val wait = (base * (0.7 + random.nextDouble() * 0.6)).roundToLong()
        reconnectAttempt += 1
        reconnectJob = inside.launch {
            delay(wait)
            reconnectJob = null
            connectNow()
        }
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        // Quiet connections get closed by proxies long before they get closed by anybody's intent.
        keepAliveJob = inside.launch {
            while (true) {
                delay(KEEP_ALIVE)
                say(buildJsonObject { put("type", "ping") })
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun teardownSocket() {
        reconnectJob?.cancel()
        reconnectJob = null
        typingClearJob?.cancel()
        typingClearJob = null
        stopKeepAlive()
        failAllAwaiting(CnctException("Disconnected.", code = CnctErrorCode.OFFLINE))
        val closing = connection
        connection = null
        liveSocket = null
        socketSendSupported = false
        // With nobody listening, nobody can be typing — and nothing is left to clear the flag later.
        if (state.value.theyAreTyping) patch { it.copy(theyAreTyping = false) }
        closing?.socket?.let { closeQuietly(it) }
    }

    /**
     * The last word, from whatever thread cancelled the scope. Only touches what is safe to touch from
     * there: the mirrored socket, the concurrent map of waiting sends, and the state flow.
     */
    private fun shutDown() {
        disposed = true
        liveSocket?.let { closeQuietly(it) }
        liveSocket = null
        failAllAwaiting(CnctException("Disconnected.", code = CnctErrorCode.OFFLINE))
        mutableState.update {
            if (it.status == CnctChatStatus.ENDED) it else it.copy(status = CnctChatStatus.OFFLINE, theyAreTyping = false)
        }
    }

    private fun closeQuietly(socket: CnctSocket) {
        try {
            socket.close(NORMAL_CLOSURE, "Client closed")
        } catch (error: Exception) {
            log(CnctLogLevel.WARNING, "Could not close the chat socket", error)
        }
    }

    private suspend fun sendOverSocket(key: String, text: String): CnctChatMessage {
        // A second send of a key already in flight waits for the same answer rather than orphaning
        // the first: the server will only ever write one row for it.
        awaiting[key]?.let { return it.await() }
        val waiting = CompletableDeferred<CnctChatMessage>()
        awaiting[key] = waiting
        awaitingTimers[key] = inside.launch {
            delay(config.sendTimeout)
            awaiting.remove(key)
            awaitingTimers.remove(key)
            markFailed(key)
            // Safe to say "retry": the clientKey means a second attempt cannot double-post.
            waiting.completeExceptionally(
                CnctException("That message has not been acknowledged yet.", code = CnctErrorCode.TIMEOUT),
            )
        }
        say(
            buildJsonObject {
                put("type", "send")
                put("id", key)
                put("body", text)
                put("clientKey", key)
            },
        )
        return waiting.await()
    }

    private fun say(frame: JsonObject) {
        val socket = connection?.socket ?: return
        try {
            socket.send(frame.toString())
        } catch (error: Exception) {
            log(CnctLogLevel.WARNING, "Could not write to the chat socket", error)
        }
    }

    private fun failAllAwaiting(error: CnctException) {
        for (key in awaiting.keys.toList()) awaiting.remove(key)?.completeExceptionally(error)
        // Only on the lane: the timers are the lane's. From [shutDown] they are children of a
        // cancelled job and already going.
        if (!disposed) {
            awaitingTimers.values.forEach { it.cancel() }
            awaitingTimers.clear()
        }
    }

    /** Merge a server message in, replacing the optimistic one it answers. */
    private fun absorb(message: CnctChatMessage) {
        patch { current ->
            val messages = current.messages.toMutableList()
            val at = if (message.clientKey != null) {
                messages.indexOfFirst { it.clientKey == message.clientKey }
            } else {
                messages.indexOfFirst { it.id == message.id }
            }
            if (at >= 0) {
                messages[at] = message
            } else if (messages.none { it.id == message.id }) {
                messages += message
            }
            current.copy(messages = messages.sortedBy { it.createdAt }, error = null)
        }
    }

    private fun markFailed(clientKey: String) {
        patch { current ->
            current.copy(
                messages = current.messages.map {
                    if (it.clientKey == clientKey) it.copy(pending = false, failed = true) else it
                },
            )
        }
    }

    private suspend fun adoptSession(data: JsonObject) {
        val fresh = data.string("token")
        if (!fresh.isNullOrEmpty()) {
            token = fresh
            tokenLoaded = true
            // A store that fails to keep the token is not a reason to fail the call it happened in:
            // the visitor can chat now, and will be a stranger next launch.
            try {
                store.write(storageKey, fresh)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                log(CnctLogLevel.WARNING, "Could not keep the chat session token", error)
            }
        }
        val visitor = (data["visitor"] as? JsonObject)?.let { decodeModel(CnctChatVisitor.serializer(), it) }
        val conversation = (data["conversation"] as? JsonObject)?.let { decodeModel(CnctChatConversation.serializer(), it) }
        val transcript = (data["messages"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.map { decodeModel(CnctChatMessage.serializer(), it) }
        patch { current ->
            current.copy(
                visitor = visitor ?: current.visitor,
                conversation = conversation ?: current.conversation,
                messages = transcript?.let { keepUnsent(it, current.messages) } ?: current.messages,
                error = null,
            )
        }
    }

    /**
     * The server's transcript, plus the visitor's own bubbles it does not have yet — in flight, or
     * failed and waiting for a retry. A reload must not make the words somebody typed disappear; once
     * the server has one, its own row replaces the bubble, matched on `clientKey`.
     */
    private fun keepUnsent(transcript: List<CnctChatMessage>, current: List<CnctChatMessage>): List<CnctChatMessage> {
        val stored = transcript.mapNotNullTo(HashSet()) { it.clientKey }
        val unsent = current.filter { (it.pending || it.failed) && it.clientKey != null && it.clientKey !in stored }
        return if (unsent.isEmpty()) transcript else (transcript + unsent).sortedBy { it.createdAt }
    }

    private suspend fun forgetSession() {
        token = null
        tokenLoaded = true
        try {
            store.clear(storageKey)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            log(CnctLogLevel.WARNING, "Could not clear the chat session token", error)
        }
        teardownSocket()
        patch { CnctChatState(inbox = it.inbox) }
    }

    private suspend fun loadToken() {
        if (tokenLoaded) return
        val stored = try {
            store.read(storageKey)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            log(CnctLogLevel.WARNING, "Could not read the chat session token", error)
            null
        }
        // Another call may have adopted a fresh session while this one was reading; that one wins.
        if (!tokenLoaded) {
            token = stored
            tokenLoaded = true
        }
    }

    /**
     * Never `Authorization`. The platform's rate limiter keys on `ip + authorization`, so a visitor
     * who sent that header would be minting themselves a fresh budget by inventing one.
     */
    private fun tokenHeader(): Map<String, String> = token?.let { mapOf("x-chat-token" to it) } ?: emptyMap()

    private fun socketHeaders(): Map<String, String> = mapOf("user-agent" to config.userAgent) + config.headers

    private fun attachmentPath(attachmentId: String) =
        "/api/chat/public/attachments/${CnctConfig.pathSegment(attachmentId)}"

    private fun patch(transform: (CnctChatState) -> CnctChatState) {
        mutableState.update(transform)
    }

    private fun fail(error: CnctException) {
        patch { it.copy(error = error) }
        emit(CnctChatEvent.Errored(error))
    }

    private fun emit(event: CnctChatEvent) {
        mutableEvents.tryEmit(event)
    }

    private fun log(level: CnctLogLevel, message: String, error: Throwable?) {
        config.logger?.log(level, message, error)
    }

    private fun uid(): String {
        val stamp = (System.currentTimeMillis() * 1000).toString(36)
        val noise = CharArray(10) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
        return "k$stamp$noise"
    }

    /** One connection attempt. Anything it reports after it has been replaced is ignored. */
    private inner class Connection : CnctSocketListener {
        var socket: CnctSocket? = null

        override fun onMessage(text: String) {
            inside.launch { if (connection === this@Connection) handleFrame(text) }
        }

        override fun onClosed(code: Int?, error: Throwable?) {
            if (error != null) log(CnctLogLevel.WARNING, "Chat socket error", error)
            inside.launch { if (connection === this@Connection) handleSocketClosed(code) }
        }
    }

    private companion object {
        const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
        const val NORMAL_CLOSURE = 1000
        const val POLICY_VIOLATION = 1008
        const val FIRST_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 30_000L
        val KEEP_ALIVE = 25.seconds
        val TYPING_THROTTLE = 3.seconds
        val TYPING_CLEARS_AFTER = 4.seconds

        /** One lane on the dispatcher the caller chose — or on the default one, where none can be had. */
        fun confine(interceptor: ContinuationInterceptor?): CoroutineDispatcher {
            val dispatcher = interceptor as? CoroutineDispatcher ?: Dispatchers.Default
            return try {
                dispatcher.limitedParallelism(1)
            } catch (_: UnsupportedOperationException) {
                // Dispatchers.Unconfined has no lane to give.
                Dispatchers.Default.limitedParallelism(1)
            }
        }
    }
}

/**
 * Where an attachment lives and what to send to fetch it. [headers] hold the visitor's session
 * token, so this prints the header names and never their values.
 */
public class CnctAttachmentRequest internal constructor(
    public val url: HttpUrl,
    public val headers: Map<String, String>,
) {
    override fun toString(): String = "CnctAttachmentRequest($url, headers: ${headers.keys})"
}
