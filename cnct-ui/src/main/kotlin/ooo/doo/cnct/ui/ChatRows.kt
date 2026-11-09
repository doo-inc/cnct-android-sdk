package ooo.doo.cnct.ui

import ooo.doo.cnct.CnctChatMessage
import ooo.doo.cnct.CnctChatSender
import ooo.doo.cnct.CnctChatState
import ooo.doo.cnct.CnctMessageType
import ooo.doo.cnct.CnctSenderKind
import ooo.doo.cnct.CnctSpeaker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the thread draws, in order, oldest first. Pure: built from [CnctChatState] alone, so what the
 * screen shows is what the headless client knows, and the rules are tested without a screen.
 */
internal sealed interface Row {
    /** Stable across redraws, so a list keeps its place and only what is new moves. */
    val key: String

    /** "Today", above the first message of each day. */
    data class Day(override val key: String, val date: LocalDate, val first: Instant) : Row

    /** A line from the platform itself — the greeting, an out-of-hours notice — centred. */
    data class System(override val key: String, val message: CnctChatMessage) : Row

    /** A ticket or booking card, centred. */
    data class Card(override val key: String, val message: CnctChatMessage) : Row

    /**
     * One run of the same speaker: the bubbles, then a byline under them — the face, the name and
     * the time on the team's side, the time (and "Seen") on the visitor's. It is what lets a thread
     * with an assistant, a hand-over and a person in it read as three voices rather than one stream.
     */
    data class Group(
        override val key: String,
        val mine: Boolean,
        val speaker: CnctSpeaker,
        val sender: CnctChatSender?,
        val messages: List<CnctChatMessage>,
        val seen: Boolean,
    ) : Row

    /** The replies the assistant offered, as chips. */
    data class Suggestions(override val key: String, val items: List<String>) : Row

    /** The other side is typing. */
    data object Typing : Row {
        override val key: String = "typing"
    }
}

internal object ChatRows {
    /**
     * The rows for [state]. [greeting] is drawn as the first line of a conversation that has not
     * started yet — the same greeting the server writes as its first line the moment it does.
     */
    fun of(state: CnctChatState, zone: ZoneId, greeting: String? = null): List<Row> {
        val rows = ArrayList<Row>()
        val messages = if (state.conversation == null && state.messages.isEmpty() && !greeting.isNullOrBlank()) {
            listOf(CnctChatMessage(id = "greeting", speaker = CnctSpeaker.SYSTEM, body = greeting, createdAt = Instant.now()))
        } else {
            state.messages
        }

        var day: LocalDate? = null
        var group: MutableList<CnctChatMessage>? = null
        var groupSigner: String? = null
        fun flush() {
            val run = group ?: return
            val first = run.first()
            rows += Row.Group(
                key = "g:" + identity(first),
                mine = first.speaker == CnctSpeaker.CALLER,
                speaker = first.speaker,
                sender = first.sender,
                messages = run.toList(),
                seen = false,
            )
            group = null
            groupSigner = null
        }

        for (message in messages) {
            val date = message.createdAt.atZone(zone).toLocalDate()
            if (date != day) {
                flush()
                rows += Row.Day("d:$date", date, message.createdAt)
                day = date
            }
            when {
                message.type == CnctMessageType.TICKET_UPDATE || message.type == CnctMessageType.BOOKING_UPDATE -> {
                    flush()
                    rows += Row.Card("c:" + identity(message), message)
                }
                message.speaker == CnctSpeaker.SYSTEM && message.sender == null -> {
                    flush()
                    rows += Row.System("s:" + identity(message), message)
                }
                else -> {
                    val signer = signer(message)
                    if (group == null || signer != groupSigner) {
                        flush()
                        group = ArrayList()
                        groupSigner = signer
                    }
                    group!!.add(message)
                }
            }
        }
        flush()

        // "Seen" under the visitor's last words once somebody on the team has read them — only when
        // theirs are the last words: after a reply, the reply says it better.
        if (state.seen) {
            val last = rows.lastOrNull()
            if (last is Row.Group && last.mine) rows[rows.lastIndex] = last.copy(seen = true)
        }

        val offered = state.suggestions
        if (offered.isNotEmpty()) rows += Row.Suggestions("sg:" + (messages.lastOrNull()?.id ?: ""), offered)
        if (state.theyAreTyping && state.isLive) rows += Row.Typing
        return rows
    }

    /** Who a message is from, as one comparable string — what decides where a group starts. */
    fun signer(message: CnctChatMessage): String {
        if (message.speaker == CnctSpeaker.CALLER) return "you"
        val sender = message.sender ?: return if (message.speaker == CnctSpeaker.AI) "ai:" else "business"
        return "${sender.kind}:${sender.name.orEmpty()}:${sender.avatar ?: sender.mascot ?: ""}"
    }

    /** The visitor's own messages keep their key from optimistic bubble to stored row. */
    fun identity(message: CnctChatMessage): String = message.clientKey ?: message.id

    /** The name a group is signed with: the sender's, else the business's own. */
    fun signerName(sender: CnctChatSender?, business: String): String = sender?.name?.takeIf { it.isNotBlank() } ?: business

    /** True when the face to draw is the assistant's. */
    fun isAssistant(speaker: CnctSpeaker, sender: CnctChatSender?): Boolean =
        sender?.kind == CnctSenderKind.AI || (sender == null && speaker == CnctSpeaker.AI)
}
