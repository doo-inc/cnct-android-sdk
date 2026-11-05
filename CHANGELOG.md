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
