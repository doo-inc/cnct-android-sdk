# cnct-android-sdk — working notes

The native Android SDK for CNCT. Gradle (Kotlin DSL, version catalog), module `:cnct` = plain
Kotlin/JVM, package `ooo.doo.cnct`, Java 11 bytecode, `explicitApi()`, warnings as errors. OkHttp
4.12, kotlinx.coroutines, kotlinx.serialization. Tests: kotlin.test on JUnit 5, MockWebServer.

## What it is

A **headless** port of the Flutter SDK. Same concepts, same names with a `Cnct` prefix, same error
codes verbatim. No UI in the library — a sample app shows how to build one.

**The reference implementation is `doo-inc/cnct-flutter-sdk` (public).** Clone it and port from it
behaviour for behaviour, test for test:

```sh
git clone https://github.com/doo-inc/cnct-flutter-sdk.git ../cnct-flutter-sdk
```

`doo-inc/cnct-web-sdk` (public) is the second reference, for anything the Dart is unclear about.
The iOS twin is `doo-inc/cnct-ios-sdk`; keep the two public surfaces parallel.

## What is here (tested)

`CnctConfig` / `CnctHosts`, `CnctChatPublicKey` / `CnctApiKey` / `CnctKeyMode`, `Cnct` /
`CnctModule`, `CnctException` / `CnctErrorCode`, `CnctTokenStore` + memory store, `CnctTransport`
(OkHttp, the Flutter transport's error mapping, `JsonElement` in and out). Build on these — do not
add a second HTTP path or a second exception type.

## What is left to build

1. **Chat client** — port `lib/src/chat/` (`chat_client.dart`, `chat_models.dart`,
   `chat_events.dart`, `chat_state.dart`) and `test/chat_client_test.dart`.
   - `boot`, `start`, `resume`, `send`, `retry`, `typing`, `end`, `downloadAttachment`, `connect`,
     `disconnect`, `close`; `state` as a `StateFlow`, `events` as a `SharedFlow`, `hasSession`.
   - Models as `@Serializable` classes; unknown fields ignored (`Json { ignoreUnknownKeys = true }`).
   - Socket: OkHttp `WebSocket` at `socketBaseUrl` + `/ws/chat`, behind an interface (the Flutter
     `CnctSocketFactory`) so tests drive a fake socket without a server. A `CoroutineScope` is
     injected so tests can use `runTest`'s scheduler.
   - Keep the load-bearing behaviours: a reconnect **reloads the transcript**; every send carries a
     `clientKey` (idempotent); sends go over the socket when the handshake `accepts` `send`, else
     HTTP; the visitor token rides on `x-chat-token`, never `Authorization`; an unauthenticated
     answer clears the stored token.
2. **Agent client** — port `lib/src/agent/` and `test/agent_client_test.dart`: `catalogue()`,
   `bookings` (`services`, `people`, `checkAvailability`, `create`, `forCustomer`, `reschedule`,
   `cancel`), `tickets` (`types`, `type`, `create`, `forCustomer`, `get`, `addTo`). Calls
   `/api/tools`. A 200 with an `error` key is `CnctErrorCode.TOOL_REFUSED`. Models carry `sandbox`.
3. **Entry points** — `Cnct.chat(key, tokenStore, storageKey)`, `Cnct.agent(key)`,
   `Cnct.clientFor(credentials)`.
4. **`:cnct-android`** — an Android library module (AGP, `minSdk 21`, `compileSdk 35`) holding what
   needs the Android SDK: a token store that encrypts with an Android Keystore key and keeps the
   ciphertext in `SharedPreferences` (do **not** use `androidx.security:security-crypto` — it is
   deprecated). Robolectric for its tests. Publish it as a second artifact.
5. **`:sample`** — an Android app module, Jetpack Compose, a complete chat screen on the headless
   client in a few hundred lines, public key from `local.properties` / a build config field. Not
   published.
6. **Docs** — README sections for chat, bookings, tickets (mirror the Flutter README), CHANGELOG.

CI is `./gradlew build publishToMavenLocal` on ubuntu with JDK 17; it has the Android SDK, so the new
modules build there. If your environment has no Android SDK, build `:cnct` locally and read CI for
the rest — do not claim a pass you did not see.

Newer visitor features on the server (past conversations, ratings, identity verification, visitor
uploads, "Seen") are **not** in the Flutter SDK yet. Parity with Flutter 0.2.0 first; list those as
follow-ups in the PR rather than inventing their wire format.

## Rules

- KDoc says **why**, in full sentences, in the voice of the Flutter SDK's doc comments. Port them,
  adapted to Kotlin; do not drop them.
- Never print a secret. Credentials redact themselves in `toString()` and are not data classes;
  keep it that way in every new type that holds one (session tokens included).
- Error codes are the server's strings. Never rename one.
- Bump `version` in `gradle.properties`, `CnctConfig.SDK_VERSION` and the CHANGELOG together (a test
  checks the first two agree). Do not tag — releases are Isa's call.
- Open a pull request into `main`; do not merge it.
