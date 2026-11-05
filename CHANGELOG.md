# Changelog

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
