# CNCT for Android

The official CNCT SDK for Android and Kotlin: a ready-made chat screen a business can brand, and
under it a headless client for live chat, bookings and tickets against a CNCT workspace.

> **Status: 1.0.0, not yet tagged.** Built from the same spec as the web and iOS SDKs
> ([`docs/chat-ui/SPEC.md`](docs/chat-ui/SPEC.md)), which ship 1.0.0 together. Until the `v1.0.0`
> tag exists, do not depend on `main`.

Three artifacts, so an app takes only what it uses:

| Artifact       | What it is                                                                                 | Needs              |
| -------------- | ------------------------------------------------------------------------------------------ | ------------------ |
| `cnct-ui`      | **The chat screen**, in Jetpack Compose: brandable, English and Arabic, files both ways    | Android, Compose   |
| `cnct-android` | The token store that keeps a visitor's session, encrypted with an Android Keystore key     | Android            |
| `cnct`         | The headless client — chat, bookings, tickets — in plain Kotlin on the JVM                 | Nothing but a JVM  |

`cnct-ui` brings the other two with it. The headless core is plain Kotlin on purpose: the same
artifact runs in an Android app and on a server, which matters, because an API key belongs on a
server. Nothing with a UI dependency is in it.

Sister SDKs, same concepts, same option names, same words and the same error codes:
[web](https://github.com/doo-inc/cnct-web-sdk) ·
[iOS](https://github.com/doo-inc/cnct-ios-sdk) ·
[Flutter](https://github.com/doo-inc/cnct-flutter-sdk).

## Install

Through [JitPack](https://jitpack.io/#doo-inc/cnct-android-sdk) until there is a Maven Central
release. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and in the app module, one of:

```kotlin
dependencies {
    // The chat screen. Brings cnct-android and cnct with it.
    implementation("com.github.doo-inc.cnct-android-sdk:cnct-ui:v1.0.0")

    // Or headless: the client and the Keystore token store, and your own interface.
    implementation("com.github.doo-inc.cnct-android-sdk:cnct-android:v1.0.0")

    // Or on a server, or anywhere else on the JVM: the core alone.
    implementation("com.github.doo-inc.cnct-android-sdk:cnct:v1.0.0")
}
```

Pin a tag: what a client ships is what their users run, and an SDK that moves under a released app
is a bug report nobody can reproduce. The app declares the `INTERNET` permission; the chat screen
asks for nothing else — the photo picker, the camera and the document picker are the system's own.

**Below API 26, enable core library desugaring.** Timestamps are `java.time.Instant`, which Android
has from 8.0; an app with a lower `minSdk` (the SDK's own is 21) needs this in its module:

```kotlin
android {
    compileOptions { isCoreLibraryDesugaringEnabled = true }
}
dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5") }
```

**Publishing an App Bundle?** Play installs only the device's own languages unless you say
otherwise, so an English phone would not have the chat's Arabic. If you let the chat speak Arabic
on any phone — `language = CnctLanguage.AR`, or an inbox set to Arabic — keep every language:

```kotlin
android {
    bundle { language { enableSplit = false } }
}
```

## Chat UI in 10 lines

```kotlin
class SupportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CnctChat(publicKey = "the-inbox-public-key", onClose = ::finish)
        }
    }
}
```

That is a complete chat: it asks the visitor's name (or lets them type first, as the inbox is set
up), keeps their session in the Android Keystore so they come back to their own conversation,
sends and receives photos and files, speaks English or Arabic, and brands itself from the inbox's
settings in the CNCT console. It pads itself for the system bars and the keyboard and keeps its
state across a rotation.

**No Compose?** The same screen as an activity:

```kotlin
startActivity(CnctChatActivity.intent(this, publicKey = "the-inbox-public-key"))
```

### Options

`CnctChat` takes the same options as the web's `<cnct-chat>` and the iOS `CnctChatView`:

| Option      | What it is                                                                                         | Default |
| ----------- | -------------------------------------------------------------------------------------------------- | ------- |
| `client` / `publicKey` | A `CnctChatClient` you made, or an inbox's public key (the screen then makes the client) | required |
| `baseUrl`   | Where CNCT is, with `publicKey`                                                                    | `https://app.doo.ooo` |
| `theme`     | A `CnctChatTheme` — see [Theming](#theming)                                                        | the console's, then built in |
| `language`  | `EN`, `AR`, or `AUTO` (the device's first of the two)                                              | the console's, then the device |
| `strings`   | `CnctChatStrings(en = …, ar = …)`: your own words for any key of the [shared table](docs/chat-ui/strings.json) | none |
| `identity`  | A signed-in user — see [Signed-in users](#signed-in-users)                                         | anonymous |
| `onClose`   | The header's close button. Without it there is none                                                | none |
| `onEvent`   | `CnctChatUIEvent`s for analytics: `Opened`, `Started`, `Sent`, `AttachmentSent(count)`, `Ended`, `Rated(score)` — never what anybody wrote | none |

Passing your own client keeps the headless API in your hands while the screen draws it — make it on
a `viewModelScope` with the Keystore store, as in [Headless chat](#headless-chat). The screen never
closes a client it did not make.

## Theming

**What the app passes wins over the inbox's widget settings in the CNCT console, which win over the
built-in defaults.** So a business can brand the chat from the console with no code at all, and an
app can pin anything it wants in code. Leave a token out to let the console decide it.

```kotlin
CnctChat(
    publicKey = "the-inbox-public-key",
    theme = CnctChatTheme(
        colors = CnctChatColors(primary = Color(0xFFD7262E)),  // both appearances
        dark = CnctChatColors(background = Color.Black),       // one of them
        cornerRadius = 8.dp,
        font = FontFamily(Font(R.font.brand_regular), Font(R.font.brand_bold, FontWeight.SemiBold)),
        logo = painterResource(R.drawable.logo),
        appearance = CnctAppearance.SYSTEM,
    ),
)
```

The tokens have the same names in every CNCT SDK:

| Token             | Meaning                                         | Default |
| ----------------- | ----------------------------------------------- | ------- |
| `primary`         | Brand colour: send button, links, header accent | the console's `brand.color`, else `#b52d93` |
| `onPrimary`       | Text and icons on `primary`                     | black or white, whichever reads (the server's contrast rule) |
| `background`      | The screen                                      | light `#ffffff` / dark `#0f1115` |
| `surface`         | Header, composer, cards                         | light `#f6f7f9` / dark `#171a21` |
| `text`            | Main text                                       | light `#111418` / dark `#eef0f3` |
| `textMuted`       | Timestamps, hints                               | light `#5b6573` / dark `#9aa3af` |
| `border`          | Dividers, outlines                              | light `#e3e6ea` / dark `#2a2f38` |
| `visitorBubble` / `onVisitorBubble` | The customer's own messages   | `primary` / `onPrimary` |
| `agentBubble` / `onAgentBubble`     | The team's and the assistant's | `surface` / `text` |
| `cornerRadius`    | Bubbles and cards                               | 18 dp |
| `font`            | A Compose `FontFamily`                          | the system's |
| `logo`            | A `Painter` for the header                      | the console's `avatarUrl`, else the business's first letter on `primary` |
| `title`           | The header title                                | the business's name |
| `appearance`      | `LIGHT`, `DARK` or `SYSTEM`                     | the console's (`auto` there is `SYSTEM`) |
| `showPoweredBy`   | The "Powered by CNCT" line                      | the console's `showBranding` |

The colour tokens live in `CnctChatColors`: `colors` applies to both appearances and `light` and
`dark` to one, winning over `colors`. Light and dark each keep their own defaults, so setting only
`primary` leaves both readable. **The chat never uses or changes your `MaterialTheme`**: it draws
with these tokens alone and looks the same in a Material 2, Material 3 or no-Material app.

## Arabic and right-to-left

Every word of the interface is in English and Arabic — the same table, word for word, as the web
and iOS SDKs ([`docs/chat-ui/strings.json`](docs/chat-ui/strings.json), shipped as
`values/strings.xml` and `values-ar/strings.xml`).

- **Choosing.** `language = AUTO` takes the first of the device's preferred languages that is
  Arabic or English, else English. Leave it out and the console's setting decides.
- **Mirroring.** Arabic mirrors the whole screen — header, composer, which side bubbles sit on (the
  visitor's own are on the trailing side in both directions), the send arrow — through Compose's
  layout direction, not swapped margins. The app's manifest needs `android:supportsRtl="true"`.
- **Each message takes its own direction** from its first strong letter, whatever the interface's
  language: an Arabic message in an English chat reads right to left, and the reverse.
- **Numbers stay whole.** Phone numbers, emails, web addresses and file sizes are held left to
  right inside Arabic text, so "+973 3311 2233" never turns into "2233 3311 973+".
- **Latin digits** in Arabic, as Gulf apps write them; times and dates in the chosen language.
- The business's own words — the greeting, a flow's questions — are shown as written.

Your own words for any key, per language, with `{name}` placeholders as in the table. The attach
menu's three words, which only the native SDKs need, are `attachPhotos`, `attachCamera` and
`attachFiles`:

```kotlin
CnctChat(
    publicKey = "the-inbox-public-key",
    strings = CnctChatStrings(
        en = mapOf("writeMessage" to "Ask us anything…"),
        ar = mapOf("writeMessage" to "اسألنا عن أي شيء…"),
    ),
)
```

## Attachments

Customers send photos and files; the team's come back in the thread.

- **Picking**: the photo picker (several at once), the camera on phones that have one, and the
  document picker. No storage or camera permission is asked for.
- **Before anything is sent**: up to **10** files a message, **50 MB** each, and only the types the
  platform keeps — images `jpeg`, `png`, `webp`, `gif`; audio `aac`, `mpeg`, `ogg`, `mp4`, `amr`;
  video `mp4`, `3gpp`, `quicktime`; `pdf`, `doc`, `docx`, `xlsx`, `txt`, `csv`. Anything else is
  refused with a sentence before a byte is uploaded.
- **Photos are made fit to send**: HEIC and HEIF become JPEG (the platform does not keep HEIC), and
  anything over 2560 pixels on its long side is brought down to it. Never up; other files untouched.
- **Each file goes up at once**, with its own progress ring in the tray above the composer; send
  waits for them.
- **Received photos** show inline and open full screen (pinch to zoom); **other files** are cards
  that download with the visitor's session and open in the phone's own viewer, or the share sheet.

Headless, the same is two calls — upload each file, then send their ids:

```kotlin
val photo = chat.upload(bytes, filename = "receipt.jpg", mimeType = "image/jpeg") { progress ->
    // 0.0 – 1.0, from OkHttp's thread, as the bytes leave
}
chat.send("Here is the receipt", attachmentIds = listOf(photo.id))
```

`upload` checks the file with `CnctAttachmentRules` first and takes bytes or a stream; converting
HEIC is yours to do headless (`cnct-ui` does it for you). `downloadAttachment(id)` answers a file's
bytes, from any of this visitor's conversations.

A refused file keeps the server's sentence for a person, and its code is the same on every CNCT SDK:

| Code                   | When |
| ---------------------- | ---- |
| `too_many`             | The eleventh file of a message — said before anything is uploaded |
| `too_large`            | Over 50 MB, before upload or answered `413` |
| `unsupported_type`     | A type the chat does not take, HEIC included — before upload, or `415`/`422` |
| `attachments_disabled` | The inbox takes no files (`403`), or the deployment keeps none (`503`) |
| `conversation_closed`  | The conversation ended while the file was on its way (`409`) |

None of them touches the session: a `403` here is not `unauthenticated`.

## Signed-in users

If your app has its own sign-in, tell the chat who the visitor is, and their conversations follow
them between devices and the team sees who they are talking to:

```kotlin
CnctChat(
    publicKey = "the-inbox-public-key",
    identity = CnctChatIdentity(
        userId = user.id,
        userHash = user.cnctHash,   // from YOUR server — see below
        name = user.name,
        email = user.email,
    ),
)
```

`userHash` is `HMAC-SHA256(userId, the inbox's identity secret)` in lowercase hex, **computed on your
own server** and handed to the app with the user's session. In Node:

```js
import { createHmac } from 'node:crypto';

// The inbox's identity secret, from the CNCT console. Server-side only.
const secret = process.env.CNCT_IDENTITY_SECRET;

app.get('/me', requireLogin, (req, res) => {
  const userHash = createHmac('sha256', secret).update(req.user.id).digest('hex');
  res.json({ id: req.user.id, name: req.user.name, email: req.user.email, cnctHash: userHash });
});
```

> **Never put the identity secret in an app.** Anybody with the APK has everything in it, and the
> secret signs in as *any* of your users. The app only ever holds the hash for the user who is
> signed in.

The identity is applied before anything else is read, so the conversations shown are the user's.
An anonymous chat started before they signed in moves across. If it is refused — identity is not set
up for the inbox (`409`), or the hash does not verify (`401`) — it is logged for you and the
customer still gets a working, anonymous chat. Headless, it is `chat.identify(identity)`.

## Previews, tests and a demo with no key

`CnctChatFixture` is the conversation every CNCT SDK renders for its screenshots — a greeting, a
question, the assistant's answer with buttons, a photo, an invoice, a failed message and two files
on their way up — on an in-process fake of the platform. No key, no network, and the real screen:

```kotlin
CnctChatFixture.Chat(language = CnctLanguage.AR, theme = myTheme)
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

## Headless chat

**Your own interface, or none.** The chat screen above is built only on this client's public API, so
anything it does, an app can do itself: the client ships state and events, and the interface is
yours. It is also what a server, a bot or a test talks to — it is plain Kotlin on the JVM.

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

A visitor is anonymous until they say who they are. If your app signs people in, use a signed
identity — `chat.identify(CnctChatIdentity(userId, userHash, …))`, see
[Signed-in users](#signed-in-users) — before `resume()`. Short of that, pass the phone number you
hold to `start(phone = …)`: it is the difference between a thread that joins their history and one
that starts a second record for them.

### Files

`upload(…)` sends a file up and `send(attachmentIds = …)` sends it — see [Attachments](#attachments).
`downloadAttachment(id)` answers a file's bytes. For an image loader, `attachmentRequest(id)` answers
the URL and the headers it needs — the route is authenticated by the session header, which a plain
image URL cannot carry. Hand those headers to the loader, never to a log; `toString()` prints their
names only.

### The rest of the visitor API

| Call | What it does |
| ---- | ------------ |
| `send(body, attachmentIds, replyId)` | Words, files, or both. `replyId` is the flow choice tapped |
| `choose(choice)` | Tap a flow choice: its title as the words, its id as `replyId` |
| `upload(…)` | One file up, with progress |
| `identify(identity)` | Sign the visitor in as your user |
| `updateProfile(displayName, phone)` | Save the name a visitor who typed first gives |
| `conversations()` / `conversation(id)` | Their conversations, newest first; one, with its messages |
| `read()` | The team's reply is on screen: report it, once per reply |
| `rate(score, comment)` | 1–5 on a closed conversation, then a comment as a second answer |

`state` also says what a flow is waiting for (`awaiting`: its choices, or the kind of answer to
type), the assistant's `suggestions`, and whether the visitor's last words were `seen`. `events`
adds `MessageUpdated` (a message edited, or a file finished storing — already replaced in place)
and `Read` (the team read up to a time).

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
message, ten files a message and 50 MB a file. The socket counts the same thirty the HTTP path does,
so moving between them buys nothing.

## The sample app

[`sample`](sample) is the chat screen with the switches a reviewer wants: the language (device,
English, Arabic), the appearance (system, light, dark), the theme (the console's, or a custom one —
brand red, 8 dp corners, its own font and logo), and a **fixture mode that needs no key**. With a
key it opens the live chat as a composable or as `CnctChatActivity`. Put your inbox's public key in
`local.properties` (git ignores it) for live mode:

```properties
cnct.publicKey=your-inbox-public-key
# Optional; without it, https://app.doo.ooo. From the emulator, your machine is 10.0.2.2.
cnct.baseUrl=http://10.0.2.2:3001
```

```sh
./gradlew :sample:installDebug
```

The custom theme's font is [Tajawal](https://fonts.google.com/specimen/Tajawal), under the SIL Open
Font License ([`sample/TAJAWAL-OFL.txt`](sample/TAJAWAL-OFL.txt)).

## Development

```sh
./gradlew build        # compile (warnings are errors), test, jars and AARs, lint
./gradlew :cnct:build  # the core alone — no Android SDK needed
./gradlew :cnct-ui:generateCnctStrings  # after docs/chat-ui/strings.json changes
./gradlew :cnct-ui:testDebugUnitTest --tests 'ooo.doo.cnct.ui.ChatScreenshotTest' -Proborazzi.test.record=true
                       # the screenshots, into cnct-ui/build/outputs/roborazzi
```

The core's tests run against fakes — an in-process HTTP answerer and a fake socket — on `runTest`'s
virtual clock, so reconnect backoff, send timeouts and keep-alives are tested without waiting for
them; uploads are also tested over a real socket against MockWebServer. The token store's and the
chat UI's tests run on Robolectric. CI runs `./gradlew build publishToMavenLocal` on every pull
request with JDK 17 and the Android SDK, then renders the fixture conversation with Roborazzi and
uploads the PNGs as the artifact **`screenshots`**: `chat-{en,ar}-{light,dark}.png`,
`chat-{en,ar}-custom.png`, and the name form and the ended conversation in both languages. A tag
`vX.Y.Z` must match `version` in `gradle.properties`, and a test keeps `CnctConfig.SDK_VERSION`
equal to it.

## Licence

BSD 3-Clause. See [LICENSE](LICENSE).
