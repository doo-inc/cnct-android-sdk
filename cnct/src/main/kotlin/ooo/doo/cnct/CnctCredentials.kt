package ooo.doo.cnct

/**
 * A credential CNCT issued you, and the only thing that decides which parts of this SDK you can
 * use.
 *
 * There are two — [CnctChatPublicKey] and [CnctApiKey] — they are not interchangeable, and the
 * difference between them is not convenience: it is what happens when one leaks. Each says what it
 * opens and where it is safe to keep it.
 *
 * [toString] never contains the secret, so a credential that ends up in a log line or a crash
 * report is not a leak. Neither is a data class, for the same reason.
 */
public sealed class CnctCredentials {
    /** The headers this credential travels on. Different for each, deliberately. */
    public abstract val headers: Map<String, String>

    /** A redacted form, for logs and error messages. Never contains the secret. */
    public abstract val redacted: String

    final override fun toString(): String = redacted

    internal companion object {
        /**
         * The last four characters, which is enough to tell two credentials apart in a log and not
         * enough to be one. The first characters are the same on every key ever issued, so they say
         * nothing.
         */
        fun tail(secret: String): String = if (secret.length <= 4) "****" else "…${secret.takeLast(4)}"
    }
}

/**
 * An inbox's public key — the credential a customer-facing app carries.
 *
 * **This one is meant to be in your app.** It identifies an inbox, not a person and not an account:
 * everything it opens is the visitor's own conversation, and a visitor is anonymous until they
 * volunteer a phone number. It is the same key that sits in the URL of the hosted chat page, so it
 * was already public.
 *
 * It grants chat and nothing else. It cannot read contacts, cannot list conversations, and cannot
 * see another visitor's thread.
 */
public class CnctChatPublicKey(public val publicKey: String) : CnctCredentials() {
    init {
        if (publicKey.isBlank()) {
            throw CnctException("A chat public key cannot be empty.", code = CnctErrorCode.INVALID)
        }
    }

    /**
     * The public key is part of the path rather than a header, so there is nothing to add here. The
     * per-visitor session token is added by the chat client itself, on `x-chat-token` — never on
     * `Authorization`, because the platform's rate limiter keys on `ip + authorization` and a visitor
     * who invents that header would be minting themselves a fresh budget.
     */
    override val headers: Map<String, String> get() = emptyMap()

    override val redacted: String get() = "publicKey:${tail(publicKey)}"

    override fun equals(other: Any?): Boolean = other is CnctChatPublicKey && other.publicKey == publicKey

    override fun hashCode(): Int = publicKey.hashCode()
}

/** Which kind of API key: one whose writes are real, or one whose writes are kept apart. */
public enum class CnctKeyMode {
    /** `kaer_sk_test_…`: reads the account as it really is, and writes nothing real. */
    SANDBOX,

    /** `kaer_sk_live_…`, and every key minted before there were two kinds (plain `kaer_sk_…`). */
    PRODUCTION,
}

/**
 * A server-to-server API key, minted in the CNCT console under **Settings → Developers**. Starts
 * with `kaer_sk_`.
 *
 * **There are two kinds, and the key says which** — see [mode]. A sandbox key (`kaer_sk_test_…`)
 * reads the account as it really is — services, people, hours, ticket types, what is free — but
 * nothing it books, moves, cancels or raises is real: no customer is contacted and nothing appears
 * in the business's calendar or queue, and every answer carries `sandbox: true`. A production key
 * (`kaer_sk_live_…`) is real. Both go to the same host.
 *
 * **Do not ship this in an app.** It is account-wide: it can read and write bookings and tickets for
 * every customer the account has, and a key inside an APK is a key anybody with the APK has. It
 * belongs on a server you control, with the app calling that server. That is why this SDK is plain
 * Kotlin on the JVM: the same artifact runs on that server.
 */
public class CnctApiKey(key: String) : CnctCredentials() {
    public val key: String = key.trim()

    init {
        if (this.key.isEmpty()) {
            throw CnctException("An API key cannot be empty.", code = CnctErrorCode.INVALID)
        }
        if (!this.key.startsWith(PREFIX)) {
            throw CnctException(
                "A CNCT API key starts with \"$PREFIX\". Check you have not pasted an inbox public key " +
                    "or a session token.",
                code = CnctErrorCode.INVALID,
            )
        }
    }

    /**
     * Which kind of key this is, read from its prefix. The platform decides from its own records and
     * this can only agree with it: a key is minted with its prefix and never changes kind.
     */
    public val mode: CnctKeyMode
        get() = if (key.startsWith(SANDBOX_PREFIX)) CnctKeyMode.SANDBOX else CnctKeyMode.PRODUCTION

    /** True for a `kaer_sk_test_` key, whose writes are kept apart and are not real. */
    public val isSandbox: Boolean get() = mode == CnctKeyMode.SANDBOX

    override val headers: Map<String, String> get() = mapOf("authorization" to "Bearer $key")

    override val redacted: String get() = "apiKey:${tail(key)}"

    override fun equals(other: Any?): Boolean = other is CnctApiKey && other.key == key

    override fun hashCode(): Int = key.hashCode()

    public companion object {
        /**
         * Every key ever issued begins with this. Recognisable on sight in a log or a pull request,
         * and greppable by the secret scanners that look for exactly this shape.
         */
        public const val PREFIX: String = "kaer_sk_"

        /** A sandbox key: reads the real account, writes nothing real. */
        public const val SANDBOX_PREFIX: String = "kaer_sk_test_"

        /** A production key. Keys from before there were two kinds have neither segment, and are this. */
        public const val PRODUCTION_PREFIX: String = "kaer_sk_live_"
    }
}

/**
 * One of your app's signed-in users, for [CnctChatClient.identify].
 *
 * [userHash] is `HMAC-SHA256(userId, the inbox's identity secret)` in lowercase hex, **computed on
 * your own server** and sent to the app with the user's session. The secret never goes in an app or
 * a page — anybody holding it can sign in as any of your users — and this class never sees it.
 *
 * Not a data class, and [toString] prints neither the hash nor the details, so an identity that ends
 * up in a log is not a list of your customers.
 */
public class CnctChatIdentity(
    /** Your own id for the user. Stable: the same person is the same id on every device. */
    public val userId: String,
    /** The signature your server computed over [userId]. */
    public val userHash: String,
    public val name: String? = null,
    public val email: String? = null,
    public val phone: String? = null,
) {
    init {
        if (userId.isBlank() || userHash.isBlank()) {
            throw CnctException("A signed-in identity needs both userId and userHash.", code = CnctErrorCode.INVALID)
        }
    }

    internal fun toJson(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
        put("userHash", kotlinx.serialization.json.JsonPrimitive(userHash))
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", kotlinx.serialization.json.JsonPrimitive(it)) }
        email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", kotlinx.serialization.json.JsonPrimitive(it)) }
        phone?.trim()?.takeIf { it.isNotEmpty() }?.let { put("phone", kotlinx.serialization.json.JsonPrimitive(it)) }
    }

    override fun toString(): String = "CnctChatIdentity(userId:${CnctCredentials.tail(userId)}, userHash:****)"
}
