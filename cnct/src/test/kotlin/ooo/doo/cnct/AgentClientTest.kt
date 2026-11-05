package ooo.doo.cnct

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentClientTest {
    /** One request as the platform saw it. The body is read once, inside the dispatcher. */
    private class Seen(val path: String, val authorization: String?, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<Seen>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        seen.clear()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun config() = CnctConfig(baseUrl = server.url("/").toString())

    /** The platform, as far as these tests are concerned: a catalogue, and a tool that answers. */
    private fun clientWith(catalogue: String = CATALOGUE, answer: (tool: String, args: JsonObject) -> String): CnctAgentClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val body = request.body.readUtf8()
                seen += Seen(url.encodedPath, request.getHeader("authorization"), body)
                if (url.encodedPath == "/api/tools") return json(catalogue)
                return json(answer(url.pathSegments.last(), Json.parseToJsonElement(body).jsonObject))
            }
        }
        return CnctAgentClient(config(), CnctApiKey("kaer_sk_test"))
    }

    private fun json(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("content-type", "application/json").setBody(body)

    @Test
    fun `the catalogue says what this account may do, and the rules it works under`() = runTest {
        val agent = clientWith { _, _ -> "{}" }
        val answer = agent.catalogue()

        assertEquals("check_availability", answer.tools.single().name)
        assertEquals("Asia/Bahrain", answer.rules.timezone)
        assertEquals(90, answer.rules.services.single().minutes)
        assertEquals(6, answer.rules.resources.single().seats)
        // Read under the rules' own keys, and lower-cased.
        assertEquals("svc_1", answer.rules.services.single().serviceId)
        assertEquals("table", answer.rules.resources.single().kind)
        // The unmodelled half is still reachable.
        assertEquals(true, answer.rules.raw["policy"]!!.jsonObject["allowResourcePreference"]!!.jsonPrimitive.boolean)
        assertEquals("Bearer kaer_sk_test", seen.single().authorization)
    }

    @Test
    fun `a tool that declines answers 200 with a sentence, and that becomes an exception`() = runTest {
        val agent = clientWith { _, _ -> """{"error":"That time is in the past."}""" }

        val error = assertFailsWith<CnctException> { agent.bookings.checkAvailability(date = "2020-01-01") }
        assertEquals(CnctErrorCode.TOOL_REFUSED, error.code)
        assertEquals("That time is in the past.", error.message)
        // Everything the tool answered travels with it.
        assertEquals(JsonPrimitive("That time is in the past."), error.details!!.jsonObject["error"])
    }

    @Test
    fun `the raw escape hatch hands a refusal back rather than raising it`() = runTest {
        val agent = clientWith { _, _ -> """{"error":"Nope."}""" }
        val result = agent.call("some_new_tool", mapOf("x" to 1))
        assertEquals("Nope.", result["error"]!!.jsonPrimitive.content)
        assertEquals("/api/tools/some_new_tool", seen.single().path)
        assertEquals(1, seen.single().json["x"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `availability parses, and startsAt survives as the exact token to send back`() = runTest {
        val agent = clientWith { _, args ->
            """
            {"date":${args["date"]},
             "free":[{"time":"19:30","startsAt":"2026-09-15T16:30:00.000Z"},
                     {"time":"20:00","startsAt":"2026-09-15T17:00:00.000Z","with":"Table 4"}],
             "note":"Offer only these times."}
            """
        }

        val day = agent.bookings.checkAvailability(date = "2026-09-15", partySize = 4)
        assertEquals(2, day.free.size)
        assertEquals("2026-09-15T16:30:00.000Z", day.free.first().startsAt)
        assertEquals("Table 4", day.free.last().resourceName)
        assertEquals(Instant.parse("2026-09-15T16:30:00.000Z"), day.free.first().startsAtUtc)

        val sent = seen.single().json
        assertEquals("2026-09-15", sent["date"]!!.jsonPrimitive.content)
        assertEquals(4, sent["partySize"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `an empty day is an answer with a reason, not an error`() = runTest {
        val agent = clientWith { _, args ->
            """{"date":${args["date"]},"free":[],"note":"No table seats six that day. Offer the caller another date."}"""
        }

        val day = agent.bookings.checkAvailability(date = "2026-09-15", partySize = 6)
        assertTrue(day.isEmpty)
        assertTrue(day.note!!.contains("seats six"))
    }

    @Test
    fun `creating a booking sends startsAt verbatim and names the customer by number`() = runTest {
        val agent = clientWith { _, _ ->
            """
            {"confirmed":true,"bookingId":"bkg_1","when":"Tuesday 15 September at 19:30",
             "partySize":4,"name":"Layla","with":"Table 4"}
            """
        }

        val booking = agent.bookings.create(
            startsAt = "2026-09-15T16:30:00.000Z",
            customerPhone = "+97312345678",
            name = "Layla",
            partySize = 4,
        )

        assertEquals("bkg_1", booking.bookingId)
        assertEquals("Tuesday 15 September at 19:30", booking.whenText)
        assertEquals("Table 4", booking.resourceName)

        val sent = seen.single().json
        assertEquals("2026-09-15T16:30:00.000Z", sent["startsAt"]!!.jsonPrimitive.content)
        assertEquals("+97312345678", sent["customerPhone"]!!.jsonPrimitive.content)
        // Defaults false: a name given for somebody else must not rename the number's owner.
        assertEquals(false, sent["nameIsTheCaller"]!!.jsonPrimitive.boolean)
        // Nothing unasked-for travels.
        assertFalse("serviceId" in sent)
    }

    @Test
    fun `a key knows which kind it is from its prefix`() {
        assertEquals(CnctKeyMode.SANDBOX, CnctApiKey("kaer_sk_test_abc").mode)
        assertTrue(CnctApiKey("kaer_sk_test_abc").isSandbox)
        assertEquals(CnctKeyMode.PRODUCTION, CnctApiKey("kaer_sk_live_abc").mode)
        // Minted before there were two kinds, and production.
        assertEquals(CnctKeyMode.PRODUCTION, CnctApiKey("kaer_sk_mintedbeforemodes").mode)
    }

    @Test
    fun `a sandbox booking and ticket say so, and a production one does not`() = runTest {
        val agent = clientWith { _, _ ->
            """
            {"ok":true,"ticketNumber":1,"confirmed":true,"bookingId":"sbx_1",
             "when":"Tuesday 15 September at 19:30","sandbox":true}
            """
        }
        val booking = agent.bookings.create(startsAt = "2026-09-15T16:30:00.000Z", customerPhone = "+97312345678")
        val ticket = agent.tickets.create(ticketTypeId = "tt_1", title = "Leaking tap", reasonUnresolved = "Needs a plumber")
        assertTrue(booking.sandbox)
        assertTrue(ticket.sandbox)
        assertEquals(CnctKeyMode.PRODUCTION, agent.mode)
        assertEquals(CnctKeyMode.SANDBOX, Cnct().agent(CnctApiKey("kaer_sk_test_abc")).mode)

        val real = CnctJson.decodeFromString(CnctBookingConfirmation.serializer(), """{"bookingId":"bkg_1","when":"Today"}""")
        assertFalse(real.sandbox)
    }

    @Test
    fun `an empty list of people keeps the sentence that says why`() = runTest {
        val agent = clientWith { _, _ ->
            """{"people":[],"note":"This business assigns whoever is free. Never offer the caller a choice of person."}"""
        }

        val people = agent.bookings.people()
        assertTrue(people.isEmpty)
        assertTrue(people.note!!.contains("Never offer"))
    }

    @Test
    fun `ticket types with nothing configured come back empty and say so`() = runTest {
        val agent = clientWith { _, _ ->
            """{"types":[],"error":"Not raised. No ticket types are set up on this account yet."}"""
        }

        val types = agent.tickets.types()
        assertTrue(types.isEmpty)
        assertTrue(types.refusal!!.contains("No ticket types"))
    }

    @Test
    fun `a ticket type reports only the questions still worth asking`() = runTest {
        val agent = clientWith { _, _ ->
            """
            {"ticketTypeId":"tt_1","name":"Maintenance","alreadyKnown":{"flat":"12B"},
             "askFor":[{"key":"issue","required":true,"question":"What is wrong?"},
                       {"key":"urgency","required":false,"question":"How urgent is it?","mustBeOneOf":["low","high"]}]}
            """
        }

        val type = agent.tickets.type("tt_1")
        assertEquals("12B", type.alreadyKnown["flat"]!!.jsonPrimitive.content)
        assertEquals(listOf("issue", "urgency"), type.askFor.map { it.key })
        assertEquals(listOf("low", "high"), type.askFor.last().mustBeOneOf)
        assertTrue(type.askFor.first().isRequired)
    }

    @Test
    fun `an idempotency key travels, so a retry returns the same ticket`() = runTest {
        val agent = clientWith { _, _ -> """{"ok":true,"ticketNumber":1042,"note":"Raised as #1042."}""" }

        val ticket = agent.tickets.create(
            ticketTypeId = "tt_1",
            title = "Leaking tap",
            reasonUnresolved = "Needs a plumber",
            customerPhone = "+97312345678",
            fields = mapOf("flat" to "12B"),
            idempotencyKey = "attempt-1",
        )

        assertEquals(1042, ticket.ticketNumber)
        val sent = seen.single().json
        assertEquals("attempt-1", sent["idempotencyKey"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"flat":"12B"}"""), sent["fields"])
    }

    @Test
    fun `every call goes to slash api slash tools, not the old booking-tools`() = runTest {
        val agent = clientWith { _, _ -> """{"services":[]}""" }
        agent.catalogue()
        agent.bookings.services()
        assertEquals(listOf("/api/tools", "/api/tools/list_services"), seen.map { it.path })
    }

    @Test
    fun `a customer's tickets can be narrowed, and nothing unasked-for is sent`() = runTest {
        val agent = clientWith { _, _ -> """{"tickets":[]}""" }

        agent.tickets.forCustomer("+97312345678", q = "refund", includeResolved = true)
        assertEquals(
            Json.parseToJsonElement("""{"customerPhone":"+97312345678","q":"refund","includeResolved":true}"""),
            seen.last().json,
        )

        agent.tickets.forCustomer("+97312345678")
        assertEquals(Json.parseToJsonElement("""{"customerPhone":"+97312345678"}"""), seen.last().json)
    }

    @Test
    fun `one ticket reads as the customer may see it`() = runTest {
        val agent = clientWith { _, _ ->
            """
            {"ticketNumber":1042,"title":"Refund not received","kind":"Refund","status":"WAITING",
             "waitingOnCustomer":true,"raised":"2026-09-18",
             "details":[{"name":"Order number","value":"881"}],
             "history":[{"on":"2026-09-18","what":"Raised"},{"on":"2026-09-19","what":"Waiting on the customer"}],
             "note":"This is waiting on the customer."}
            """
        }

        val ticket = agent.tickets.get("+97312345678", 1042)

        assertEquals("/api/tools/get_ticket", seen.single().path)
        assertEquals(Json.parseToJsonElement("""{"customerPhone":"+97312345678","ticketNumber":1042}"""), seen.single().json)
        assertEquals("WAITING", ticket.status)
        assertTrue(ticket.waitingOnCustomer)
        assertEquals("Refund", ticket.kind)
        assertEquals("Order number", ticket.details.single().name)
        assertEquals(JsonPrimitive("881"), ticket.details.single().value)
        assertEquals(listOf("Raised", "Waiting on the customer"), ticket.history.map { it.what })
        assertNull(ticket.resolved)
    }

    @Test
    fun `a ticket that is not theirs is a refusal, not an empty ticket`() = runTest {
        val agent = clientWith { _, _ -> """{"error":"No ticket #9 belongs to them."}""" }

        val error = assertFailsWith<CnctException> { agent.tickets.get("+97312345678", 9) }
        assertEquals(CnctErrorCode.TOOL_REFUSED, error.code)
        assertEquals("No ticket #9 belongs to them.", error.message)
    }

    @Test
    fun `a follow-up lands on the ticket and says when it started it moving again`() = runTest {
        val agent = clientWith { _, _ -> """{"ok":true,"resumed":true,"note":"Added."}""" }

        val added = agent.tickets.addTo(customerPhone = "+97312345678", ticketNumber = 1042, note = "The order number is 881")

        assertEquals("/api/tools/add_to_ticket", seen.single().path)
        assertEquals(
            Json.parseToJsonElement("""{"customerPhone":"+97312345678","ticketNumber":1042,"note":"The order number is 881"}"""),
            seen.single().json,
        )
        // The number comes back even when the platform's answer leaves it out.
        assertEquals(1042, added.ticketNumber)
        assertTrue(added.resumed)
    }

    @Test
    fun `a finished ticket refuses a follow-up in the platform's own words`() = runTest {
        val agent = clientWith { _, _ -> """{"error":"Not added. #7 is resolved."}""" }

        val error = assertFailsWith<CnctException> {
            agent.tickets.addTo(customerPhone = "+97312345678", ticketNumber = 7, note = "Again")
        }
        assertEquals(CnctErrorCode.TOOL_REFUSED, error.code)
        assertEquals("Not added. #7 is resolved.", error.message)
    }

    @Test
    fun `an HTTP failure is still an HTTP failure, not a refusal`() = runTest {
        server.enqueue(json("""{"error":"Send an API key as `Authorization: Bearer kaer_sk_…`"}""", status = 401))
        val agent = CnctAgentClient(config(), CnctApiKey("kaer_sk_bad"))

        val error = assertFailsWith<CnctException> { agent.bookings.services() }
        assertEquals(CnctErrorCode.UNAUTHENTICATED, error.code)
        assertTrue(error.isUnauthenticated)
    }

    // ── Beyond the Flutter suite: what the Kotlin port adds ─────────────────────────────────────

    @Test
    fun `a response in the wrong shape is a bad response, not a serialization crash`() = runTest {
        val agent = clientWith { _, _ -> """{"date":"2026-09-15","free":"not a list"}""" }
        val error = assertFailsWith<CnctException> { agent.bookings.checkAvailability(date = "2026-09-15") }
        assertEquals(CnctErrorCode.BAD_RESPONSE, error.code)
    }

    @Test
    fun `fields the platform adds later are ignored, and unknown values do not break a list`() = runTest {
        val agent = clientWith { _, _ ->
            """{"services":[{"serviceId":"s1","name":"Cut","minutes":30,"colour":"red"},"junk",{"serviceId":"s2"}]}"""
        }
        val services = agent.bookings.services()
        assertEquals(listOf("s1", "s2"), services.items.map { it.serviceId })
        assertEquals(30, services.items.first().minutes)
        assertEquals("", services.items.last().name)
    }

    @Test
    fun `a value that cannot be sent as JSON is refused before anything is sent`() = runTest {
        val agent = clientWith { _, _ -> "{}" }
        val error = assertFailsWith<CnctException> { agent.call("x", mapOf("when" to Any())) }
        assertEquals(CnctErrorCode.INVALID, error.code)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `printing the client never prints the key`() {
        val agent = CnctAgentClient(CnctConfig(), CnctApiKey("kaer_sk_live_supersecretWXYZ"))
        assertFalse(agent.toString().contains("supersecret"))
    }

    private companion object {
        const val CATALOGUE = """
            {"tools":[{"name":"check_availability","description":"List the free times.","parameters":{}}],
             "rules":{"timezone":"Asia/Bahrain","capacity":4,"slotMinutes":30,
                      "services":[{"id":"svc_1","name":"Dinner","durationMinutes":90}],
                      "resources":[{"id":"res_1","name":"Table 4","kind":"TABLE","seats":6}],
                      "policy":{"allowResourcePreference":true}}}
        """
    }
}
