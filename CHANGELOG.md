# Changelog

## Unreleased

The rest of the visitor API, as CNCT's hosted chat page uses it (`docs/chat-ui/SPEC.md`, Part 1).

- **The inbox** (`boot()`) carries `widget` — the business's brand (`color`, `appearance`, `avatarUrl`,
  `language`, `showBranding`) and chat settings (`allowAttachments`, `allowEndConversation`, `start`,
  `askForRating`) — plus `team` (first names and faces) and `availability` (in or out, when the team
  is back, whether the assistant answers, how fast replies come). Missing sections read as the
  hosted widget's defaults.
- **The full message**: `sender` (`CnctChatSender`: a person's first name and face, or the
  assistant), `flow` (`CnctChatFlow`: words, `buttons`/`list` choices, footer, media, the kind of
  answer it `expects`), `suggestions`, `replyTo` (a quoted earlier message) and `attachments`.
  `CnctChatState` gains `awaiting` (what a flow is waiting for), `suggestions`, `seen` and `isLive`.
- **Files both ways.** `uploadAttachment(bytes | stream, filename, mimeType, onProgress)` sends one
  file as `multipart/form-data` (part `file`) with real per-file progress, after checking it against
  `CnctAttachmentRules` (10 files, 50 MB, the accepted types; HEIC/HEIF must be converted to JPEG). The
  server's refusals keep its sentence and map to the usual codes (`409` is `conversation_closed`,
  `422` is `invalid`); none clears the session. Uploads and downloads have no whole-call deadline.
- `send(body, attachmentIds, replyId, clientKey)`: words, files or both, and the flow choice a visitor
  tapped (`choose(choice)` does it for you). Over the socket and over HTTP alike. `retry` resends the
  same files and choice under the same `clientKey`.
- **Signed-in users**: `identify(CnctChatIdentity(userId, userHash, …))` with the current token, so an
  anonymous chat moves across; the answered token replaces the stored one. A refusal leaves the
  anonymous session as it was. `CnctChatIdentity` never prints its hash.
- `conversations()` and `conversation(id)`; `read()` (once per team reply); `rate(score, comment)`;
  `updateProfile(displayName, phone)`.
- Socket frames `chat.message.updated` (replaced in place, `CnctChatEvent.MessageUpdated`) and
  `chat.read` (`seenAt`, `CnctChatEvent.Read`). `chat.conversation.updated` also takes the flat
  payload the hosted widget reads (`status`, `withPerson`, `assignee`) and patches rather than resets.
  When a conversation closes, the client asks the server whether it can be rated.
- The session start and resume send the device's IANA `timezone`.

## Unreleased — 0.1.0

- The foundation, matching the Flutter and web SDKs at 0.2.0: `Cnct`, `CnctConfig` (production
  `https://app.doo.ooo` by default, path prefixes kept, the socket origin derived), `CnctHosts`,
  `CnctChatPublicKey`, `CnctApiKey` with `CnctKeyMode` (sandbox / production), `CnctModule`,
  `CnctException` with the shared `CnctErrorCode` vocabulary, `CnctTokenStore` with a memory store,
  and `CnctTransport` on OkHttp.
- Plain Kotlin on the JVM (Java 11 bytecode), `explicitApi()`, warnings as errors.
- Credentials redact themselves in `toString()`.
- Query values are encoded strictly, so a `+` in a phone number is not read as a space.
- A response that is not JSON — an HTML login page, a proxy's plain-text error — is
  `bad_response`, even though kotlinx.serialization's tree reader would accept an unquoted word.
- **Bookings and tickets** on a `kaer_sk_` API key: `CnctAgentClient` (or `Cnct().agent(key)`) with
  `catalogue()`, `bookings` (`services`, `people`, `checkAvailability`, `create`, `forCustomer`,
  `reschedule`, `cancel`) and `tickets` (`types`, `type`, `create`, `forCustomer`, `get`, `addTo`),
  plus `call()` / `callOrThrow()` onto any tool the platform adds later. Calls `/api/tools`. A tool
  that declines with `200 {error}` is `tool_refused`; the raw `call()` hands it back instead. Models
  are `@Serializable`, ignore fields they do not know, and carry `sandbox` where the platform does.
- A response that parses but is the wrong shape is `bad_response` rather than a serialization
  exception.
- **Live chat** on an inbox public key: `CnctChatClient` (or `Cnct().chat(key, tokenStore, storageKey,
  scope)`) with `boot`, `start`, `resume`, `send`, `retry`, `typing`, `end`, `downloadAttachment`,
  `attachmentRequest`, `connect`, `disconnect`, `close`; `state` is a `StateFlow<CnctChatState>`,
  `events` a `SharedFlow<CnctChatEvent>`. A reconnect reloads the transcript; every send carries a
  `clientKey`; sends go over the socket when the handshake accepts them and over HTTP otherwise; the
  session token rides on `x-chat-token`; a refused token is cleared. Ticket and booking cards are
  typed, and an unrecognised card degrades to its own body.
- The socket is OkHttp's WebSocket behind `CnctSocketFactory`, so tests and apps can bring their own.
  The client's work runs one piece at a time on the dispatcher of the `CoroutineScope` it is given,
  and cancelling that scope closes it.
- `Cnct().clientFor(credentials)` answers a sealed `CnctClient`.
- **`cnct-android`**, a second artifact: `CnctKeystoreTokenStore` keeps the chat session token in
  `SharedPreferences`, encrypted with an AES-256-GCM key generated in the Android Keystore and bound
  to the entry it was written under. Anything that will not decrypt reads as no session. Below API 23,
  where the Keystore has no AES keys, it keeps the token in memory. Not built on the deprecated
  `androidx.security:security-crypto`.
- **`sample`**, a Compose chat screen on the headless client, configured from `local.properties`.
  Not published.
- README sections for chat, bookings and tickets. Timestamps are `java.time.Instant`, so an app below
  API 26 needs core library desugaring.
