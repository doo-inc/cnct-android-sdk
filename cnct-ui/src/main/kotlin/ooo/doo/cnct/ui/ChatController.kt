package ooo.doo.cnct.ui

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ooo.doo.cnct.CnctAttachmentRules
import ooo.doo.cnct.CnctChatAttachment
import ooo.doo.cnct.CnctChatClient
import ooo.doo.cnct.CnctChatEvent
import ooo.doo.cnct.CnctChatIdentity
import ooo.doo.cnct.CnctChatStart
import ooo.doo.cnct.CnctChatStatus
import ooo.doo.cnct.CnctErrorCode
import ooo.doo.cnct.CnctException
import ooo.doo.cnct.CnctFlowChoice
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything the screen does, as opposed to everything it shows.
 *
 * Built only on the headless client's public API — [CnctChatClient.state] is the truth about the
 * conversation, and this holds only what the client has no business knowing: which screen is up,
 * the files in the tray, the sentence under the thread, the stage of the rating card.
 */
internal class ChatController(
    val client: CnctChatClient,
    host: CoroutineScope,
    private val store: AttachmentStore?,
    private val identity: CnctChatIdentity?,
    private val events: () -> ((CnctChatUIEvent) -> Unit)?,
) {
    /**
     * A child of the host's scope that one failure cannot take down: a refused session start is a
     * sentence under the form, not the end of the screen.
     */
    private val scope: CoroutineScope = CoroutineScope(
        host.coroutineContext + SupervisorJob(host.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, error -> Log.w(TAG, "Chat screen failure", error) },
    )

    enum class Unavailable { INVALID_LINK, SWITCHED_OFF, FAILED }

    sealed interface Phase {
        data object Loading : Phase
        data class Closed(val why: Unavailable) : Phase

        /** The short form before the first message: a name, and a phone number where the inbox wants one. */
        data object Details : Phase
        data object Chat : Phase
    }

    /** A sentence for the visitor, under the thread: a file refused, a send that failed. */
    sealed interface Notice {
        data class Word(val res: Int, val args: List<String> = emptyList()) : Notice
        data class Server(val text: String) : Notice
    }

    enum class UploadStatus { UPLOADING, DONE, FAILED }

    /** One file in the tray above the composer, on its way up or waiting to be sent. */
    data class Upload(
        val key: Long,
        val name: String,
        val mimeType: String,
        val size: Long,
        val isImage: Boolean,
        val progress: Float,
        val status: UploadStatus,
        val attachment: CnctChatAttachment? = null,
        val thumbnail: androidx.compose.ui.graphics.ImageBitmap? = null,
    )

    enum class RatingStage { FACES, COMMENT, DONE }

    private val mutablePhase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = mutablePhase.asStateFlow()

    private val mutableUploads = MutableStateFlow<List<Upload>>(emptyList())
    val uploads: StateFlow<List<Upload>> = mutableUploads.asStateFlow()

    private val mutableNotice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = mutableNotice.asStateFlow()

    /** True while a session is being started from the form. */
    val starting = MutableStateFlow(false)

    /** The name the visitor just gave in the thread, for a "thanks" in place of the card. */
    val justNamed = MutableStateFlow<String?>(null)

    /** "End this chat?" asked in place of the composer. */
    val confirmingEnd = MutableStateFlow(false)

    /** Conversation id → where its rating card is. */
    val ratingStage = MutableStateFlow<Map<String, RatingStage>>(emptyMap())

    private val uploadJobs = HashMap<Long, Job>()
    private val keys = AtomicLong()
    private var startingSession: Deferred<Unit>? = null

    private fun emit(event: CnctChatUIEvent) {
        events()?.invoke(event)
    }

    // ── Opening ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Boot, then sign in when there is an identity — before anything is read, because the answer
     * decides whose conversations those are — then pick a stored session back up.
     */
    fun open(): Job = scope.launch {
        mutablePhase.value = Phase.Loading
        scope.launch {
            // The stored session was refused and cleared: back to the start, as a stranger.
            client.events.collect { event ->
                if (event is CnctChatEvent.Unauthenticated && mutablePhase.value == Phase.Chat) {
                    mutableUploads.value = emptyList()
                    mutablePhase.value = if (asksForDetails()) Phase.Details else Phase.Chat
                }
            }
        }
        val inbox = try {
            client.boot()
        } catch (error: CnctException) {
            mutablePhase.value = Phase.Closed(if (error.status == 404) Unavailable.INVALID_LINK else Unavailable.FAILED)
            return@launch
        }
        if (!inbox.isActive) {
            mutablePhase.value = Phase.Closed(Unavailable.SWITCHED_OFF)
            return@launch
        }
        emit(CnctChatUIEvent.Opened)
        if (identity != null) {
            try {
                client.identify(identity)
            } catch (error: CnctException) {
                // For the developer: the customer still gets a working, anonymous chat.
                Log.w(TAG, "Could not sign this visitor in: ${error.message}")
            }
        }
        val resumed = try {
            client.state.value.conversation != null || client.resume()
        } catch (_: CnctException) {
            mutablePhase.value = Phase.Closed(Unavailable.FAILED)
            return@launch
        }
        mutablePhase.value = if (resumed || !asksForDetails()) Phase.Chat else Phase.Details
    }

    /** Whether a new conversation begins with the form: the inbox asks for it and they have not said who they are. */
    private fun asksForDetails(): Boolean {
        val state = client.state.value
        val named = state.visitor?.let { !it.anonymous && it.displayName.isNotBlank() } == true
        return state.inbox?.widget?.chat?.start != CnctChatStart.MESSAGE && !named
    }

    /** The form's "Start chatting". */
    fun startWithDetails(displayName: String, phone: String?) {
        if (starting.value) return
        val name = displayName.trim()
        if (name.isEmpty()) return
        starting.value = true
        mutableNotice.value = null
        scope.launch {
            try {
                client.start(displayName = name, phone = phone?.trim()?.takeIf { it.isNotEmpty() })
                emit(CnctChatUIEvent.Started)
                mutablePhase.value = Phase.Chat
            } catch (error: CnctException) {
                mutableNotice.value = if (error.status == 429) Notice.Word(R.string.cnct_too_many_chats) else Notice.Server(error.message)
            } finally {
                starting.value = false
            }
        }
    }

    /** "Start a new conversation", once one has ended. */
    fun startNew() {
        confirmingEnd.value = false
        justNamed.value = null
        mutableNotice.value = null
        if (asksForDetails()) {
            mutablePhase.value = Phase.Details
            return
        }
        scope.launch {
            try {
                ensureSession(fresh = true)
            } catch (error: CnctException) {
                mutableNotice.value = if (error.status == 429) Notice.Word(R.string.cnct_too_many_chats) else Notice.Server(error.message)
            }
        }
    }

    /**
     * The conversation exists on the server — started now if this is the first thing they do. A
     * message and a file in the same moment share one start rather than racing to make two.
     */
    private suspend fun ensureSession(fresh: Boolean = false) {
        val state = client.state.value
        if (!fresh && client.hasSession && state.conversation != null && state.isLive) return
        val pending = startingSession ?: scope.async {
            try {
                client.start()
                emit(CnctChatUIEvent.Started)
            } finally {
                startingSession = null
            }
        }.also { startingSession = it }
        pending.await()
    }

    // ── Saying something ─────────────────────────────────────────────────────────────────────────

    /** True while a file is still on its way up: the message waits for it. */
    val uploading: Boolean get() = mutableUploads.value.any { it.status == UploadStatus.UPLOADING }

    /**
     * Send the box's words with the files that finished uploading. [accepted] is called once the
     * message is on screen, to clear the box — not before, so a conversation that could not be
     * started leaves the words where they were typed.
     */
    fun send(text: String, accepted: () -> Unit) {
        if (uploading) return
        val words = text.trim()
        val files = mutableUploads.value.filter { it.status == UploadStatus.DONE && it.attachment != null }
        if (words.isEmpty() && files.isEmpty()) return
        mutableNotice.value = null
        scope.launch {
            try {
                ensureSession()
            } catch (error: CnctException) {
                mutableNotice.value = if (error.status == 429) Notice.Word(R.string.cnct_too_many_chats) else Notice.Server(error.message)
                return@launch
            }
            val ids = files.mapNotNull { it.attachment?.id }
            mutableUploads.update { tray -> tray.filterNot { upload -> files.any { it.key == upload.key } } }
            accepted()
            try {
                client.send(words, attachmentIds = ids)
                emit(CnctChatUIEvent.Sent)
                if (ids.isNotEmpty()) emit(CnctChatUIEvent.AttachmentSent(ids.size))
            } catch (error: CnctException) {
                // The bubble stays, marked, with its own retry; a word under the thread says why.
                mutableNotice.value = when {
                    error.code == CnctErrorCode.RATE_LIMITED -> Notice.Word(R.string.cnct_slow_down)
                    error.code == CnctErrorCode.CONFLICT || error.code == CnctErrorCode.CONVERSATION_CLOSED -> null
                    else -> Notice.Server(error.message)
                }
            }
        }
    }

    /** A flow's choice, tapped. */
    fun choose(choice: CnctFlowChoice) {
        mutableNotice.value = null
        scope.launch {
            try {
                client.choose(choice)
                emit(CnctChatUIEvent.Sent)
            } catch (error: CnctException) {
                mutableNotice.value = Notice.Server(error.message)
            }
        }
    }

    /** An assistant's suggestion, tapped: sent as the visitor's own words. */
    fun suggest(text: String) = send(text) {}

    fun retry(clientKey: String) {
        mutableNotice.value = null
        scope.launch {
            try {
                client.retry(clientKey)
            } catch (_: CnctException) {
                // Still marked on screen, with the retry still there.
            }
        }
    }

    fun typing() {
        if (client.hasSession && client.state.value.isLive) client.typing()
    }

    // ── Files ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Files from the photo picker, the camera or the document picker: checked, put in the tray, and
     * sent up at once, each on its own. The eleventh, one over 50 MB and one of a type the platform
     * does not keep are refused here, with a sentence, before a byte is sent.
     */
    fun addFiles(files: List<PickedFile>, thumbnail: suspend (PickedFile) -> androidx.compose.ui.graphics.ImageBitmap? = { null }) {
        if (files.isEmpty()) return
        mutableNotice.value = null
        for (file in files) {
            if (mutableUploads.value.size >= CnctAttachmentRules.MAX_FILES) {
                mutableNotice.value = Notice.Word(R.string.cnct_too_many)
                break
            }
            val convertible = CnctAttachmentRules.needsConversion(file.mimeType, file.name)
            if (!convertible && !CnctAttachmentRules.isAccepted(file.mimeType, file.name)) {
                mutableNotice.value = Notice.Word(R.string.cnct_upload_failed, listOf(file.name))
                continue
            }
            // A photo over the limit is brought down before it goes; anything else is refused now.
            if (file.size > CnctAttachmentRules.MAX_BYTES && !file.mimeType.startsWith("image/")) {
                mutableNotice.value = Notice.Word(R.string.cnct_too_big, listOf(file.name))
                continue
            }
            val key = keys.incrementAndGet()
            val upload = Upload(
                key = key,
                name = file.name,
                mimeType = file.mimeType,
                size = file.size,
                isImage = file.mimeType.startsWith("image/"),
                progress = 0f,
                status = UploadStatus.UPLOADING,
            )
            mutableUploads.update { it + upload }
            uploadJobs[key] = scope.launch { upload(key, file, thumbnail) }
        }
    }

    private suspend fun upload(key: Long, file: PickedFile, thumbnail: suspend (PickedFile) -> androidx.compose.ui.graphics.ImageBitmap?) {
        fun change(transform: (Upload) -> Upload) = mutableUploads.update { tray -> tray.map { if (it.key == key) transform(it) else it } }
        if (file.mimeType.startsWith("image/")) {
            scope.launch {
                val thumb = try {
                    thumbnail(file)
                } catch (_: Exception) {
                    null
                }
                if (thumb != null) change { it.copy(thumbnail = thumb) }
            }
        }
        try {
            ensureSession()
            val prepared = withContext(Dispatchers.Default) { ImagePrep.prepare(file) }
            if (prepared.size > CnctAttachmentRules.MAX_BYTES) {
                change { it.copy(status = UploadStatus.FAILED) }
                mutableNotice.value = Notice.Word(R.string.cnct_too_big, listOf(file.name))
                return
            }
            change { it.copy(name = prepared.name, mimeType = prepared.mimeType, size = prepared.size) }
            val progress: (Float) -> Unit = { fraction -> change { it.copy(progress = fraction) } }
            val bytes = prepared.bytes
            val attachment = if (bytes != null) {
                client.upload(bytes, prepared.name, prepared.mimeType, progress)
            } else {
                client.upload(prepared.size, prepared.name, prepared.mimeType, progress, file.open)
            }
            // The server's copy, when it comes back on the message, is drawn from the file on this device.
            try {
                if (bytes != null) store?.remember(attachment.id, bytes) else store?.remember(attachment.id, file)
            } catch (_: Exception) {
                // Only a cache: it will be downloaded instead.
            }
            change { it.copy(status = UploadStatus.DONE, progress = 1f, attachment = attachment) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CnctException) {
            change { it.copy(status = UploadStatus.FAILED) }
            mutableNotice.value = when (error.code) {
                CnctErrorCode.CONVERSATION_CLOSED -> null
                CnctErrorCode.TOO_LARGE -> Notice.Word(R.string.cnct_too_big, listOf(file.name))
                else -> if (error.status != null) Notice.Server(error.message) else Notice.Word(R.string.cnct_upload_failed, listOf(file.name))
            }
        } catch (_: Exception) {
            change { it.copy(status = UploadStatus.FAILED) }
            mutableNotice.value = Notice.Word(R.string.cnct_upload_failed, listOf(file.name))
        } finally {
            uploadJobs.remove(key)
        }
    }

    fun removeUpload(key: Long) {
        uploadJobs.remove(key)?.cancel()
        mutableUploads.update { tray -> tray.filterNot { it.key == key } }
    }

    // ── Ending, rating, naming, reading ──────────────────────────────────────────────────────────

    fun end() {
        confirmingEnd.value = false
        scope.launch {
            try {
                client.end()
                emit(CnctChatUIEvent.Ended)
            } catch (_: CnctException) {
                // Ending is the visitor's way out; it should never be the thing that fails loudly.
            }
            uploadJobs.values.forEach { it.cancel() }
            uploadJobs.clear()
            mutableUploads.value = emptyList()
        }
    }

    /**
     * The faces. Tapping one saves it at once — the face is the answer, and somebody who taps and
     * closes the chat has still said something — and then offers a box for more, sent as a second
     * answer to the same question.
     */
    fun rate(conversationId: String, score: Int, comment: String? = null) {
        val before = ratingStage.value[conversationId]
        ratingStage.update { it + (conversationId to if (comment == null) RatingStage.COMMENT else RatingStage.DONE) }
        scope.launch {
            try {
                client.rate(score, comment = comment, conversationId = conversationId)
                if (comment == null) emit(CnctChatUIEvent.Rated(score))
            } catch (error: CnctException) {
                ratingStage.update { stages -> if (before == null) stages - conversationId else stages + (conversationId to before) }
                mutableNotice.value = Notice.Server(error.message)
            }
        }
    }

    fun skipComment(conversationId: String) {
        ratingStage.update { it + (conversationId to RatingStage.DONE) }
    }

    /** The name card in the thread, for a visitor who typed first. */
    fun saveDetails(displayName: String, phone: String?) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        scope.launch {
            try {
                client.updateProfile(name, phone?.trim()?.takeIf { it.isNotEmpty() })
                justNamed.value = name
            } catch (error: CnctException) {
                mutableNotice.value = Notice.Server(error.message)
            }
        }
    }

    /** A team reply is on screen with the app in front: tell them, once per reply. */
    fun markRead() {
        scope.launch {
            try {
                client.read()
            } catch (_: CnctException) {
                // Forgotten by the client, so the next look tries again.
            }
        }
    }

    /** The app came to the front: reconnect, which reloads. */
    fun foreground() {
        if (client.hasSession && client.state.value.status != CnctChatStatus.ENDED) client.connect()
    }

    /** The app went to the back: a socket held open there is battery for nothing. */
    fun background() {
        client.disconnect()
    }

    fun dismissNotice() {
        mutableNotice.value = null
    }

    private companion object {
        const val TAG = "CnctChat"
    }
}
