package ooo.doo.cnct

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The CNCT hosts this SDK knows about by name.
 *
 * **CNCT runs at `app.doo.ooo`, and that is the default.** Anything else — a proxy on your own
 * domain, a CNCT on your laptop — is one `baseUrl` away.
 *
 * Whether what you do is real is decided by the key, not the host: a sandbox key (`kaer_sk_test_…`)
 * talks to the same production host and writes nothing real. See [CnctApiKey].
 */
public object CnctHosts {
    /** Production. What [CnctConfig.baseUrl] is when you leave it out. */
    public const val PRODUCTION: String = "https://app.doo.ooo"
}

/** How loud a log line is. */
public enum class CnctLogLevel { DEBUG, INFO, WARNING, ERROR }

/** Where this SDK says what it is doing. */
public fun interface CnctLogger {
    public fun log(level: CnctLogLevel, message: String, error: Throwable?)
}

/**
 * Where this SDK points and how it behaves on the wire.
 *
 * One object, passed to every client, and the only thing that has to change to move an app between
 * a developer's laptop, a staging host and production. [copy] exists so that swap can happen at
 * runtime — an in-app environment picker, a remote config value, a build flavour — without
 * rebuilding anything else.
 *
 * ```kotlin
 * val config = CnctConfig()                                  // https://app.doo.ooo
 * val local = CnctConfig(baseUrl = "http://10.0.2.2:3001")   // the host machine, from an emulator
 * ```
 *
 * @throws CnctException with [CnctErrorCode.INVALID] for a base URL that is empty or not absolute
 *   http(s). An empty string is never "left out": it is a variable somebody meant to set.
 */
public class CnctConfig(
    baseUrl: String = CnctHosts.PRODUCTION,
    /** How long to wait for an HTTP response before giving up with [CnctErrorCode.TIMEOUT]. */
    public val connectTimeout: Duration = 20.seconds,
    /**
     * How long to wait for a chat message to be acknowledged before rejecting it. Rejecting is safe:
     * every message carries an idempotency key, so a retry cannot double-post.
     */
    public val sendTimeout: Duration = 15.seconds,
    /**
     * Sent on every request. For a proxy that wants its own header, a tracing id, or a tenant
     * selector in front of CNCT. Never put a CNCT credential here — the clients set those
     * themselves, on the header each one actually uses.
     */
    public val headers: Map<String, String> = emptyMap(),
    /**
     * Identifies this SDK in the platform's request logs. Worth overriding with your own app's name
     * and version: when a client asks why their integration is being rate-limited, this is the field
     * that answers it.
     */
    public val userAgent: String = "cnct-android-sdk/$SDK_VERSION",
    /**
     * Where this SDK says what it is doing. Null is silence, which is the default — an SDK that
     * prints to a customer's logcat uninvited is a bug report.
     */
    public val logger: CnctLogger? = null,
) {
    /**
     * The CNCT host, with an optional path prefix. Defaults to [CnctHosts.PRODUCTION].
     *
     * A prefix is honoured rather than stripped: a client fronting CNCT at
     * `https://theirdomain.com/support` is a legitimate arrangement, and every path this SDK builds
     * is appended to whatever is here. Trailing slashes, a query and a fragment are dropped.
     */
    public val baseUrl: HttpUrl = normalise(baseUrl)

    /**
     * The WebSocket origin, derived rather than configured.
     *
     * Two URLs to keep in step is one URL that gets forgotten, and the forgotten one is always the
     * socket — it fails later, in a way that looks like a network problem rather than a
     * misconfiguration. `https` becomes `wss`, `http` becomes `ws`, and the path prefix travels. A
     * string, because OkHttp's [HttpUrl] only holds http(s); OkHttp's WebSocket accepts this as is.
     */
    public val socketBaseUrl: String
        get() = baseUrl.toString().trimEnd('/').replaceFirst(Regex("^http"), "ws")

    /** A copy with some things changed. This is how you swap hosts at runtime. */
    public fun copy(
        baseUrl: String = this.baseUrl.toString(),
        connectTimeout: Duration = this.connectTimeout,
        sendTimeout: Duration = this.sendTimeout,
        headers: Map<String, String> = this.headers,
        userAgent: String = this.userAgent,
        logger: CnctLogger? = this.logger,
    ): CnctConfig = CnctConfig(baseUrl, connectTimeout, sendTimeout, headers, userAgent, logger)

    /**
     * Build a request URL under [baseUrl], preserving any path prefix it carries.
     *
     * [path] is taken as already percent-encoded where it needs to be — build an id into it with
     * [pathSegment]. In [query], null and empty values are left out and an [Iterable] repeats the
     * key, in the order given. Values are encoded strictly: a `+` in a phone number arrives as a
     * `+`, not as a space.
     */
    public fun resolve(path: String, query: Map<String, Any?> = emptyMap()): HttpUrl {
        val builder = baseUrl.newBuilder()
        val trimmed = path.trimStart('/')
        if (trimmed.isNotEmpty()) builder.addEncodedPathSegments(trimmed)
        for ((key, value) in query) {
            when (value) {
                null -> Unit
                is Iterable<*> ->
                    value.mapNotNull { it?.toString() }
                        .filter { it.isNotEmpty() }
                        .forEach { builder.addQueryParameter(key, it) }
                else -> value.toString().takeIf { it.isNotEmpty() }?.let { builder.addQueryParameter(key, it) }
            }
        }
        return builder.build()
    }

    override fun toString(): String = "CnctConfig(baseUrl: $baseUrl)"

    public companion object {
        /**
         * The version this SDK reports in its `User-Agent`. Kept in step with `version` in
         * `gradle.properties` by a test.
         */
        public const val SDK_VERSION: String = "1.0.0"

        /**
         * One path segment, percent-encoded — for an id that is about to go into a path. A `/` in it
         * is encoded rather than read as a separator.
         */
        public fun pathSegment(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private fun normalise(raw: String): HttpUrl {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw CnctException(
                    "baseUrl is empty. Leave it out to use CNCT production (${CnctHosts.PRODUCTION}), " +
                        "or pass your own host.",
                    code = CnctErrorCode.INVALID,
                )
            }
            // HttpUrl only parses http and https, so anything else — a bare host, a relative path,
            // a ws:// URL — comes back null here and is refused in one place.
            val parsed = trimmed.toHttpUrlOrNull()
                ?: throw CnctException(
                    "baseUrl must be an absolute http(s) URL — got \"$trimmed\".",
                    code = CnctErrorCode.INVALID,
                )
            // Trailing slashes are stripped once, here, so that every path built from this is built
            // the same way and a host written with one behaves identically to a host written
            // without.
            val builder = parsed.newBuilder().query(null).fragment(null)
            var segments = parsed.pathSegments.size
            while (segments > 1 && parsed.pathSegments[segments - 1].isEmpty()) {
                builder.removePathSegment(segments - 1)
                segments--
            }
            return builder.build()
        }
    }
}
