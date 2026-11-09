# cnct-android-sdk — working notes

The native Android SDK for CNCT. Gradle (Kotlin DSL, version catalog), module `:cnct` = plain
Kotlin/JVM, package `ooo.doo.cnct`, Java 11 bytecode, `explicitApi()`, warnings as errors. OkHttp
4.12, kotlinx.coroutines, kotlinx.serialization. Tests: kotlin.test on JUnit 5, MockWebServer; the
Android modules on Robolectric (JUnit 4), screenshots with Roborazzi.

## What it is

Three artifacts: `:cnct`, a headless port of the Flutter SDK (same concepts, same names with a
`Cnct` prefix, same error codes verbatim); `:cnct-android`, the Keystore token store; and `:cnct-ui`,
a ready-made Jetpack Compose chat screen (`ooo.doo.cnct.ui`) built on the headless client. The
Flutter SDK (`doo-inc/cnct-flutter-sdk`) is the reference for the headless agent and chat basics;
`docs/chat-ui/SPEC.md` is the contract for everything 1.0 added.

## What is here (tested)

- **`:cnct`** (plain Kotlin/JVM): `CnctConfig` / `CnctHosts`, credentials (`CnctChatPublicKey`,
  `CnctApiKey`, `CnctKeyMode`, `CnctChatIdentity`), `Cnct` / `CnctModule`, `CnctException` /
  `CnctErrorCode`, `CnctTokenStore`, `CnctTransport` (OkHttp, JSON and multipart), the agent client
  (bookings, tickets), and the chat client with the whole visitor API of `docs/chat-ui/SPEC.md`
  Part 1 — widget/team/availability, the full message (sender, flow, suggestions, replyTo,
  attachments), `upload` with progress and `CnctAttachmentRules`, `send(attachmentIds, replyId)`,
  `choose`, `identify`, `conversations` / `conversation`, `read`, `rate`, `updateProfile`, and the
  `chat.message.updated` / `chat.read` frames.
- **`:cnct-android`**: `CnctKeystoreTokenStore` (Android Keystore AES-GCM, ciphertext in
  `SharedPreferences`, not `security-crypto`). Robolectric tests.
- **`:cnct-ui`**: `CnctChat` / `CnctChatActivity` / `CnctChatTheme` / `CnctChatFixture` — SPEC Part 2.
  Strings are generated from `docs/chat-ui/strings.json` (`./gradlew :cnct-ui:generateCnctStrings`;
  `StringTableTest` fails until you do). Screenshots: `ChatScreenshotTest` with Roborazzi, uploaded by
  CI as `screenshots`.
- **`:sample`**: the chat UI with language / appearance / custom-theme switches and a fixture mode.

Build on these — do not add a second HTTP path or a second exception type, and keep `:cnct` free of
UI dependencies. The UI uses only the headless client's public API: if it needs something more, add
it to the client first.

`docs/chat-ui/SPEC.md` is the contract shared with `doo-inc/cnct-web-sdk` and
`doo-inc/cnct-ios-sdk`: keep option names, theme tokens and words identical across the three. The
reference implementation of the chat is CNCT's hosted widget (`https://app.doo.ooo/chat-assets/chat.js`,
or branch `reference/hosted-widget`): port behaviour, never copy it in, never merge that branch.

## Known follow-ups

Not in the spec yet, so not built: the widget's home screen and conversation list UI (the client
already has `conversations()`), its streaming `chat.draft` frame, the emoji tray (web only), the
team's drawn faces (`avatar`/`mascot` keys refer to the widget's own sprite), and a Maven Central
release.

## Rules

- KDoc says **why**, in full sentences, in the voice of the Flutter SDK's doc comments. Port them,
  adapted to Kotlin; do not drop them.
- Never print a secret. Credentials redact themselves in `toString()` and are not data classes;
  keep it that way in every new type that holds one (session tokens included).
- Error codes are the server's strings. Never rename one.
- Bump `version` in `gradle.properties`, `CnctConfig.SDK_VERSION` and the CHANGELOG together (a test
  checks the first two agree). Do not tag — releases are Isa's call.
- Open a pull request into `main`; do not merge it.
