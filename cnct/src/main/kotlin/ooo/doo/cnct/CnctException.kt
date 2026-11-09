package ooo.doo.cnct

import kotlinx.serialization.json.JsonElement

/**
 * Everything in this SDK that can fail, fails with one of these.
 *
 * The [code] is the part worth branching on. It is stable across versions and, where the failure
 * came from the server, it is the server's own code verbatim — the same string a JavaScript, Flutter
 * or iOS integrator sees for the same event, because SDKs inventing their own vocabularies for one
 * error is how a support answer stops being portable between them.
 */
public class CnctException(
    /** For a human. Never parse this — branch on [code]. */
    message: String,
    /** Stable. See [CnctErrorCode] for the ones this SDK promises to keep. */
    public val code: String = CnctErrorCode.REQUEST_FAILED,
    /**
     * The HTTP status, when the failure arrived as a response rather than as a socket frame or a
     * dead network.
     */
    public val status: Int? = null,
    /**
     * The server's whole error body, where it answered with one.
     *
     * Worth reading: a refused tool call carries everything the tool answered, and a validation
     * refusal carries the fields under `error` as `{fieldErrors, formErrors}`.
     */
    public val details: JsonElement? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override val message: String get() = super.message ?: ""

    /**
     * True when retrying later is reasonable: the network was down, the socket dropped mid-send, or
     * the server asked us to slow down. A 4xx that is not a rate limit is not in here — retrying a
     * request the server understood and refused just refuses again.
     */
    public val isRetryable: Boolean
        get() = code in RETRYABLE

    /**
     * True when the credential is the problem: it expired, it was revoked, or it never resolved. A
     * chat client clears its stored session token when it sees this; anything else should ask for a
     * fresh credential rather than retrying.
     */
    public val isUnauthenticated: Boolean
        get() = code == CnctErrorCode.UNAUTHENTICATED

    override fun toString(): String = "CnctException($code${status?.let { " $it" } ?: ""}): $message"

    private companion object {
        val RETRYABLE = setOf(
            CnctErrorCode.OFFLINE,
            CnctErrorCode.TIMEOUT,
            CnctErrorCode.RATE_LIMITED,
            CnctErrorCode.SERVER_ERROR,
        )
    }
}

/**
 * The codes this SDK will not rename.
 *
 * The first block is the server's — the platform sends these by name and they are the same strings
 * the web, Flutter and iOS SDKs expose. The second block is this client's, for the failures that
 * never reach a server. A code the server invents later still arrives intact: [CnctException.code]
 * is a string, not a closed enum.
 */
public object CnctErrorCode {
    // ── The server's ──────────────────────────────────────────────────────────────────────────

    /** The request was malformed or failed validation. [CnctException.details] carries the fields. */
    public const val INVALID: String = "invalid"

    /** Too many requests. Slow down. */
    public const val RATE_LIMITED: String = "rate_limited"

    /** The message body was over the four-thousand-character limit. */
    public const val TOO_LARGE: String = "too_large"

    /** The conversation has ended. Start a new one; it cannot be reopened from this side. */
    public const val CONVERSATION_CLOSED: String = "conversation_closed"

    /**
     * The request collided with something that already exists or has already moved on — a slot
     * somebody else took, say. Never a reason to retry unchanged.
     */
    public const val CONFLICT: String = "conflict"

    /** The session token now points at a different conversation than the one being written to. */
    public const val CONVERSATION_CHANGED: String = "conversation_changed"

    /** The credential did not resolve, or no longer does. */
    public const val UNAUTHENTICATED: String = "unauthenticated"

    /** Something broke on the far side. */
    public const val SERVER_ERROR: String = "server_error"

    // ── This client's ─────────────────────────────────────────────────────────────────────────

    /**
     * The network could not be reached at all, or a socket dropped before a frame was acknowledged.
     * Distinct from a 4xx on purpose: one is worth retrying and the other is not.
     */
    public const val OFFLINE: String = "offline"

    /** A request or a send was not answered inside the configured window. */
    public const val TIMEOUT: String = "timeout"

    /** A method that needs a live session was called before `start()` or `resume()`. */
    public const val NO_SESSION: String = "no_session"

    /** Nothing to send: the body was empty or whitespace. */
    public const val EMPTY: String = "empty"

    /** A lookup found nothing. */
    public const val NOT_FOUND: String = "not_found"

    /** The server answered, and answered with an error this SDK has no more specific name for. */
    public const val REQUEST_FAILED: String = "request_failed"

    /**
     * The response was not the shape this SDK expects. Almost always a base URL pointing at
     * something that is not a CNCT host.
     */
    public const val BAD_RESPONSE: String = "bad_response"

    /** A module was used without the credential it needs. */
    public const val MISSING_CREDENTIAL: String = "missing_credential"

    /**
     * More files than one message may carry — ten. Said before anything is uploaded. (A file over the
     * 50 MB limit is [TOO_LARGE], before upload as after.) The attachment codes are the same on the
     * web and iOS SDKs, so a support answer about a file is the same answer on every platform.
     */
    public const val TOO_MANY: String = "too_many"

    /**
     * A kind of file the chat does not take — said before upload, or answered `415`/`422` — HEIC
     * included, which must be converted to JPEG first.
     */
    public const val UNSUPPORTED_TYPE: String = "unsupported_type"

    /**
     * The inbox takes no files (`403`), or the deployment keeps none (`503`). **Not a session
     * problem**: the conversation goes on, without files — which is why a `403` here is not
     * [UNAUTHENTICATED].
     */
    public const val ATTACHMENTS_DISABLED: String = "attachments_disabled"

    /**
     * A tool declined, in a sentence rather than a status code. The platform answers `200` with an
     * `error` key when it will not do something, and this is that, raised.
     */
    public const val TOOL_REFUSED: String = "tool_refused"
}
