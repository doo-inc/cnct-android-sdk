package ooo.doo.cnct.ui

/**
 * What the visitor did in the chat, for an app's analytics. Nothing here carries what they wrote:
 * an analytics pipeline is the wrong place for a customer's words.
 */
public sealed interface CnctChatUIEvent {
    /** The chat was opened and the inbox answered. */
    public data object Opened : CnctChatUIEvent

    /** A conversation began — after the name form, or with the first message. */
    public data object Started : CnctChatUIEvent

    /** The visitor sent a message. */
    public data object Sent : CnctChatUIEvent

    /** The visitor sent [count] files with a message. */
    public data class AttachmentSent(public val count: Int) : CnctChatUIEvent

    /** The visitor ended the conversation. */
    public data object Ended : CnctChatUIEvent

    /** The visitor rated the conversation, 1 (terrible) to 5 (great). */
    public data class Rated(public val score: Int) : CnctChatUIEvent
}
