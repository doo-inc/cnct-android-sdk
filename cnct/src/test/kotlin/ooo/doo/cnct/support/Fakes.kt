package ooo.doo.cnct.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import ooo.doo.cnct.CnctSocket
import ooo.doo.cnct.CnctSocketFactory
import ooo.doo.cnct.CnctSocketListener
import okio.Buffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The platform's HTTP side, answered in-process.
 *
 * An OkHttp interceptor answers every request without a network, and the client's dispatcher runs
 * calls on the calling thread — so a request is answered before `enqueue` returns, and a test on
 * `runTest`'s virtual clock never waits on a real thread. (MockWebServer would put every answer on a
 * socket thread, and virtual time races ahead of a real one: a keep-alive loop would tick thousands
 * of times while a test waited for a single response.)
 */
class FakeHttp(private val answer: (Seen) -> Answer) {
    class Seen(val method: String, val path: String, val headers: Headers, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    class Answer(val status: Int, val body: String, val contentType: String = "application/json")

    val seen = CopyOnWriteArrayList<Seen>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher(Inline))
        .addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() } ?: ""
            val seen = Seen(request.method, request.url.encodedPath, request.headers, body)
            this.seen += seen
            val answer = answer(seen)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(answer.status)
                .message("fake")
                .body(answer.body.toResponseBody(answer.contentType.toMediaType()))
                .build()
        }
        .build()

    private object Inline : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown() = false

        override fun isTerminated() = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}

/**
 * A WebSocket that never touches a network.
 *
 * [sent] is every frame the client wrote, in order; [push] delivers one to it; [serverClose] ends
 * the connection with a code, which is how the 1008 path gets tested without a server that refuses
 * a token. The same socket answers every connection attempt, as the Flutter suite's does.
 */
class FakeSocket : CnctSocket {
    val sent = CopyOnWriteArrayList<String>()
    var closed: Int? = null
    var opened = 0
    var url: String? = null
    var headers: Map<String, String> = emptyMap()
    private var listener: CnctSocketListener? = null

    val factory = CnctSocketFactory { url, headers, listener ->
        this.url = url
        this.headers = headers
        this.listener = listener
        opened++
        this
    }

    fun push(frame: String) = listener!!.onMessage(frame)

    fun serverClose(code: Int?) = listener!!.onClosed(code, null)

    override fun send(text: String): Boolean = sent.add(text)

    override fun close(code: Int, reason: String?) {
        closed = code
    }
}
