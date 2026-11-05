package ooo.doo.cnct

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The account's own operations — bookings and tickets — against the API key CNCT issued you.
 *
 * **This credential is account-wide. Keep it on a server.** Everything here reads and writes on
 * behalf of the whole account, and the customer each call is about is named by phone number rather
 * than inferred from a session. An app that held this key could book for, and cancel, anybody. The
 * intended shape is: your app talks to your backend, your backend holds the key and uses this. This
 * artifact is plain Kotlin on the JVM so that it can run there.
 *
 * ```kotlin
 * val agent = CnctAgentClient(
 *     config = CnctConfig(), // https://app.doo.ooo
 *     credentials = CnctApiKey(System.getenv("CNCT_API_KEY")),
 * )
 * val day = agent.bookings.checkAvailability(date = "2026-09-12", partySize = 4)
 * if (!day.isEmpty) {
 *     agent.bookings.create(
 *         startsAt = day.free.first().startsAt, // exactly as given
 *         customerPhone = "+97312345678",
 *         name = "Layla",
 *     )
 * }
 * ```
 *
 * Every call is a `suspend` function and safe to make from any dispatcher: the HTTP work happens on
 * OkHttp's own threads.
 */
public class CnctAgentClient(
    config: CnctConfig,
    credentials: CnctApiKey,
    /** Pass one to share an [okhttp3.OkHttpClient] with the rest of your app. */
    transport: CnctTransport = CnctTransport(config),
) : CnctClient {
    private val transport: CnctTransport = transport
    private val headers: Map<String, String> = credentials.headers

    /** [CnctKeyMode.SANDBOX] for a `kaer_sk_test_` key — see [CnctApiKey]. */
    public val mode: CnctKeyMode = credentials.mode

    /** The calendar. */
    public val bookings: CnctBookings = CnctBookings(this)

    /** Tickets — the work a business hands to a colleague. */
    public val tickets: CnctTickets = CnctTickets(this)

    /**
     * What this account's credential may actually do, and the rules it must do it within.
     *
     * Filtered per account: an account without ticketing is not shown ticket tools. Read this rather
     * than assuming — it is also the cheapest way to check a key works.
     */
    public suspend fun catalogue(): CnctCatalogue {
        val answer = asObject(transport.get("/api/tools", headers = headers))
        return CnctCatalogue(
            tools = answer.objects("tools").map { decodeModel(CnctTool.serializer(), it) },
            rules = decodeModel(CnctBookingRules.serializer(), answer["rules"] as? JsonObject ?: JsonObject(emptyMap())),
        )
    }

    /**
     * Call any tool by name, and get back exactly what the platform sent.
     *
     * The escape hatch, and a supported one: a tool added to the platform after this SDK was
     * published is reachable here on the day it ships, without waiting for a release. **It does not
     * throw on a refusal** — a tool that declines answers `200` with an `error` sentence, and this
     * hands that back for you to read. Use [callOrThrow] for the other behaviour.
     *
     * [args] may be a [JsonObject] or a plain map of strings, numbers, booleans, lists and maps.
     */
    public suspend fun call(tool: String, args: Map<String, Any?> = emptyMap()): JsonObject {
        val answer = transport.post(
            "/api/tools/${CnctConfig.pathSegment(tool)}",
            body = toJsonObject(args),
            headers = headers,
        )
        return asObject(answer)
    }

    /** [call], but a refusal becomes a [CnctException] with [CnctErrorCode.TOOL_REFUSED]. */
    public suspend fun callOrThrow(tool: String, args: Map<String, Any?> = emptyMap()): JsonObject {
        val result = call(tool, args)
        val refusal = result["error"] as? JsonPrimitive
        if (refusal != null && refusal.isString) {
            throw CnctException(refusal.content, code = CnctErrorCode.TOOL_REFUSED, details = result)
        }
        return result
    }

    override fun toString(): String = "CnctAgentClient(${mode.name.lowercase()})"
}

/**
 * The calendar, as an account's software sees it.
 *
 * Every method that is about a particular person takes `customerPhone` in full international form.
 * That is not a convenience parameter — it *is* the authorization boundary. There is no ambient
 * identity out here, so the number is what decides whose booking may be read and changed, and the
 * platform resolves it itself rather than trusting an id a caller could have guessed.
 */
public class CnctBookings internal constructor(private val client: CnctAgentClient) {
    /** What the business can be booked for. An empty list means it books generically. */
    public suspend fun services(): CnctListing<CnctService> =
        listing(client.call("list_services"), "services", CnctService.serializer())

    /**
     * The people and tables that can be asked for by name.
     *
     * **An empty list is an instruction, not an absence.** A business that has switched named
     * preference off is telling you not to offer a choice of person — the reason accounts turn it
     * off is that one popular stylist absorbs every request while three others sit idle.
     * [CnctListing.note] says which case you are in.
     */
    public suspend fun people(): CnctListing<CnctBookableResource> =
        listing(client.call("list_people"), "people", CnctBookableResource.serializer())

    /**
     * The free times on a date. **Always call this before offering anybody a time.**
     *
     * [date] is `YYYY-MM-DD` in the business's own timezone — the one [CnctBookingRules.timezone]
     * names, which is not necessarily the device's.
     */
    public suspend fun checkAvailability(
        date: String,
        serviceId: String? = null,
        resourceId: String? = null,
        partySize: Int? = null,
        locationId: String? = null,
    ): CnctAvailability {
        val result = client.callOrThrow(
            "check_availability",
            present(
                "date" to date,
                "serviceId" to serviceId,
                "resourceId" to resourceId,
                "partySize" to partySize,
                "locationId" to locationId,
            ),
        )
        return decodeModel(CnctAvailability.serializer(), result)
    }

    /**
     * Take a booking at a time [checkAvailability] reported free.
     *
     * Pass [startsAt] exactly as the slot gave it. Rebuilding it from a parsed `Instant` is how a
     * booking lands on a time nobody offered.
     *
     * [nameIsTheCaller] is small and load-bearing. A booking's name and a customer's name are
     * different facts about different people: somebody booking a haircut for their daughter gives
     * their daughter's name, and filing it against the number would rename the contact
     * permanently. Leave it false unless the name given is the phone number's own owner.
     */
    public suspend fun create(
        startsAt: String,
        customerPhone: String,
        serviceId: String? = null,
        resourceId: String? = null,
        partySize: Int? = null,
        name: String? = null,
        nameIsTheCaller: Boolean = false,
        notes: String? = null,
        locationId: String? = null,
    ): CnctBookingConfirmation {
        val result = client.callOrThrow(
            "create_booking",
            present(
                "startsAt" to startsAt,
                "customerPhone" to customerPhone,
                "serviceId" to serviceId,
                "resourceId" to resourceId,
                "partySize" to partySize,
                "name" to name,
                "nameIsTheCaller" to nameIsTheCaller,
                "notes" to notes,
                "locationId" to locationId,
            ),
        )
        return decodeModel(CnctBookingConfirmation.serializer(), result)
    }

    /**
     * One customer's upcoming bookings. Call this before changing anything — you need the id, and
     * the lookup is also what proves the booking is theirs.
     */
    public suspend fun forCustomer(customerPhone: String): CnctListing<CnctBookingSummary> =
        listing(
            client.call("find_my_booking", mapOf("customerPhone" to customerPhone)),
            "bookings",
            CnctBookingSummary.serializer(),
        )

    /**
     * Move one of that customer's bookings to a different free time. Returns the new time in the
     * business's words, for the sentence you say back to them.
     */
    public suspend fun reschedule(bookingId: String, startsAt: String, customerPhone: String): String {
        val result = client.callOrThrow(
            "reschedule_booking",
            mapOf("bookingId" to bookingId, "startsAt" to startsAt, "customerPhone" to customerPhone),
        )
        return result.string("when") ?: ""
    }

    /**
     * Cancel one of that customer's bookings. Returns the time it *was* at, for the sentence you say
     * back to them.
     */
    public suspend fun cancel(bookingId: String, customerPhone: String): String {
        val result = client.callOrThrow(
            "cancel_booking",
            mapOf("bookingId" to bookingId, "customerPhone" to customerPhone),
        )
        return result.string("was") ?: ""
    }
}

/** Tickets: the work a business could not finish in the moment and hands to a colleague. */
public class CnctTickets internal constructor(private val client: CnctAgentClient) {
    /**
     * The kinds of work this business hands over. Start here — the type decides what facts are
     * needed.
     *
     * An empty list with a [CnctListing.refusal] means no ticket types are configured yet, which is
     * an account setup step rather than a failure of this call.
     */
    public suspend fun types(): CnctListing<CnctTicketType> =
        listing(client.call("list_ticket_types"), "types", CnctTicketType.serializer())

    /**
     * What one type still needs before it can be raised.
     *
     * The subtraction happens on the server: [CnctTicketTypeDetail.askFor] is already only the
     * questions worth asking, with what the platform knows about this customer removed. Ask for those
     * and nothing else.
     */
    public suspend fun type(ticketTypeId: String): CnctTicketTypeDetail {
        val result = client.callOrThrow("get_ticket_type", mapOf("ticketTypeId" to ticketTypeId))
        return decodeModel(CnctTicketTypeDetail.serializer(), result)
    }

    /**
     * Raise one.
     *
     * [fields] is keyed exactly as [type] named them — never invent a key. Values are strings,
     * numbers, booleans, lists or maps. [idempotencyKey] is worth sending: retry with the same value
     * and you get the same ticket back rather than a second one. Omit it and every call raises a new
     * ticket.
     */
    public suspend fun create(
        ticketTypeId: String,
        title: String,
        reasonUnresolved: String,
        customerPhone: String? = null,
        customerRequest: String? = null,
        desiredOutcome: String? = null,
        actionsTaken: String? = null,
        recommendedNextAction: String? = null,
        priority: String? = null,
        fields: Map<String, Any?> = emptyMap(),
        idempotencyKey: String? = null,
    ): CnctRaisedTicket {
        val result = client.callOrThrow(
            "create_ticket",
            present(
                "ticketTypeId" to ticketTypeId,
                "title" to title,
                "reasonUnresolved" to reasonUnresolved,
                "customerPhone" to customerPhone,
                "customerRequest" to customerRequest,
                "desiredOutcome" to desiredOutcome,
                "actionsTaken" to actionsTaken,
                "recommendedNextAction" to recommendedNextAction,
                "priority" to priority,
                "fields" to fields.takeIf { it.isNotEmpty() },
                "idempotencyKey" to idempotencyKey,
            ),
        )
        return decodeModel(CnctRaisedTicket.serializer(), result)
    }

    /**
     * One customer's tickets, found by their number — open ones unless [includeResolved]. Narrow
     * with [q] (a word from what it is about) or [ticketNumber]. Never describe a ticket to anybody
     * but the person it belongs to.
     */
    public suspend fun forCustomer(
        customerPhone: String,
        q: String? = null,
        ticketNumber: Int? = null,
        includeResolved: Boolean = false,
    ): CnctListing<CnctTicketSummary> {
        val result = client.call(
            "find_my_tickets",
            present(
                "customerPhone" to customerPhone,
                "q" to q,
                "ticketNumber" to ticketNumber,
                // Only sent when asked for: the platform's default is the one an app wants.
                "includeResolved" to (true.takeIf { includeResolved }),
            ),
        )
        return listing(result, "tickets", CnctTicketSummary.serializer())
    }

    /**
     * One of this customer's tickets in detail, as the customer may see it: its status, whether it
     * is waiting on them, what they asked for, the details on it and what has happened so far.
     *
     * There are no names in it — not who is handling it, not who moved it — and none of the notes
     * the team writes for each other. Somebody else's ticket number is refused exactly as a number
     * that does not exist is, so this cannot be used to find out whether a ticket exists.
     *
     * Needs a CNCT host from 2026-09-29 or later (it serves `get_ticket`).
     */
    public suspend fun get(customerPhone: String, ticketNumber: Int): CnctTicketDetail {
        val result = client.callOrThrow(
            "get_ticket",
            mapOf("customerPhone" to customerPhone, "ticketNumber" to ticketNumber),
        )
        return decodeModel(CnctTicketDetail.serializer(), result)
    }

    /**
     * Pass something the customer said on to whoever is handling one of their open tickets — new
     * information, an answer to a question, "it got worse". **Use this rather than [create]** when
     * they are following up on something already open: a second ticket splits the story in two.
     *
     * If the ticket was waiting on the customer, this starts it moving again
     * ([CnctTicketFollowUp.resumed]). A resolved, closed or cancelled ticket refuses — raise a new
     * one that mentions the old number. The same words sent twice within ten minutes are kept once,
     * so a retry is safe.
     *
     * Needs a CNCT host from 2026-09-29 or later (it serves `add_to_ticket`).
     */
    public suspend fun addTo(customerPhone: String, ticketNumber: Int, note: String): CnctTicketFollowUp {
        val result = client.callOrThrow(
            "add_to_ticket",
            mapOf("customerPhone" to customerPhone, "ticketNumber" to ticketNumber, "note" to note),
        )
        // The number comes back even when the platform's answer leaves it out.
        val withNumber = JsonObject(mapOf("ticketNumber" to JsonPrimitive(ticketNumber)) + result)
        return decodeModel(CnctTicketFollowUp.serializer(), withNumber)
    }
}

/** The arguments that were given. A tool told `serviceId: null` is not the same as one told nothing. */
private fun present(vararg args: Pair<String, Any?>): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply { args.forEach { (key, value) -> if (value != null) put(key, value) } }

private fun <T> listing(result: JsonObject, key: String, serializer: DeserializationStrategy<T>): CnctListing<T> =
    CnctListing(
        items = result.objects(key).map { decodeModel(serializer, it) },
        note = result.string("note"),
        refusal = result.string("error"),
    )
