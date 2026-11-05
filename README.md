# CNCT for Android

The official CNCT SDK for Android and Kotlin. Live chat, bookings and tickets against a CNCT
workspace, using the credentials CNCT issues you.

> **Status: complete, not released.** Chat, bookings and tickets are ported from the
> [Flutter SDK](https://github.com/doo-inc/cnct-flutter-sdk) 0.2.0, behaviour for behaviour, and
> tested. There is no tag yet; do not depend on `main`.

**It is headless, and that is the whole point.** There is no view in this library and no web view.
A business building their own app has a design system already; shipping them a chat bubble to
restyle answers a question they did not ask. So this ships state and events, and the interface is
yours. [`sample`](sample) is a complete Compose chat screen built on it, in a few hundred lines you own
and can delete.

**The core is plain Kotlin on the JVM.** The same artifact runs in an Android app and on a server —
which matters, because an API key belongs on a server. What needs the Android SDK — a token store
that encrypts with an Android Keystore key — is a second, small artifact beside it.

Sister SDKs, same concepts and the same error codes:
[web](https://github.com/doo-inc/cnct-web-sdk) ·
[Flutter](https://github.com/doo-inc/cnct-flutter-sdk) ·
[iOS](https://github.com/doo-inc/cnct-ios-sdk).

## Install

Through [JitPack](https://jitpack.io/#doo-inc/cnct-android-sdk) until there is a Maven Central
release. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and in the module:

```kotlin
dependencies {
    // An Android app: the core, plus the Keystore-backed token store.
    implementation("com.github.doo-inc.cnct-android-sdk:cnct-android:v0.1.0")
    // A server, or anything else on the JVM: the core alone.
    implementation("com.github.doo-inc.cnct-android-sdk:cnct:v0.1.0")
}
```

Pin a tag: what a client ships is what their users run, and an SDK that moves under a released app
is a bug report nobody can reproduce. Needs the `INTERNET` permission. Pulls in OkHttp,
kotlinx.coroutines and kotlinx.serialization — most apps have all three already.

**Below API 26, enable core library desugaring.** Timestamps are `java.time.Instant`, which Android
has from 8.0; an app with a lower `minSdk` needs this in its module:

```kotlin
android {
    compileOptions { isCoreLibraryDesugaringEnabled = true }
}
dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5") }
```

## The host

CNCT runs at **`https://app.doo.ooo`**, and that is where this SDK points unless you say otherwise.
One value decides where everything goes — HTTP and the WebSocket alike, because the socket origin is
derived from it.

```kotlin
val cnct = Cnct()                                   // https://app.doo.ooo
val local = Cnct.host("http://10.0.2.2:3001")       // your machine, from the emulator
val staging = cnct.withBaseUrl("https://staging.example.com")
```

A base URL may carry a path, so fronting CNCT on your own domain works:
`https://theirdomain.com/support` puts every request under `/support`.

**Testing is not a different host.** What decides whether a booking is real is the key — a sandbox
key talks to the same `app.doo.ooo` and writes nothing real.

Clients already built keep the host they were built with. They hold open sockets and in-flight
requests, and silently re-pointing those mid-conversation would be worse than making a new one.

## Two credentials, two doors

| Credential                   | Opens                   | Where it belongs |
| ---------------------------- | ----------------------- | ---------------- |
| `CnctChatPublicKey("…")`     | Live chat, as a visitor | **Inside your app.** It identifies an inbox, not a person, and it is already public — it is in the URL of the hosted chat page. |
| `CnctApiKey("kaer_sk_…")`    | Bookings and tickets    | **On a server you control.** It is account-wide. Mint one in the CNCT console under **Settings → Developers**. |

```kotlin
Cnct.modulesFor(CnctChatPublicKey("inbox-key"))   // [CHAT]
Cnct.modulesFor(CnctApiKey(key))                  // [BOOKINGS, TICKETS]

when (val client = cnct.clientFor(credentials)) {  // whichever one somebody pasted
    is CnctChatClient -> showChat(client)
    is CnctAgentClient -> showBookings(client)
}
```

`toString()` on a credential never contains the secret — only the last four characters. Tickets also
need the account to hold ticketing; `agent.catalogue()` lists exactly the tools a particular account
offers, so read that rather than assuming.

## Sandbox and production

An API key comes in two kinds, and the key says which. The account's owner or an admin mints either
in the CNCT console, under **Settings → Developers**.

|                      | Sandbox — `kaer_sk_test_…`                             | Production — `kaer_sk_live_…`         |
| -------------------- | ------------------------------------------------------ | ------------------------------------- |
| Reads                | The real account: services, people, hours, what's free | The same                              |
| Book, move, cancel   | Kept in the account's sandbox, and findable again      | The real calendar                     |
| Raise a ticket       | Kept in the sandbox, numbered from 1                   | The real queue                        |
| Customers            | Never contacted, never looked up, never created        | Texted and filed as the account's own |
| Seen by the business | Never — not the calendar, the queue or the reports     | Everywhere                            |

Everything else is identical, and deliberately: a sandbox key runs **the same tools, the same
validation and the same refusals** as production. So an integration that works in sandbox is an
integration that works, and going live is swapping the key.

```kotlin
val key = CnctApiKey(System.getenv("CNCT_API_KEY"))
key.mode       // SANDBOX | PRODUCTION
key.isSandbox  // true for kaer_sk_test_…

val agent = Cnct().agent(key)
val booking = agent.bookings.create(startsAt = startsAt, customerPhone = "+97312345678")
booking.sandbox // true — kept so you can find, move and cancel it, but nobody will ever see it
```

`agent.mode` says the same thing before anything is sent. Keys minted before there were two kinds are
plain `kaer_sk_…`, and are production. **A sandbox booking does not take its slot** — availability is
the real calendar's, so the same free time can be booked twice in sandbox — and there is **one
sandbox per account**, shared by all its sandbox keys.

Live chat has no sandbox mode — its credential is an inbox's public key, not an API key. To build a
chat screen without landing in the team's real queue, ask for a second web inbox and use its key.

## Chat

```kotlin
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val chat = Cnct().chat(
        CnctChatPublicKey("the-inbox-public-key"),
        tokenStore = CnctKeystoreTokenStore(application),  // from cnct-android
        scope = viewModelScope,                           // closes itself in onCleared
    )

    init {
        viewModelScope.launch {
            val inbox = chat.boot()                       // greeting, whether it is open, whether it wants a number
            if (!chat.resume() && inbox.isActive) chat.start(displayName = "Layla")
        }
    }
}

// In a composable:
val state by viewModel.chat.state.collectAsStateWithLifecycle()
```

`boot()` is readable without a session — it is the front door, and it is what tells you whether to
draw a composer at all. `start()` mints a visitor, or reuses the one behind a stored token, and opens
the socket. `resume()` picks a stored session back up, and answers `false` for a first-time visitor —
which is not an error. From there, `chat.state` is everything a UI renders, and `chat.events` is what
it acts on.

### The state

`state` is a `StateFlow<CnctChatState>`, replaced rather than mutated on every change.

| Field           | What it is                                                                     |
| --------------- | ------------------------------------------------------------------------------ |
| `status`        | `IDLE` → `CONNECTING` / `LIVE` / `OFFLINE`, and `ENDED` once closed             |
| `inbox`         | What `boot()` returned                                                         |
| `conversation`  | `status`, `startedAt`, and `withPerson` — somebody is on the thread, never who  |
| `messages`      | Oldest first, with `pending` and `failed` on the visitor's own                  |
| `theyAreTyping` | True while the other side is typing; clears itself                             |
| `error`         | The last failure, or null                                                       |
| `canSend`       | Whether there is a conversation to type into                                   |

`OFFLINE` is not an error state. The transcript is still true, sends still go over HTTP, and a
reconnect is already backing off towards its next attempt.

`events` is a `SharedFlow<CnctChatEvent>` — `Connected`, `Disconnected`, `MessageReceived`,
`TypingReceived`, `ConversationUpdated`, `Closed`, `Errored`, and `Unauthenticated` when the stored
session was refused and cleared (start the visitor again). It is hot: collect it before you `start()`.

### Cards

Every message carries a `type`. It is `TEXT` for ordinary words, and a card for the few things a
customer is meant to read as something other than a sentence.

```kotlin
when (message.type) {
    CnctMessageType.TICKET_UPDATE -> renderTicket(message.ticket!!)   // ticketNumber, title, status, raisedAt
    CnctMessageType.BOOKING_UPDATE -> renderBooking(message.booking!!) // title, startsAt, endsAt, status, locationName, partySize
    else -> renderText(message.body)
}
```

**`body` always reads correctly on its own** — "We have opened ticket #1042 for this: Refund not
received" — so an app built before a card type existed shows something true rather than an empty
bubble. That `else` branch is the whole of what an older app needs. An unrecognised card arrives as
`CnctMessageType.OTHER`, with its name in `rawType` and its fields in `data`.

Times are `Instant`s: format them on the customer's clock (`ZoneId.systemDefault()`), not the
business's.

### Three things it does for you

**A reconnect reloads.** The platform's fan-out replays nothing after a drop — deliberately — so this
client refetches the whole transcript every time it reconnects rather than splicing a gap it cannot
measure. Rendering `state.messages` is always rendering the truth. The visitor's own bubbles that the
server does not have yet — in flight, or failed — survive the reload.

**A send is idempotent.** Every message carries a `clientKey`, unique per conversation on the server,
so a frame replayed across a reconnect returns the row it already wrote instead of writing a second
one. That is what makes `send()` safe to reject on a timeout: a failed bubble stays on screen, marked
`failed`, and `retry(clientKey)` cannot double-post.

**It degrades rather than breaks.** The server announces what it accepts on the handshake. Sends go
over the socket where that is offered and fall back to `POST /api/chat/public/messages` where it is
not — which covers both an older server and the ordinary case of the socket being between
connections. Nothing in your code changes either way.

### Threads, scopes and the lifecycle

Every method is safe to call from any thread. The client's own work runs one piece at a time on the
dispatcher of the `scope` you give it — `viewModelScope` runs it on the main thread, which is where a
UI wants its state — and HTTP and the socket run on OkHttp's threads. When that scope is cancelled the
client closes its socket; `close()` does the same by hand.

A socket held open in the background costs battery and is killed by the system anyway. Call
`disconnect()` in `onStop` and `connect()` in `onStart`; the reconnect reloads.

### Keeping the visitor

A session token is a thirty-day secret scoped to one conversation. Held, somebody who chatted last
week comes back to their own thread; lost, they arrive as a stranger. The core's default store is
memory. **`CnctKeystoreTokenStore`**, in `cnct-android`, keeps it across launches:

- the ciphertext is in `SharedPreferences`; the key is AES-256-GCM, generated in the Android Keystore,
  and never leaves it — a copy of the app's files holds nothing that opens it;
- each token is bound to the entry it was written under;
- anything that will not decrypt — a backup restored onto a new phone, a value somebody edited —
  reads as no session and is removed, and the visitor starts afresh;
- **below API 23 there is no Keystore AES key**, so there the token is kept in memory only.

It does not use `androidx.security:security-crypto`, which is deprecated. Any other storage is three
functions — implement `CnctTokenStore`.

### Identity

A visitor is anonymous until they volunteer a phone number. If your app has already signed this
person in, **pass the number you hold**: it is the difference between a thread that joins their
history and one that starts a second record for them.

```kotlin
chat.start(displayName = user.name, phone = user.phone)
```

There is no signed identity handshake yet, so this is the honest weak spot of the channel, and
`start(phone = …)` is the way around it today.

### Files

`downloadAttachment(id)` answers the bytes of a file an operator sent. For an image loader,
`attachmentRequest(id)` answers the URL and the headers it needs — the route is authenticated by the
session header, which a plain image URL cannot carry. Hand those headers to the loader, never to a
log; `toString()` prints their names only.

## Bookings and tickets

Server-side, on the API key — a sandbox one (`kaer_sk_test_…`) while you build.

```kotlin
val agent = Cnct().agent(CnctApiKey(System.getenv("CNCT_API_KEY")))

val day = agent.bookings.checkAvailability(date = "2026-09-15", partySize = 4)
if (day.isEmpty) {
    println(day.note) // "No table seats six that day." — an answer, with the reason
} else {
    val booking = agent.bookings.create(
        startsAt = day.free.first().startsAt,   // exactly as given
        customerPhone = "+97312345678",
        name = "Layla",
        nameIsTheCaller = true,
    )
    println(booking.whenText)                    // "Tuesday 15 September at 19:30"
}
```

Three things are worth knowing before you build against it.

**Pass `startsAt` back verbatim.** It is the token a slot is matched on. Rebuilding it from a parsed
`Instant` is how a booking lands on a time that was never offered — `startsAtUtc` is for sorting and
display, never for sending.

**The phone number is the authorization boundary.** There is no ambient identity out here, so the
number is what decides whose booking may be read and changed, and the platform resolves it itself.

**`nameIsTheCaller` is small and load-bearing.** A booking's name and a customer's name are different
facts about different people: somebody booking a haircut for their daughter gives their daughter's
name, and filing that against the number renames the contact permanently. Leave it false unless the
name given is the number's own owner.

`bookings` also has `services()`, `people()`, `forCustomer(phone)`, `reschedule(…)` and `cancel(…)`.

Tickets follow the platform's own sequence — list the types, read what one needs, raise it:

```kotlin
val types = agent.tickets.types()
val type = agent.tickets.type(types.items.first().ticketTypeId)
// `askFor` is already only the questions worth asking: what the platform knows is subtracted.
val ticket = agent.tickets.create(
    ticketTypeId = type.ticketTypeId,
    title = "Leaking tap",
    reasonUnresolved = "Needs a plumber",
    customerPhone = "+97312345678",
    fields = type.askFor.associate { it.key to answers[it.key] },
    idempotencyKey = attemptId, // retry with the same value, get the same ticket
)
```

### Following a ticket up

```kotlin
// "Any news on my refund?"
val open = agent.tickets.forCustomer(phone, q = "refund")
val ticket = agent.tickets.get(phone, open.items.first().ticketNumber)
ticket.status             // "WAITING"
ticket.waitingOnCustomer  // true — the business is waiting on an answer from them
ticket.history.forEach { println("${it.on}  ${it.what}") }

// "The order number is 881"
val added = agent.tickets.addTo(customerPhone = phone, ticketNumber = ticket.ticketNumber, note = "The order number is 881")
added.resumed // true — it was waiting on them, and is being worked on again
```

**`get` is safe to show the customer it belongs to** — no person's name on any line, no team, and
none of the notes staff write for each other. Somebody else's ticket number is refused exactly as a
number that does not exist is.

**`addTo` instead of a second ticket.** A follow-up belongs on the ticket it is about. A resolved,
closed or cancelled one refuses with `tool_refused`, and the refusal says to raise a new one. The same
words twice within ten minutes are kept once, so a retry is safe. Both need a CNCT host from
2026-09-29 or later — `app.doo.ooo` is one.

`forCustomer` also takes `ticketNumber =` and `includeResolved = true`.

### Worth knowing

**An empty list is often an instruction rather than an absence.** `bookings.people()` coming back
empty with a note means the business assigns whoever is free and you must not offer a choice of
person. Read `note`.

**A tool that declines answers `200` with a sentence.** The typed methods raise those as
`CnctException` with `CnctErrorCode.TOOL_REFUSED`; `agent.call(name, args)` hands the raw `JsonObject`
back instead, and reaches any tool the platform adds after this SDK was published.

**The same tools, for an AI agent.** The same key opens the same tools over MCP, at
`https://app.doo.ooo/api/mcp` with the key as a bearer token.

## Errors

Everything fails with `CnctException`. Branch on `code` — it is the server's own code where there is
one, and the same string the web, Flutter and iOS SDKs use.

```kotlin
try {
    chat.send(text)
} catch (error: CnctException) {
    when {
        error.isUnauthenticated -> signInAgain()
        error.isRetryable -> retryLater()                              // offline, timeout, rate_limited, server_error
        error.code == CnctErrorCode.CONVERSATION_CLOSED -> startAgain()
        else -> show(error.message)
    }
}
```

`details` carries the server's whole error body — what a tool answered, the fields that failed
validation.

## Limits

Thirty messages a minute per visitor, five session starts a minute per IP, four thousand characters a
message. The socket counts the same thirty the HTTP path does, so moving between them buys nothing.

## The sample app

[`sample`](sample) is a Compose chat screen on the headless client: header and connection status,
bubbles with pending and failed states and a retry, ticket and booking cards, typing, a pre-chat step
for an inbox that wants a phone number, the Keystore token store, and a socket that sleeps in the
background. Put your inbox's public key in `local.properties` (git ignores it) and run it:

```properties
cnct.publicKey=your-inbox-public-key
# Optional; without it, https://app.doo.ooo. From the emulator, your machine is 10.0.2.2.
cnct.baseUrl=http://10.0.2.2:3001
```

```sh
./gradlew :sample:installDebug
```

## Development

```sh
./gradlew build      # compile (warnings are errors), test, jars and AARs, lint
./gradlew :cnct:build  # the core alone — no Android SDK needed
```

The core's tests run against fakes — an in-process HTTP answerer and a fake socket — on `runTest`'s
virtual clock, so reconnect backoff, send timeouts and keep-alives are tested without waiting for
them. The token store's tests run on Robolectric. CI runs `./gradlew build publishToMavenLocal` on
every pull request with JDK 17 and the Android SDK. A tag `vX.Y.Z` must match `version` in
`gradle.properties`, and a test keeps `CnctConfig.SDK_VERSION` equal to it.

## Licence

BSD 3-Clause. See [LICENSE](LICENSE).
