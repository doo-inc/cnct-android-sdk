package ooo.doo.cnct

import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatModelsTest {
    private fun message(json: String): CnctChatMessage = decodeModel(CnctChatMessage.serializer(), CnctJson.parseToJsonElement(json))

    // ── Cards ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a ticket card parses into its four published fields`() {
        val message = message(
            """
            {"id":"m1","speaker":"SYSTEM","type":"TICKET_UPDATE",
             "body":"We have opened ticket #1042 for this: Refund not received",
             "createdAt":"2026-09-08T10:00:00.000Z",
             "data":{"ticketNumber":1042,"title":"Refund not received","status":"OPEN","raisedAt":"2026-09-08T09:58:00.000Z"}}
            """,
        )

        assertEquals(CnctMessageType.TICKET_UPDATE, message.type)
        assertEquals(1042, message.ticket?.ticketNumber)
        assertEquals("Refund not received", message.ticket?.title)
        assertEquals(Instant.parse("2026-09-08T09:58:00.000Z"), message.ticket?.raisedAt)
        assertNull(message.booking)
    }

    @Test
    fun `a booking card carries where and when, and nothing internal`() {
        val message = message(
            """
            {"id":"m2","speaker":"AI","type":"BOOKING_UPDATE","body":"Booked for Tuesday at 19:30.",
             "createdAt":"2026-09-08T10:00:00.000Z",
             "data":{"title":"Dinner","startsAt":"2026-09-15T16:30:00.000Z","endsAt":"2026-09-15T18:00:00.000Z",
                     "status":"CONFIRMED","locationName":"Adliya","partySize":4}}
            """,
        )

        assertEquals(4, message.booking?.partySize)
        assertEquals("Adliya", message.booking?.locationName)
        // An instant, so there is no zone in it to get wrong: a customer formats it on their own clock.
        assertEquals(Instant.parse("2026-09-15T16:30:00.000Z"), message.booking?.startsAt)
        assertEquals(Instant.parse("2026-09-15T18:00:00.000Z"), message.booking?.endsAt)
    }

    @Test
    fun `a card type this SDK has never met degrades to text with a readable body`() {
        val message = message(
            """
            {"id":"m3","speaker":"AI","type":"DELIVERY_UPDATE","body":"Your order is out for delivery.",
             "createdAt":"2026-09-08T10:00:00.000Z","data":{"courier":"Aramex"}}
            """,
        )

        assertEquals(CnctMessageType.OTHER, message.type)
        assertEquals("DELIVERY_UPDATE", message.rawType)
        assertEquals("Your order is out for delivery.", message.body)
        // The fields are still there for an app that wants to opt in early.
        assertEquals("Aramex", message.data?.get("courier")?.jsonPrimitive?.content)
    }

    @Test
    fun `a malformed card does not take the message down with it`() {
        val message = message(
            """
            {"id":"m4","speaker":"SYSTEM","type":"TICKET_UPDATE","body":"A ticket was raised.",
             "createdAt":"2026-09-08T10:00:00.000Z","data":{"title":"no number here"}}
            """,
        )

        assertNull(message.ticket)
        assertEquals("A ticket was raised.", message.body)
        assertNull(message(""" {"type":"BOOKING_UPDATE","data":{"startsAt":"not a time"}} """).booking)
    }

    // ── Speakers and statuses ────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown speaker is a speaker, not an exception`() {
        assertEquals(CnctSpeaker.UNKNOWN, CnctSpeaker.parse("MARTIAN"))
        assertFalse(CnctSpeaker.parse("CALLER").isFromBusiness)
        assertTrue(CnctSpeaker.parse("OPERATOR").isFromBusiness)
        // And the same on the wire.
        assertEquals(CnctSpeaker.UNKNOWN, message("""{"speaker":"MARTIAN","body":"hi"}""").speaker)
    }

    @Test
    fun `conversation status parses the three the platform has`() {
        assertEquals(CnctConversationStatus.OPEN, CnctConversationStatus.parse("OPEN"))
        assertEquals(CnctConversationStatus.SNOOZED, CnctConversationStatus.parse("SNOOZED"))
        assertEquals(CnctConversationStatus.CLOSED, CnctConversationStatus.parse("CLOSED"))
        assertEquals(CnctConversationStatus.UNKNOWN, CnctConversationStatus.parse("ARCHIVED"))
        val wire = decodeModel(CnctChatConversation.serializer(), CnctJson.parseToJsonElement("""{"status":"ARCHIVED"}"""))
        assertEquals(CnctConversationStatus.UNKNOWN, wire.status)
    }

    @Test
    fun `attachments arrive typed`() {
        val message = message(
            """
            {"id":"m5","speaker":"OPERATOR","type":"TEXT","body":"Here is the quote.","createdAt":"2026-09-08T10:00:00.000Z",
             "attachments":[{"id":"att_1","kind":"DOCUMENT","mimeType":"application/pdf","filename":"quote.pdf","bytes":20480},
                            {"id":"att_2","kind":"HOLOGRAM"}]}
            """,
        )

        assertEquals(CnctAttachmentKind.DOCUMENT, message.attachments.first().kind)
        assertEquals("quote.pdf", message.attachments.first().filename)
        assertEquals(20480L, message.attachments.first().bytes)
        assertEquals(CnctAttachmentKind.OTHER, message.attachments.last().kind)
        assertEquals("application/octet-stream", message.attachments.last().mimeType)
    }

    @Test
    fun `state reports what a composer should do`() {
        val idle = CnctChatState()
        assertFalse(idle.canSend)

        val live = idle.copy(
            status = CnctChatStatus.LIVE,
            conversation = CnctChatConversation(
                status = CnctConversationStatus.OPEN,
                startedAt = Instant.parse("2026-09-08T00:00:00Z"),
                withPerson = false,
            ),
        )
        assertTrue(live.canSend)

        val ended = live.copy(status = CnctChatStatus.ENDED)
        assertFalse(ended.canSend)
    }

    // ── Beyond the Flutter suite ─────────────────────────────────────────────────────────────────

    @Test
    fun `a message with nothing in it still reads, and is text`() {
        val message = message("{}")
        assertEquals(CnctMessageType.TEXT, message.type)
        assertEquals(CnctSpeaker.UNKNOWN, message.speaker)
        assertEquals("", message.body)
        assertTrue(message.attachments.isEmpty())
    }

    @Test
    fun `pending and failed are this client's knowledge, never the server's`() {
        val optimistic = CnctChatMessage.optimistic(clientKey = "k1", body = "Hi")
        assertTrue(optimistic.pending)
        assertEquals("pending_k1", optimistic.id)
        val wire = CnctJson.encodeToString(CnctChatMessage.serializer(), optimistic.copy(failed = true))
        assertFalse(wire.contains("pending\""))
        assertFalse(wire.contains("failed"))
        assertFalse(message("""{"pending":true,"failed":true}""").failed)
    }
}
