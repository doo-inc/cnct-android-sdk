# CNCT for Android

The official CNCT SDK for Android and Kotlin. Live chat, bookings and tickets against a CNCT
workspace, using the credentials CNCT issues you.

> **Status: in progress, not released.** The foundation is here and tested — the host, the two
> credentials, the error vocabulary and the HTTP transport. The chat and agent clients are being
> ported from the [Flutter SDK](https://github.com/doo-inc/cnct-flutter-sdk) next. There is no tag
> yet; do not depend on `main`.

**It is headless, and that is the whole point.** There is no view in this library and no web view.
A business building their own app has a design system already; shipping them a chat bubble to
restyle answers a question they did not ask. So this ships state and events, and the interface is
yours.

**The core is plain Kotlin on the JVM.** The same artifact runs in an Android app and on a server —
which matters, because an API key belongs on a server. Android-only pieces (a Keystore-backed token
store, the sample app) sit in their own modules beside it.

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
    implementation("com.github.doo-inc.cnct-android-sdk:cnct:v0.1.0")
}
```

Pin a tag: what a client ships is what their users run, and an SDK that moves under a released app
is a bug report nobody can reproduce. Needs the `INTERNET` permission. Pulls in OkHttp,
kotlinx.coroutines and kotlinx.serialization — most apps have all three already.

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

## Two credentials, two doors

| Credential                   | Opens                   | Where it belongs |
| ---------------------------- | ----------------------- | ---------------- |
| `CnctChatPublicKey("…")`     | Live chat, as a visitor | **Inside your app.** It identifies an inbox, not a person, and it is already public — it is in the URL of the hosted chat page. |
| `CnctApiKey("kaer_sk_…")`    | Bookings and tickets    | **On a server you control.** It is account-wide. Mint one in the CNCT console under **Settings → Developers**. |

```kotlin
Cnct.modulesFor(CnctChatPublicKey("inbox-key"))   // [CHAT]
Cnct.modulesFor(CnctApiKey(key))                  // [BOOKINGS, TICKETS]
```

`toString()` on a credential never contains the secret — only the last four characters.

## Sandbox and production

An API key comes in two kinds, and the key says which: `kaer_sk_test_…` reads the real account and
writes nothing real; `kaer_sk_live_…` is real. Same host, same tools, same validation, same refusals
— going live is swapping the key.

```kotlin
val key = CnctApiKey(System.getenv("CNCT_API_KEY"))
key.mode       // SANDBOX | PRODUCTION
key.isSandbox  // true for kaer_sk_test_…
```

## Errors

Everything fails with `CnctException`. Branch on `code` — it is the server's own code where there is
one, and the same string the web, Flutter and iOS SDKs use.

```kotlin
try {
    // …
} catch (error: CnctException) {
    when {
        error.isRetryable -> retryLater()                              // offline, timeout, rate_limited, server_error
        error.code == CnctErrorCode.CONVERSATION_CLOSED -> startAgain()
        else -> show(error.message)
    }
}
```

## Development

```sh
./gradlew build      # compile (warnings are errors), test, jar
```

CI runs it on every pull request with JDK 17. A tag `vX.Y.Z` must match `version` in
`gradle.properties`, and a test keeps `CnctConfig.SDK_VERSION` equal to it.

## Licence

BSD 3-Clause. See [LICENSE](LICENSE).
