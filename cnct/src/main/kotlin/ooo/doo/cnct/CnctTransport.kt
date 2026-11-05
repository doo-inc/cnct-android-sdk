package ooo.doo.cnct

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one place this SDK talks HTTP.
 *
 * Every module goes through it, so the error vocabulary, the timeout, the headers and the JSON
 * handling are decided once. A module that built its own request would be a module that maps a 429
 * to something slightly different, and an integrator branching on [CnctErrorCode] would be right
 * about one of them.
 *
 * Pass your own [OkHttpClient] to share an app's connection pool, interceptors or certificate
 * pinning. The default is one client shared by every transport, so many SDK objects do not mean
 * many connection pools.
 */
public class CnctTransport(
    public val config: CnctConfig,
    private val client: OkHttpClient = defaultClient,
) {
    public suspend fun get(
        path: String,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = send("GET", path, query = query, headers = headers)

    public suspend fun post(
        path: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = send("POST", path, body = body, query = query, headers = headers)

    public suspend fun patch(
        path: String,
        body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = send("PATCH", path, body = body, headers = headers)

    public suspend fun delete(path: String, headers: Map<String, String> = emptyMap()): JsonElement =
        send("DELETE", path, headers = headers)

    /**
     * The raw bytes of something — an attachment, an export. Same auth and same error mapping as the
     * JSON paths, because a 401 on a file download is the same event as a 401 anywhere else.
     */
    public suspend fun bytes(path: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val (status, data) = run(request("GET", config.resolve(path), null, headers))
        if (status >= 400) throw failure(status, data.decodeToString())
        return data
    }

    /** Send a request and answer its JSON. An empty body or a `204` answers [JsonNull]. */
    public suspend fun send(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement {
        val payload = body?.let { Json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }
        // OkHttp refuses a POST, PUT or PATCH with no body, so an empty one stands in for "nothing".
        val effective = payload ?: if (method in NEEDS_BODY) EMPTY_BODY else null
        val (status, data) = run(request(method, config.resolve(path, query), effective, headers))
        config.logger?.log(CnctLogLevel.DEBUG, "$method $path → $status", null)

        val text = data.decodeToString()
        if (status == 204 || text.isEmpty()) {
            if (status >= 400) throw failure(status, "")
            return JsonNull
        }

        val decoded = try {
            Json.parseToJsonElement(text).also { requireJsonLiteral(it) }
        } catch (_: Exception) {
            if (status >= 400) throw failure(status, text)
            throw CnctException(
                "The server answered with something that is not JSON. Check that baseUrl points at a " +
                    "CNCT host and not at a login page or a proxy error.",
                code = CnctErrorCode.BAD_RESPONSE,
                status = status,
            )
        }

        if (status >= 400) throw failure(status, text, decoded)
        return decoded
    }

    /**
     * kotlinx.serialization's tree reader takes an unquoted word as a value, so an HTML login page or
     * a proxy's plain-text error parses as a "primitive" rather than failing. At the top level, only
     * `true`, `false`, `null` and numbers are real unquoted JSON; anything else is not an answer.
     */
    private fun requireJsonLiteral(element: JsonElement) {
        if (element !is JsonPrimitive || element.isString || element is JsonNull) return
        val content = element.content
        require(content == "true" || content == "false" || content.toDoubleOrNull() != null) { "not JSON" }
    }

    private fun request(method: String, url: HttpUrl, body: RequestBody?, extra: Map<String, String>): Request {
        val builder = Request.Builder().url(url).method(method, body)
        builder.header("accept", "application/json")
        builder.header("user-agent", config.userAgent)
        config.headers.forEach { (name, value) -> builder.header(name, value) }
        extra.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private suspend fun run(request: Request): Pair<Int, ByteArray> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        call.timeout().timeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
                    } catch (error: IOException) {
                        continuation.resumeWithException(networkFailure(error))
                        return
                    }
                    continuation.resume(result)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkFailure(e))
                }
            },
        )
    }

    private fun networkFailure(error: IOException): CnctException {
        // OkHttp reports its own call timeout as an InterruptedIOException("timeout"), and a socket
        // read timeout as its subclass SocketTimeoutException. Either way the request was not
        // answered in time, which is not the same as there being no network.
        if (error is InterruptedIOException) {
            return CnctException(
                "The request timed out after ${config.connectTimeout.inWholeSeconds}s.",
                code = CnctErrorCode.TIMEOUT,
                cause = error,
            )
        }
        // A dead network, a refused connection, DNS. Distinct from an answer, because a UI should
        // retry one and not the other.
        return CnctException(
            "Could not reach ${config.baseUrl.host}: ${error.message ?: error::class.java.simpleName}",
            code = CnctErrorCode.OFFLINE,
            cause = error,
        )
    }

    /**
     * Turn a failed response into an exception with a code worth branching on.
     *
     * The platform answers in three shapes and all three are handled here rather than at the call
     * sites: `{error: "a sentence"}`, `{error: {fieldErrors, formErrors}}` from a validation refusal,
     * and `application/problem+json` with its own `code`. Where the body names a code, that code
     * wins — the server is more specific about its own failures than a status line can be.
     */
    private fun failure(status: Int, raw: String, decoded: JsonElement? = null): CnctException {
        var message = raw.ifEmpty { "Request failed with $status." }
        var details: JsonElement? = null
        var serverCode: String? = null

        if (decoded is JsonObject) {
            // The whole body, always. A refusal often carries more than a sentence, and a transport
            // that keeps only the message throws that away at the one moment it is useful.
            details = decoded
            when (val error = decoded["error"]) {
                null, JsonNull -> Unit
                is JsonPrimitive -> if (error.isString) message = error.content else message = REFUSED
                else -> message = REFUSED
            }
            (decoded["title"] as? JsonPrimitive)?.takeIf { it.isString }?.let { message = it.content }
            serverCode = (decoded["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        val code = serverCode ?: when {
            status == 400 -> CnctErrorCode.INVALID
            status == 401 || status == 403 -> CnctErrorCode.UNAUTHENTICATED
            status == 404 -> CnctErrorCode.NOT_FOUND
            status == 409 -> CnctErrorCode.CONFLICT
            status == 413 -> CnctErrorCode.TOO_LARGE
            status == 429 -> CnctErrorCode.RATE_LIMITED
            status >= 500 -> CnctErrorCode.SERVER_ERROR
            else -> CnctErrorCode.REQUEST_FAILED
        }
        return CnctException(message, code = code, status = status, details = details)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        val NEEDS_BODY = setOf("POST", "PUT", "PATCH")
        const val REFUSED = "That request was refused."

        val defaultClient: OkHttpClient by lazy { OkHttpClient() }
    }
}
