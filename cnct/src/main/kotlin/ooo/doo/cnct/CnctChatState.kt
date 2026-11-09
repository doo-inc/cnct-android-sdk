package ooo.doo.cnct

import java.time.Instant

/** Where the chat client is. */
public enum class CnctChatStatus {
    /** No session. `boot()` may have run; nobody has started a conversation. */
    IDLE,

    /** A session exists and the socket is being opened. */
    CONNECTING,

    /** Connected. Messages arrive as they are written. */
    LIVE,

    /**
     * The socket dropped and is being retried. **Not an error state** — the transcript is still true,
     * sends still work over HTTP, and the client is already backing off towards a reconnect.
     */
    OFFLINE,

    /** The conversation is closed. It cannot be reopened; `start()` a new one. */
    ENDED,
}

/**
 * Everything a UI renders, in one immutable snapshot.
 *
 * Replaced rather than mutated on every change, so a `StateFlow` collector, a Compose `State` or
 * anything comparing values sees the change, and anything reading fields sees a consistent picture
 * rather than a half-applied one. Use [copy] to build one in a preview or a test.
 */
public data class CnctChatState(
    public val status: CnctChatStatus = CnctChatStatus.IDLE,
    /** What `boot()` returned. Null until it has run. */
    public val inbox: CnctChatInbox? = null,
    public val conversation: CnctChatConversation? = null,
    public val visitor: CnctChatVisitor? = null,
    /** Oldest first. */
    public val messages: List<CnctChatMessage> = emptyList(),
    /**
     * True while the other side is typing. Clears itself after four seconds — the platform sends a
     * start, never a stop, because a stop that goes missing leaves an indicator on the screen for
     * ever.
     */
    public val theyAreTyping: Boolean = false,
    /** The last failure, or null. Cleared by the next success. */
    public val error: CnctException? = null,
) {
    /** True when there is a conversation to type into. */
    public val canSend: Boolean
        get() = status != CnctChatStatus.IDLE &&
            status != CnctChatStatus.ENDED &&
            conversation?.status != CnctConversationStatus.CLOSED

    /** Messages that failed to send and are waiting for a retry. */
    public val failed: List<CnctChatMessage> get() = messages.filter { it.failed }

    /** False once the conversation has ended, by either side. Nothing more can be said in it. */
    public val isLive: Boolean
        get() = status != CnctChatStatus.ENDED && conversation?.status != CnctConversationStatus.CLOSED

    /**
     * What an assistant's flow is waiting for, if anything: its choices to draw under the thread, or
     * the kind of answer the composer should ask for.
     *
     * Only ever the last thing said, and only while the conversation is live: once the visitor
     * answers, or anybody says anything, the choices go, as quick replies do everywhere.
     */
    public val awaiting: CnctChatAwaiting?
        get() {
            if (!isLive) return null
            val last = messages.lastOrNull() ?: return null
            if (last.speaker != CnctSpeaker.AI || last.pending || !last.drawsAsFlow) return null
            val flow = last.flow ?: return null
            val choices = flow.choices.filter { it.id.isNotEmpty() && it.title.isNotEmpty() }
            if (choices.isNotEmpty()) {
                // A description needs the width, so choices that carry one are a list whatever the
                // flow asked for.
                val list = flow.layout == CnctFlowLayout.LIST || choices.any { !it.description.isNullOrBlank() }
                return CnctChatAwaiting(
                    messageId = last.id,
                    choices = choices,
                    layout = if (list) CnctFlowLayout.LIST else CnctFlowLayout.BUTTONS,
                    label = flow.listButton,
                    expects = null,
                )
            }
            val expects = flow.expects ?: return null
            return CnctChatAwaiting(last.id, emptyList(), CnctFlowLayout.BUTTONS, null, expects)
        }

    /**
     * The replies the assistant offered with its last answer, to show as chips — up to three. Only
     * while nobody has spoken since, and never beside a flow's choices: one row of answers to tap at
     * a time. Read back over the assistant's trailing messages, because a picture it sent after its
     * words arrives as a message of its own and carries none.
     */
    public val suggestions: List<String>
        get() {
            if (!isLive || awaiting?.choices?.isNotEmpty() == true) return emptyList()
            for (message in messages.asReversed()) {
                if (message.speaker != CnctSpeaker.AI) break
                if (message.suggestions.isNotEmpty()) return message.suggestions.filter { it.isNotBlank() }.take(3)
            }
            return emptyList()
        }

    /**
     * True when the visitor's own words are the last thing said and somebody on the team has read
     * them — "Seen" under them. After a reply, the reply says it better, so this is false.
     */
    public val seen: Boolean
        get() {
            val last = messages.lastOrNull() ?: return false
            if (last.speaker != CnctSpeaker.CALLER || last.pending || last.failed || last.id.startsWith("pending_")) return false
            val at = conversation?.seenAt ?: return false
            return !at.isBefore(last.createdAt)
        }

    override fun toString(): String =
        "CnctChatState(${status.name.lowercase()}, ${messages.size} messages${if (theyAreTyping) ", typing" else ""})"
}

/** What a flow is waiting for: see [CnctChatState.awaiting]. */
public data class CnctChatAwaiting(
    /** The flow message asking. */
    public val messageId: String,
    /** The choices to offer, already checked to have an id and a title. Empty when [expects] is set. */
    public val choices: List<CnctFlowChoice>,
    /** Pills for short choices, rows for ones with descriptions. */
    public val layout: CnctFlowLayout,
    /** The flow's label for its list of choices, where it gave one. */
    public val label: String?,
    /** The kind of answer to type, when the flow asks for one rather than offering choices. */
    public val expects: CnctFlowExpects?,
)

/**
 * Something that happened, as opposed to something that is true.
 *
 * [CnctChatState] is enough to render the whole interface; these are for the things a UI *does*
 * rather than shows — playing a sound on a new message, scrolling to the bottom, sending the visitor
 * back to a login screen when their session is refused.
 */
public sealed interface CnctChatEvent {
    /** The socket is up and the transcript has been refetched. */
    public data object Connected : CnctChatEvent

    /** The socket dropped. A reconnect is already scheduled; nothing is required of you. */
    public data object Disconnected : CnctChatEvent

    /**
     * The session token was refused and has been cleared. The visitor is a stranger again — show
     * whatever you show before a conversation starts.
     */
    public data object Unauthenticated : CnctChatEvent

    /** A message arrived. Already in [CnctChatState.messages] by the time this is emitted. */
    public data class MessageReceived(public val message: CnctChatMessage) : CnctChatEvent

    /**
     * A message changed — edited, deleted, or a file on it finished storing. Already replaced, in
     * place, in [CnctChatState.messages].
     */
    public data class MessageUpdated(public val message: CnctChatMessage) : CnctChatEvent

    /**
     * Somebody on the team read the conversation up to [at]. Already in the conversation's
     * `seenAt`; [CnctChatState.seen] says whether that covers the visitor's last words.
     */
    public data class Read(public val at: Instant) : CnctChatEvent

    /** The other side is typing. */
    public data object TypingReceived : CnctChatEvent

    /** The conversation changed — assigned to somebody, snoozed, reopened. */
    public data class ConversationUpdated(public val conversation: CnctChatConversation) : CnctChatEvent

    /** The conversation was closed, by the visitor or by the business. */
    public data object Closed : CnctChatEvent

    /** Something failed. Also in [CnctChatState.error]. */
    public data class Errored(public val error: CnctException) : CnctChatEvent
}
