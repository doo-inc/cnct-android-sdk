package ooo.doo.cnct.ui

/**
 * What the visitor did in the chat, for an app's analytics. Nothing here carries what they wrote:
 * an analytics pipeline is the wrong place for a customer's words.
 */
public sealed interface CnctChatUiEvent {
    /** The chat was opened and the inbox answered. */
    public data object Opened : CnctChatUiEvent

    /** A conversation began — after the name form, or with the first message. */
    public data object Started : CnctChatUiEvent

    /** The visitor sent a message. */
    public data object Sent : CnctChatUiEvent

    /** The visitor sent [count] files with a message. */
    public data class AttachmentSent(public val count: Int) : CnctChatUiEvent

    /** The visitor ended the conversation. */
    public data object Ended : CnctChatUiEvent

    /** The visitor rated the conversation, 1 (terrible) to 5 (great). */
    public data class Rated(public val score: Int) : CnctChatUiEvent
}
