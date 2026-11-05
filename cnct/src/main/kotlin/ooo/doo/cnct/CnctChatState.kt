package ooo.doo.cnct

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

    override fun toString(): String =
        "CnctChatState(${status.name.lowercase()}, ${messages.size} messages${if (theyAreTyping) ", typing" else ""})"
}

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

    /** The other side is typing. */
    public data object TypingReceived : CnctChatEvent

    /** The conversation changed — assigned to somebody, snoozed, reopened. */
    public data class ConversationUpdated(public val conversation: CnctChatConversation) : CnctChatEvent

    /** The conversation was closed, by the visitor or by the business. */
    public data object Closed : CnctChatEvent

    /** Something failed. Also in [CnctChatState.error]. */
    public data class Errored(public val error: CnctException) : CnctChatEvent
}
