@file:OptIn(ExperimentalSerializationApi::class)

package ooo.doo.cnct

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * One of the operations the account's credential may perform, as the platform advertises it.
 *
 * The catalogue is filtered per account — a client without ticketing is not shown four tools that
 * would refuse — so read it rather than assuming. A catalogue is a promise, and something that has
 * read one will try to keep it.
 */
@Serializable
public data class CnctTool(
    public val name: String = "",
    public val description: String = "",
    /** JSON Schema for the arguments. Older hosts call it `inputSchema`; both are read. */
    @JsonNames("inputSchema")
    public val parameters: JsonObject = JsonObject(emptyMap()),
)

/**
 * What [CnctAgentClient.catalogue] answers: the tools this account's key may call, and the booking
 * rules it works under.
 */
public data class CnctCatalogue(
    public val tools: List<CnctTool>,
    public val rules: CnctBookingRules,
)

/**
 * The booking rules this account works under: its clock, its week, its services and what it will
 * let a caller ask for.
 *
 * Worth fetching once at startup and showing: the hours are the difference between an app that
 * offers a time and one that offers a time the business will accept.
 */
@Serializable(with = CnctBookingRules.Serializer::class)
public data class CnctBookingRules(
    /** The business's clock. Every human-readable time this API returns is already in it. */
    public val timezone: String = "UTC",
    public val capacity: Int = 0,
    /** The step between offered start times. */
    public val slotMinutes: Int = 0,
    public val services: List<CnctService> = emptyList(),
    public val resources: List<CnctBookableResource> = emptyList(),
    /**
     * Everything, unmodelled — `hours`, `locations`, `policy`. Exposed raw rather than pinned to a
     * shape this SDK would have to break to widen: the parts above are the ones an app renders, and
     * the rest is here when you need it.
     */
    public val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** The typed half, read by the plugin; the whole object is kept alongside it as [raw]. */
    @Serializable
    private class Typed(
        val timezone: String = "UTC",
        val capacity: Int = 0,
        val slotMinutes: Int = 0,
        val services: List<CnctService> = emptyList(),
        val resources: List<CnctBookableResource> = emptyList(),
    )

    internal object Serializer : KSerializer<CnctBookingRules> {
        override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

        override fun deserialize(decoder: Decoder): CnctBookingRules {
            val whole = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: JsonObject(emptyMap())
            // The SDK's own settings rather than the caller's: `hours` and `policy` are unknown keys to
            // the typed half, and a strict Json would refuse them.
            val typed = CnctJson.decodeFromJsonElement(Typed.serializer(), whole)
            return CnctBookingRules(
                timezone = typed.timezone,
                capacity = typed.capacity,
                slotMinutes = typed.slotMinutes,
                services = typed.services,
                resources = typed.resources,
                raw = whole,
            )
        }

        override fun serialize(encoder: Encoder, value: CnctBookingRules) {
            JsonObject.serializer().serialize(encoder, value.raw)
        }
    }
}

/**
 * Something the business can be booked for.
 *
 * The tools call its id `serviceId` and its length `minutes`; the catalogue's rules call them `id`
 * and `durationMinutes`. Both are read, so the same type serves both.
 */
@Serializable
public data class CnctService(
    @JsonNames("id")
    public val serviceId: String = "",
    public val name: String = "",
    /** How long it takes. Null where the business books generically. */
    @JsonNames("durationMinutes")
    public val minutes: Int? = null,
    public val description: String? = null,
)

/**
 * A named person or table that can be asked for.
 *
 * An empty list is a real answer rather than a missing one: a business that assigns whoever is free
 * has switched named preference off deliberately, and an app must not offer a choice it has been
 * told not to offer.
 */
@Serializable
public data class CnctBookableResource(
    @JsonNames("id")
    public val resourceId: String = "",
    public val name: String = "",
    /** `person`, `table`, `room`… lower-cased by the platform, and again here for the rules. */
    @Serializable(with = LowercaseStringSerializer::class)
    public val kind: String? = null,
    public val seats: Int? = null,
)

/** A free time. */
@Serializable
public data class CnctSlot(
    /** The label to show — already in the business's clock, e.g. "19:30". */
    public val time: String = "",
    /**
     * **Pass this back exactly as given.** It is the token `create` matches a slot on; reformatting
     * it, or rebuilding it from a parsed [Instant], is how a booking lands on a time that was never
     * offered. [startsAtUtc] is for display arithmetic only.
     */
    public val startsAt: String = "",
    /** Who or what is free then. Only present when a specific person or table was asked for. */
    @SerialName("with")
    public val resourceName: String? = null,
) {
    /** The instant, parsed. Convenient for sorting and grouping; never send it back — send [startsAt]. */
    public val startsAtUtc: Instant? get() = parseInstant(startsAt)
}

/** What a day looks like. */
@Serializable
public data class CnctAvailability(
    public val date: String = "",
    /**
     * Empty means closed or full. [note] says which, and it is worth showing: "nothing is free" and
     * "no table seats six" send a customer to different places.
     */
    public val free: List<CnctSlot> = emptyList(),
    public val note: String? = null,
) {
    public val isEmpty: Boolean get() = free.isEmpty()
}

/** A booking that now exists. */
@Serializable
public data class CnctBookingConfirmation(
    public val bookingId: String = "",
    /** In the business's clock and its words — "Tuesday 12 August at 19:30". Ready to read back. */
    @SerialName("when")
    public val whenText: String = "",
    /** The name the booking is under, which is not always the customer's. */
    public val name: String? = null,
    public val partySize: Int? = null,
    /** The person or table allocated, where the business names them. */
    @SerialName("with")
    public val resourceName: String? = null,
    /**
     * True when a sandbox key made it: it is kept so it can be found, moved and cancelled, but it
     * holds no slot, nobody was told, and the business will never see it.
     */
    public val sandbox: Boolean = false,
)

/** One of a customer's upcoming bookings. */
@Serializable
public data class CnctBookingSummary(
    public val bookingId: String = "",
    @SerialName("when")
    public val whenText: String = "",
    public val name: String? = null,
    public val partySize: Int? = null,
)

/** A kind of work the business hands over. */
@Serializable
public data class CnctTicketType(
    public val ticketTypeId: String = "",
    public val name: String = "",
    public val description: String? = null,
)

/** One field a ticket type still needs. */
@Serializable
public data class CnctTicketField(
    /** Use this exact key in `fields` when raising the ticket. Never invent one. */
    public val key: String = "",
    @SerialName("required")
    public val isRequired: Boolean = false,
    /** Already phrased as a question, so it can go straight on a form label. */
    public val question: String = "",
    /** When non-empty, the only accepted values. */
    @Serializable(with = StringListSerializer::class)
    public val mustBeOneOf: List<String> = emptyList(),
)

/** What one ticket type needs before it can be raised. */
@Serializable
public data class CnctTicketTypeDetail(
    public val ticketTypeId: String = "",
    public val name: String = "",
    /** What the platform already has for this customer. Do not ask for these again. */
    public val alreadyKnown: JsonObject = JsonObject(emptyMap()),
    /**
     * What is still missing. The subtraction is done on the server, so this list is exactly the
     * questions worth putting in front of somebody.
     */
    public val askFor: List<CnctTicketField> = emptyList(),
)

/** A ticket that now exists. */
@Serializable
public data class CnctRaisedTicket(
    /** What a customer quotes back. A sandbox has its own numbering, from 1. */
    public val ticketNumber: Int = 0,
    public val note: String? = null,
    /** True when a sandbox key raised it: it is in no queue and nobody will work it. */
    public val sandbox: Boolean = false,
)

/** One of a customer's tickets. */
@Serializable
public data class CnctTicketSummary(
    public val ticketNumber: Int = 0,
    public val title: String = "",
    public val status: String = "",
    /** The date it was raised, `YYYY-MM-DD`. */
    public val raised: String? = null,
)

/** One line of a ticket's story, in words a customer can be told. Never who did it. */
@Serializable
public data class CnctTicketMilestone(
    /** `YYYY-MM-DD`. */
    public val on: String = "",
    /** "Raised", "Being worked on", "Waiting on the customer", "The customer added: …". */
    public val what: String = "",
)

/** One of a ticket type's own fields, as it was filled in, labelled by its display name. */
@Serializable
public data class CnctTicketDetailField(
    public val name: String = "",
    /** Whatever was filled in — usually a string, but a field can hold a number, a flag or a list. */
    public val value: JsonElement = JsonNull,
)

/**
 * One of a customer's tickets, as the customer may see it.
 *
 * What is missing is deliberate: no person's name anywhere, no team, and none of the notes a
 * business writes for its own staff. It is safe to show to the customer it belongs to — and only to
 * them.
 */
@Serializable
public data class CnctTicketDetail(
    public val ticketNumber: Int = 0,
    public val title: String = "",
    /** The kind of work, by the business's own name for it. */
    public val kind: String? = null,
    /** `OPEN`, `IN_PROGRESS`, `WAITING`, `RESOLVED`, `CLOSED` or `CANCELLED`. */
    public val status: String = "",
    /**
     * True when the business is waiting on the customer — for an answer, a photo, a time. Ask them
     * what is needed and pass it on with [CnctTickets.addTo], which starts the ticket moving again.
     */
    public val waitingOnCustomer: Boolean = false,
    /** `YYYY-MM-DD`. */
    public val raised: String? = null,
    public val resolved: String? = null,
    public val closed: String? = null,
    public val customerRequest: String? = null,
    public val desiredOutcome: String? = null,
    /** The ticket type's own fields, labelled by their display names. */
    public val details: List<CnctTicketDetailField> = emptyList(),
    public val history: List<CnctTicketMilestone> = emptyList(),
    /** How to talk about it — worth following, it is written for exactly that. */
    public val note: String? = null,
)

/** What happened to a follow-up passed on with [CnctTickets.addTo]. */
@Serializable
public data class CnctTicketFollowUp(
    public val ticketNumber: Int = 0,
    /** True when the ticket was waiting on the customer and is now being worked on again. */
    public val resumed: Boolean = false,
    public val note: String? = null,
)

/**
 * A list, plus what the platform said about it.
 *
 * The note is not decoration: an empty list of people means "this business assigns whoever is free
 * — never offer a choice", and an empty list of times means the day is closed, or full, or that no
 * table seats six. Dropping the sentence loses the difference.
 */
public data class CnctListing<out T>(
    public val items: List<T>,
    public val note: String? = null,
    /**
     * Present when the platform refused rather than answered — no ticket types configured, say. The
     * list will be empty.
     */
    public val refusal: String? = null,
) {
    public val isEmpty: Boolean get() = items.isEmpty()

    public val size: Int get() = items.size
}
