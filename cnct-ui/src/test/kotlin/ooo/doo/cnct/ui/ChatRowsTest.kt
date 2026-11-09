package ooo.doo.cnct.ui

import ooo.doo.cnct.CnctChatConversation
import ooo.doo.cnct.CnctChatMessage
import ooo.doo.cnct.CnctChatSender
import ooo.doo.cnct.CnctChatState
import ooo.doo.cnct.CnctChatStatus
import ooo.doo.cnct.CnctConversationStatus
import ooo.doo.cnct.CnctSenderKind
import ooo.doo.cnct.CnctSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ChatRowsTest {
    private val zone = ZoneId.of("Asia/Bahrain")
    private val sara = CnctChatSender(CnctSenderKind.PERSON, "Sara")
    private val omar = CnctChatSender(CnctSenderKind.PERSON, "Omar")
    private val ai = CnctChatSender(CnctSenderKind.AI, "Assistant")

    private fun at(day: Int, minute: Int) = Instant.parse("2026-11-%02dT10:%02d:00Z".format(day, minute))

    private fun message(id: String, speaker: CnctSpeaker, sender: CnctChatSender? = null, minute: Int = 0, day: Int = 9) =
        CnctChatMessage(id = id, speaker = speaker, sender = sender, body = id, createdAt = at(day, minute))

    @Test
    fun `runs of one speaker are one group, days get a stamp, system lines stand alone`() {
        val state = CnctChatState(
            status = CnctChatStatus.LIVE,
            conversation = CnctChatConversation(id = "c", status = CnctConversationStatus.OPEN),
            messages = listOf(
                message("hello", CnctSpeaker.SYSTEM, minute = 0, day = 8),
                message("q1", CnctSpeaker.CALLER, minute = 1),
                message("q2", CnctSpeaker.CALLER, minute = 2),
                message("a1", CnctSpeaker.AI, ai, minute = 3),
                message("s1", CnctSpeaker.OPERATOR, sara, minute = 4),
                message("s2", CnctSpeaker.OPERATOR, sara, minute = 5),
                message("o1", CnctSpeaker.OPERATOR, omar, minute = 6),
            ),
            theyAreTyping = true,
        )
        val rows = ChatRows.of(state, zone)
        val shape = rows.map {
            when (it) {
                is Row.Day -> "day"
                is Row.System -> "system"
                is Row.Group -> "group:" + it.messages.joinToString(",") { m -> m.id }
                is Row.Typing -> "typing"
                else -> it.key
            }
        }
        assertEquals(listOf("day", "system", "day", "group:q1,q2", "group:a1", "group:s1,s2", "group:o1", "typing"), shape)
    }

    @Test
    fun `a new conversation opens with the greeting, and the visitor's last words can be Seen`() {
        val empty = ChatRows.of(CnctChatState(), zone, greeting = "Hello. How can we help?")
        assertTrue(empty.any { it is Row.System && it.message.body == "Hello. How can we help?" })

        val seen = CnctChatState(
            status = CnctChatStatus.LIVE,
            conversation = CnctChatConversation(id = "c", status = CnctConversationStatus.OPEN, seenAt = at(9, 30)),
            messages = listOf(message("a1", CnctSpeaker.AI, ai, minute = 1), message("q1", CnctSpeaker.CALLER, minute = 2)),
        )
        val last = ChatRows.of(seen, zone).last() as Row.Group
        assertTrue(last.mine && last.seen)
    }

    @Test
    fun `the optimistic bubble and the stored one keep the same key`() {
        val optimistic = CnctChatMessage.optimistic("k1", "Hi")
        val stored = optimistic.copy(id = "m1", pending = false)
        assertEquals(ChatRows.identity(optimistic), ChatRows.identity(stored))
    }
}
