package ooo.doo.cnct

/**
 * A part of CNCT a credential can open.
 *
 * Not decoration: the two credentials are genuinely different keys to different doors, and an app
 * that asks [Cnct.modulesFor] what it holds can hide the tabs it cannot serve instead of finding out
 * with a 401 in front of a customer.
 */
public enum class CnctModule {
    /** Live chat with an inbox, as a visitor. */
    CHAT,

    /** The calendar: availability, bookings, reschedules, cancellations. */
    BOOKINGS,

    /** Tickets raised for work somebody has to pick up. */
    TICKETS,
}

/**
 * The entry point. Holds the [CnctConfig] and hands out the clients a credential opens.
 *
 * You do not have to use it — every client can be constructed directly — but it is the shortest path
 * from "here is our host and here is our key" to something working, and it is the one place the
 * host lives.
 *
 * ```kotlin
 * val cnct = Cnct()                                // https://app.doo.ooo
 * val local = Cnct.host("http://10.0.2.2:3001")
 * ```
 *
 * **Swapping hosts** is [withBaseUrl]. Clients already made keep the host they were made with — they
 * hold open sockets and in-flight requests, and silently re-pointing those mid-conversation would be
 * worse than making a new client and saying so.
 */
public class Cnct(public val config: CnctConfig = CnctConfig()) {
    /** The same SDK pointed somewhere else. Everything but the host is carried over. */
    public fun withBaseUrl(baseUrl: String): Cnct = Cnct(config.copy(baseUrl = baseUrl))

    /** Bookings and tickets, on an account-wide API key. **Server-side only** — see [CnctApiKey]. */
    public fun agent(credentials: CnctApiKey): CnctAgentClient = CnctAgentClient(config, credentials)

    public companion object {
        /** The short form, for the common case of a host and nothing else. */
        public fun host(baseUrl: String): Cnct = Cnct(CnctConfig(baseUrl = baseUrl))

        /**
         * What a credential opens. Tickets also need the account to hold ticketing — the agent
         * client's catalogue says what a particular account actually offers.
         */
        public fun modulesFor(credentials: CnctCredentials): Set<CnctModule> = when (credentials) {
            is CnctChatPublicKey -> setOf(CnctModule.CHAT)
            is CnctApiKey -> setOf(CnctModule.BOOKINGS, CnctModule.TICKETS)
        }
    }
}
