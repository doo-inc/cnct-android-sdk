package ooo.doo.cnct

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

/** Who said it. */
@Serializable
public enum class CnctSpeaker {
    /** The visitor — the person using your app. */
    @SerialName("CALLER")
    CALLER,

    /** The assistant. */
    @SerialName("AI")
    AI,

    /**
     * A human on the business's side. Never named: the platform publishes that somebody is with you,
     * never who, and this SDK cannot show you what it is not sent.
     */
    @SerialName("OPERATOR")
    OPERATOR,

    /** The platform itself: a greeting, an out-of-hours notice, a "nobody is covering this" line. */
    @SerialName("SYSTEM")
    SYSTEM,

    /** A speaker added after this version of the SDK was written. Render it as you would [SYSTEM]. */
    UNKNOWN,
    ;

    /** True for anything that is not the visitor — the side of the bubble to draw it on. */
    public val isFromBusiness: Boolean get() = this != CALLER

    public companion object {
        /** The server's name for a speaker. Anything unrecognised is [UNKNOWN], never an exception. */
        public fun parse(raw: String?): CnctSpeaker = entries.firstOrNull { it != UNKNOWN && it.name == raw } ?: UNKNOWN
    }
}

/** Where a conversation is. */
@Serializable
public enum class CnctConversationStatus {
    @SerialName("OPEN")
    OPEN,

    @SerialName("SNOOZED")
    SNOOZED,

    @SerialName("CLOSED")
    CLOSED,

    UNKNOWN,
    ;

    public companion object {
        public fun parse(raw: String?): CnctConversationStatus =
            entries.firstOrNull { it != UNKNOWN && it.name == raw } ?: UNKNOWN
    }
}

/**
 * What to render a message as.
 *
 * **Anything unrecognised is [OTHER], and its `body` is text — that is the whole compatibility
 * story.** A card type added to the platform next year arrives at an app built today with a `body`
 * that reads correctly as a sentence — "We have opened ticket #1042 for this: Refund not received" —
 * so the app shows something true rather than an empty bubble. Handling a new card is an opt-in
 * improvement, never a required migration.
 */
public enum class CnctMessageType {
    TEXT,
    TICKET_UPDATE,
    BOOKING_UPDATE,

    /**
     * A card this SDK does not know. [CnctChatMessage.rawType] has its name and
     * [CnctChatMessage.data] its fields; [CnctChatMessage.body] is always safe to show.
     */
    OTHER,
    ;

    public companion object {
        public fun parse(raw: String?): CnctMessageType = when (raw) {
            "TICKET_UPDATE" -> TICKET_UPDATE
            "BOOKING_UPDATE" -> BOOKING_UPDATE
            "TEXT" -> TEXT
            else -> OTHER
        }
    }
}

/** What kind of file was sent. */
@Serializable
public enum class CnctAttachmentKind {
    @SerialName("IMAGE")
    IMAGE,

    @SerialName("VIDEO")
    VIDEO,

    @SerialName("AUDIO")
    AUDIO,

    @SerialName("DOCUMENT")
    DOCUMENT,

    @SerialName("STICKER")
    STICKER,

    OTHER,
    ;

    public companion object {
        public fun parse(raw: String?): CnctAttachmentKind = entries.firstOrNull { it != OTHER && it.name == raw } ?: OTHER
    }
}

/** What the inbox says before anybody has typed. Readable without a session — it is the front door. */
@Serializable
public data class CnctChatInbox(
    /** The business's name, for your header. */
    public val business: String = "",
    /** This inbox's name. */
    public val inbox: String = "",
    /**
     * **False means do not open the composer.** The inbox is switched off; a session start will be
     * refused. Say so rather than letting somebody type into something that will not accept it.
     */
    public val isActive: Boolean = false,
    /** What to show before the first message. */
    public val greeting: String = "",
    /**
     * True when this inbox wants a phone number before the conversation starts. Ask for it in your
     * pre-chat step and pass it to `start(phone = …)` — it is what joins this visitor to the contact
     * record the business already has.
     */
    public val requirePhone: Boolean = false,
)

/** The state of the visitor's own conversation. */
@Serializable
public data class CnctChatConversation(
    public val status: CnctConversationStatus = CnctConversationStatus.UNKNOWN,
    @Serializable(with = InstantOrNowSerializer::class)
    public val startedAt: Instant = Instant.now(),
    /**
     * True when somebody — an operator or the assistant — is on this thread. **Never who.** The
     * platform does not publish a colleague's name to a customer, so "Sara is typing" is not
     * something this SDK can offer and not something it will pretend to.
     */
    public val withPerson: Boolean = false,
)

/**
 * A ticket raised out of this conversation, where the inbox publishes them.
 *
 * Off by default per inbox: a business that files a ticket for every awkward question has not
 * thereby agreed to tell the customer a case was opened.
 */
public data class CnctTicketCard(
    /** What a customer quotes back to you. */
    public val ticketNumber: Int,
    public val title: String,
    /**
     * `OPEN`, `RESOLVED`, and the rest of the lifecycle. A string rather than an enum on purpose:
     * the lifecycle is per account and a closed enum here would turn a client adding a status into a
     * crash in somebody's app.
     */
    public val status: String,
    public val raisedAt: Instant?,
) {
    public companion object {
        /** The card in a message's `data`, or null when there is none worth drawing. */
        public fun tryFrom(data: JsonObject?): CnctTicketCard? {
            if (data == null) return null
            val number = (data["ticketNumber"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                ?: return null
            return CnctTicketCard(
                ticketNumber = number.toInt(),
                title = data.string("title") ?: "",
                status = data.string("status") ?: "",
                raisedAt = parseInstant(data.string("raisedAt")),
            )
        }
    }
}

/**
 * A booking taken, moved or cancelled in this conversation, where the inbox publishes them.
 *
 * All three moments arrive, not just the first — a card that announces an appointment and goes quiet
 * when it moves leaves a stale time on a screen the customer has every reason to trust.
 */
public data class CnctBookingCard(
    /** The service, or the booking's own title where there is no service. */
    public val title: String,
    /**
     * **Render this in the customer's zone, not the business's** — format it with
     * `ZoneId.systemDefault()`. It is an instant, so there is no zone in it to get wrong.
     */
    public val startsAt: Instant,
    public val endsAt: Instant?,
    public val status: String,
    /** Where to go. Null where the business has one location. */
    public val locationName: String?,
    /** Null unless the booking took one. */
    public val partySize: Int?,
) {
    public companion object {
        /** The card in a message's `data`, or null when there is none worth drawing. */
        public fun tryFrom(data: JsonObject?): CnctBookingCard? {
            if (data == null) return null
            val startsAt = parseInstant(data.string("startsAt")) ?: return null
            return CnctBookingCard(
                title = data.string("title") ?: "",
                startsAt = startsAt,
                endsAt = parseInstant(data.string("endsAt")),
                status = data.string("status") ?: "",
                locationName = data.string("locationName"),
                partySize = (data["partySize"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.toInt(),
            )
        }
    }
}

/**
 * A file sent with a message. Only files the platform actually holds are published, so anything in
 * this list can be fetched.
 */
@Serializable
public data class CnctChatAttachment(
    public val id: String = "",
    public val kind: CnctAttachmentKind = CnctAttachmentKind.OTHER,
    public val mimeType: String = "application/octet-stream",
    public val filename: String? = null,
    /** Size in bytes, where the platform recorded one. */
    public val bytes: Long? = null,
)

/** One message in the transcript. */
@Serializable
public data class CnctChatMessage(
    public val id: String = "",
    public val speaker: CnctSpeaker = CnctSpeaker.UNKNOWN,
    /** The type exactly as the server named it, for the cards this SDK version has not met. */
    @SerialName("type")
    public val rawType: String = "TEXT",
    /** **Always reads correctly on its own**, card or not. This is the field an `else` branch shows. */
    public val body: String = "",
    @Serializable(with = InstantOrNowSerializer::class)
    public val createdAt: Instant = Instant.now(),
    /** A card's fields, allowlisted by the server. Null on ordinary text. */
    public val data: JsonObject? = null,
    /**
     * The visitor's own idempotency key, handed back so an optimistic bubble can be retired. Only
     * ever present on the visitor's own messages — an operator's key is none of a visitor's business.
     */
    public val clientKey: String? = null,
    public val attachments: List<CnctChatAttachment> = emptyList(),
    /** In flight. Draw it, greyed. Never on the wire: it is this client's knowledge, not the server's. */
    @Transient
    public val pending: Boolean = false,
    /**
     * The send failed. Offer a retry button wired to `CnctChatClient.retry(message.clientKey!!)` —
     * retrying cannot double-post.
     */
    @Transient
    public val failed: Boolean = false,
) {
    /**
     * What to render this as. Unrecognised card types arrive as [CnctMessageType.OTHER]; [body] is
     * always safe to show.
     */
    public val type: CnctMessageType get() = CnctMessageType.parse(rawType)

    /** The ticket card, when this is one. Null otherwise. */
    public val ticket: CnctTicketCard?
        get() = if (type == CnctMessageType.TICKET_UPDATE) CnctTicketCard.tryFrom(data) else null

    /** The booking card, when this is one. Null otherwise. */
    public val booking: CnctBookingCard?
        get() = if (type == CnctMessageType.BOOKING_UPDATE) CnctBookingCard.tryFrom(data) else null

    override fun toString(): String = "CnctChatMessage(${speaker.name.lowercase()}, $rawType, \"$body\")"

    public companion object {
        /**
         * A bubble drawn the moment somebody pressed send, before the server has it. Replaced in place
         * by the stored row, matched on [clientKey].
         */
        public fun optimistic(clientKey: String, body: String): CnctChatMessage = CnctChatMessage(
            id = "pending_$clientKey",
            speaker = CnctSpeaker.CALLER,
            rawType = "TEXT",
            body = body,
            createdAt = Instant.now(),
            clientKey = clientKey,
            pending = true,
        )
    }
}

/**
 * The visitor, as the platform knows them. A display name and nothing else: this SDK is not told
 * which contact record they resolved to, and a customer-facing app has no use for one.
 */
@Serializable
public data class CnctChatVisitor(public val displayName: String = "")
