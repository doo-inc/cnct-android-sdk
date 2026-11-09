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
    /**
     * How the business set the chat up in the CNCT console: its colour, its logo, its language, and
     * which parts of the conversation screen it wants. A ready-made interface reads its defaults from
     * here, so a business can brand the chat with no code at all; an app that pins something in code
     * wins over it.
     *
     * Merged section by section over [CnctChatWidget]'s defaults, exactly as the server merges, so an
     * older server that predates a field leaves that field at its default rather than missing.
     */
    public val widget: CnctChatWidget = CnctChatWidget(),
    /**
     * A few of the people who will answer — first names and faces, never more. For a "Sara and Omar
     * are here to help" line; empty when the business chose not to show its team.
     */
    public val team: List<CnctChatSender> = emptyList(),
    /**
     * Whether the team is in, when it is back, and how fast it really replies. Null on a server that
     * does not say, which is not the same as "closed": promise nothing rather than guess.
     */
    public val availability: CnctChatAvailability? = null,
)

/**
 * The inbox's widget settings, as the business saved them in the console. Only the sections a
 * conversation screen reads are modelled; the launcher and home screen belong to the hosted page.
 */
@Serializable
public data class CnctChatWidget(
    public val brand: CnctWidgetBrand = CnctWidgetBrand(),
    public val chat: CnctWidgetChat = CnctWidgetChat(),
)

/** How the chat looks. */
@Serializable
public data class CnctWidgetBrand(
    /**
     * The business's colour, `#rrggbb`. A value that is not one is the business's typo, not a reason
     * to draw an unstyled screen — read it through [colorOrDefault].
     */
    public val color: String = DEFAULT_COLOR,
    /** Light, dark, or following the device (`auto` on the wire). */
    public val appearance: CnctAppearance = CnctAppearance.LIGHT,
    /** The name the business wants shown, where it differs from the account's. */
    public val businessName: String? = null,
    /** The logo, as a public URL. Null means "draw the first letter of the name on the colour". */
    public val avatarUrl: String? = null,
    /** The interface's language. `auto` follows the visitor's device. */
    public val language: CnctLanguage = CnctLanguage.AUTO,
    /** Whether to say "Powered by CNCT" under the composer. */
    public val showBranding: Boolean = true,
) {
    /** [color] when it is a real `#rrggbb`, and the CNCT default otherwise. */
    public val colorOrDefault: String get() = if (HEX.matches(color)) color else DEFAULT_COLOR

    public companion object {
        /** The colour the hosted widget falls back to, and so does every SDK. */
        public const val DEFAULT_COLOR: String = "#b52d93"
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")
    }
}

/** What the conversation screen offers. */
@Serializable
public data class CnctWidgetChat(
    /** Show the attach button. The server refuses files on an inbox with this off, whatever a UI does. */
    public val allowAttachments: Boolean = true,
    /** The web widget's emoji panel. A phone has a keyboard for that; native UIs ignore it. */
    public val allowEmoji: Boolean = true,
    /** Offer "End conversation" in the header. */
    public val allowEndConversation: Boolean = false,
    /** Ask for a name before the first message, or let the visitor type first. */
    public val start: CnctChatStart = CnctChatStart.DETAILS,
    /** Ask "How did we do?" when a conversation closes. */
    public val askForRating: Boolean = true,
)

/** How a conversation begins. */
@Serializable
public enum class CnctChatStart {
    /** A short form first — a name, and a phone number where the inbox wants one. */
    @SerialName("details")
    DETAILS,

    /**
     * Straight to the composer: the first message starts the session, and the visitor is asked for a
     * name in the thread afterwards, once they have said what they came to say.
     */
    @SerialName("message")
    MESSAGE,
}

/** Light, dark, or the device's own setting. */
@Serializable
public enum class CnctAppearance {
    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,

    /** Follow the device. The console calls this `auto`. */
    @SerialName("auto")
    SYSTEM,
}

/** The languages the interface speaks, and `AUTO` for "whichever of them the device prefers". */
@Serializable
public enum class CnctLanguage {
    @SerialName("auto")
    AUTO,

    @SerialName("en")
    EN,

    @SerialName("ar")
    AR,
}

/**
 * What the visitor can expect, said honestly: whether the team is in, when it is back, and how fast
 * replies have really come over the last month. Without a measurement, a UI promises nothing.
 */
@Serializable
public data class CnctChatAvailability(
    /**
     * In or out, by the business's opening hours. **Null when the business has no hours to be in or
     * out of** — a dot saying "away" there would be a guess.
     */
    public val open: Boolean? = null,
    /** When the team is back, while it is out. Format it on the visitor's clock. */
    @Serializable(with = InstantOrNullSerializer::class)
    public val opensAt: Instant? = null,
    /** True when an assistant answers now, whatever the hour. */
    public val assistant: Boolean = false,
    /** How long a reply has typically taken, in minutes. Null without enough conversations to say. */
    public val replyMinutes: Double? = null,
)

/**
 * Who wrote a message, for the face and the first name beside it — or who is on a conversation.
 *
 * A first name and a face, never more: the platform publishes that Sara answered, not her surname,
 * her email or anything else about her. [avatar] and [mascot] are keys from the platform's own shelf
 * of drawings rather than URLs, so a UI without those drawings shows the first letter instead.
 */
@Serializable
public data class CnctChatSender(
    public val kind: CnctSenderKind = CnctSenderKind.OTHER,
    /** First name for a person, the assistant's name for the assistant. May be null for either. */
    public val name: String? = null,
    /** A person's face, as a key such as `fox-clay`. */
    public val avatar: String? = null,
    /** The assistant's mascot, as a key. */
    public val mascot: String? = null,
    /** The assistant's colour, as a key. */
    public val colour: String? = null,
)

/** What kind of sender. */
@Serializable
public enum class CnctSenderKind {
    /** Somebody on the business's team. */
    @SerialName("person")
    PERSON,

    /** The business's assistant. */
    @SerialName("ai")
    AI,

    /** A kind this SDK has not met. Sign it with the business's own name and mark. */
    OTHER,
}

/** A rating the visitor left on a closed conversation. */
@Serializable
public data class CnctChatRating(
    /** 1 (terrible) to 5 (great). */
    public val score: Int = 0,
    public val comment: String? = null,
    @Serializable(with = InstantOrNullSerializer::class)
    public val ratedAt: Instant? = null,
)

/** The state of the visitor's own conversation. */
@Serializable
public data class CnctChatConversation(
    /** The conversation's id — what [CnctChatClient.conversation], `read` and `rate` take. */
    public val id: String = "",
    public val status: CnctConversationStatus = CnctConversationStatus.UNKNOWN,
    @Serializable(with = InstantOrNowSerializer::class)
    public val startedAt: Instant = Instant.now(),
    /**
     * True when somebody — an operator or the assistant — is on this thread. [assignee] says who,
     * by first name, when it is a person who took it.
     */
    public val withPerson: Boolean = false,
    /** When it closed. Null while it is open. */
    @Serializable(with = InstantOrNullSerializer::class)
    public val closedAt: Instant? = null,
    /** The person on it, first name and face, when somebody took it. Null otherwise. */
    public val assignee: CnctChatSender? = null,
    /** The visitor's rating, once they left one. */
    public val rating: CnctChatRating? = null,
    /**
     * True while the visitor may still rate it. A closed conversation stops being ratable after a
     * while, and the server answers `409` to a rating sent after that.
     */
    public val ratable: Boolean = false,
    /** When anything was last said, for sorting a list. */
    @Serializable(with = InstantOrNullSerializer::class)
    public val lastMessageAt: Instant? = null,
    /** The last thing said, on the rows of [CnctChatClient.conversations]. Null in a transcript. */
    public val preview: CnctChatMessage? = null,
    /**
     * When somebody on the team last read the visitor's messages. A "Seen" under the visitor's last
     * words is honest when this is at or after them.
     */
    @Serializable(with = InstantOrNullSerializer::class)
    public val seenAt: Instant? = null,
) {
    /** False once it has closed. A conversation the visitor cannot write in any more. */
    public val isLive: Boolean get() = status != CnctConversationStatus.CLOSED
}

/** One conversation and everything said in it, oldest first. */
public data class CnctChatTranscript(
    public val conversation: CnctChatConversation,
    public val messages: List<CnctChatMessage>,
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
    /** Files sent with it. Only files the platform holds are listed, so every one can be fetched. */
    public val attachments: List<CnctChatAttachment> = emptyList(),
    /**
     * Who wrote it, for the face and first name beside a reply. Null for the visitor's own words and
     * for system lines, which a UI signs with the business's own name and mark.
     */
    public val sender: CnctChatSender? = null,
    /**
     * What an assistant's flow is asking: its words, its choices and the kind of answer it expects.
     * Only ever on assistant messages. Draw [CnctChatFlow.text] rather than [body] when it is set —
     * the body is the same question as numbered text, for screens that know nothing of flows.
     */
    public val flow: CnctChatFlow? = null,
    /** Up to three short replies the assistant offers, to show as chips. Tapping one sends it. */
    @Serializable(with = StringListSerializer::class)
    public val suggestions: List<String> = emptyList(),
    /** The earlier message this one replies to, as the server quoted it. */
    public val replyTo: CnctChatReplyTo? = null,
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

    /**
     * True when this is a flow's message with its own words. Draw [CnctChatFlow.text], its media and
     * footer, rather than [body]. A flow message stored before the server sent its words keeps its
     * body.
     */
    public val drawsAsFlow: Boolean get() = flow?.text != null

    /** What this message says in words: the flow's text where it has one, otherwise [body]. */
    public val words: String get() = if (drawsAsFlow) flow?.text.orEmpty() else body

    override fun toString(): String = "CnctChatMessage(${speaker.name.lowercase()}, $rawType, \"$body\")"

    public companion object {
        /**
         * A bubble drawn the moment somebody pressed send, before the server has it. Replaced in place
         * by the stored row, matched on [clientKey].
         */
        public fun optimistic(
            clientKey: String,
            body: String,
            attachments: List<CnctChatAttachment> = emptyList(),
        ): CnctChatMessage = CnctChatMessage(
            id = "pending_$clientKey",
            speaker = CnctSpeaker.CALLER,
            rawType = "TEXT",
            body = body,
            createdAt = Instant.now(),
            clientKey = clientKey,
            attachments = attachments,
            pending = true,
        )
    }
}

/**
 * The visitor, as the platform knows them. A display name and two facts about it: this SDK is not
 * told which contact record they resolved to, and a customer-facing app has no use for one.
 */
@Serializable
public data class CnctChatVisitor(
    public val displayName: String = "",
    /**
     * True when the visitor has not told the business who they are — they typed first, and the name
     * on the thread is one the platform made up. A UI asks for a name in the thread once they have
     * said something; [CnctChatClient.updateProfile] saves it.
     */
    public val anonymous: Boolean = false,
    /** True when the app signed this visitor in with [CnctChatClient.identify]. */
    public val identified: Boolean = false,
)

/**
 * What an assistant's flow is asking. A UI draws [text] (with [media] above and [footer] below), then
 * [choices] as buttons or a list under the thread, and asks for the right kind of answer when it
 * [expects] one. The flow still reads a typed answer, so nobody is stuck if they would rather type.
 */
@Serializable
public data class CnctChatFlow(
    /** The question, in the flow's own words. Null on a message stored before the server sent them. */
    public val text: String? = null,
    /** Buttons for a few short choices, a list for longer ones. */
    public val layout: CnctFlowLayout = CnctFlowLayout.BUTTONS,
    public val choices: List<CnctFlowChoice> = emptyList(),
    /** A small line under the question. */
    public val footer: String? = null,
    /** The label a list of choices is opened with. */
    public val listButton: String? = null,
    /** A picture, film or document the flow put above its words. */
    public val media: CnctFlowMedia? = null,
    /** The kind of answer the flow wants typed: pick the keyboard for it. Null for anything. */
    public val expects: CnctFlowExpects? = null,
)

/** How a flow lays out its choices. */
@Serializable
public enum class CnctFlowLayout {
    @SerialName("buttons")
    BUTTONS,

    @SerialName("list")
    LIST,
}

/**
 * One choice a flow offers. Tapping it sends [title] as the visitor's words with [id] as the
 * message's `replyId` — see [CnctChatClient.choose] — so the flow follows the button exactly rather
 * than reading the title as typing.
 */
@Serializable
public data class CnctFlowChoice(
    public val id: String = "",
    public val title: String = "",
    public val description: String? = null,
)

/**
 * A flow's picture, film or document. [url] is public — it is the flow's own asset, not a
 * visitor's file — so it is fetched without the session token. Only `http(s)` is ever drawn.
 */
@Serializable
public data class CnctFlowMedia(
    public val kind: CnctFlowMediaKind = CnctFlowMediaKind.OTHER,
    public val url: String = "",
    public val filename: String? = null,
)

/** What kind of media a flow attached. */
@Serializable
public enum class CnctFlowMediaKind {
    @SerialName("image")
    IMAGE,

    @SerialName("video")
    VIDEO,

    @SerialName("document")
    DOCUMENT,

    /** Anything else: offer it as a link to open. */
    OTHER,
}

/** The kind of answer a flow's question expects, and so the keyboard to show. */
@Serializable
public enum class CnctFlowExpects {
    @SerialName("name")
    NAME,

    @SerialName("number")
    NUMBER,

    @SerialName("email")
    EMAIL,

    @SerialName("phone")
    PHONE,

    @SerialName("date")
    DATE,
}

/**
 * The message a reply quotes, as the server snapshotted it — so nothing here is anything the quoting
 * side made up. [excerpt] is its first words; where it was only a file, [attachmentKind] says what
 * kind, and a UI says "Photo" or "File" instead.
 */
@Serializable
public data class CnctChatReplyTo(
    /** The quoted message's id, to scroll to when it is on screen. */
    public val messageId: String? = null,
    /** True when the visitor wrote the quoted message. */
    public val mine: Boolean = false,
    public val excerpt: String? = null,
    public val attachmentKind: CnctAttachmentKind? = null,
)
