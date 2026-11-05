package ooo.doo.cnct

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a chat WebSocket gets made. Swappable so tests can drive a fake one without a server, and so an
 * app with its own networking stack can bring it.
 *
 * The default is [okHttp]. A factory is called once per connection attempt; it must not block, and
 * frames written before the connection is up must be queued rather than dropped — the handshake goes
 * out the moment the socket exists, because the server closes one that has not authenticated within
 * five seconds.
 */
public fun interface CnctSocketFactory {
    /**
     * Start connecting to [url] — `wss://…/ws/chat` — and report what happens to [listener].
     *
     * [headers] are the SDK's `user-agent` and the [CnctConfig.headers] — never a credential. The
     * session token travels in the first frame instead, so it never sits in a proxy's access log.
     */
    public fun open(url: String, headers: Map<String, String>, listener: CnctSocketListener): CnctSocket

    public companion object {
        /** OkHttp's WebSocket. Pass your own client to share its connection pool and interceptors. */
        public fun okHttp(client: OkHttpClient = sharedOkHttpClient): CnctSocketFactory = OkHttpSocketFactory(client)
    }
}

/** One open, or opening, chat connection. */
public interface CnctSocket {
    /** Queue a text frame. False when the socket is already closing or closed. */
    public fun send(text: String): Boolean

    /** Close it. The listener still hears [CnctSocketListener.onClosed] afterwards. */
    public fun close(code: Int, reason: String?)
}

/** What a [CnctSocket] reports. Called from whatever thread the socket lives on. */
public interface CnctSocketListener {
    /** A frame arrived. Binary frames are handed over as UTF-8 text. */
    public fun onMessage(text: String)

    /**
     * The connection is over — called exactly once. [code] is the close code the server sent (`1008`
     * means the token did not resolve), or null when the connection failed without one; [error] is
     * why, when there is a why.
     */
    public fun onClosed(code: Int?, error: Throwable?)
}

private class OkHttpSocketFactory(private val client: OkHttpClient) : CnctSocketFactory {
    override fun open(url: String, headers: Map<String, String>, listener: CnctSocketListener): CnctSocket {
        // OkHttp reads a ws(s):// URL as http(s):// and upgrades.
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        val socket = client.newWebSocket(request, Relay(listener))
        return object : CnctSocket {
            override fun send(text: String): Boolean = socket.send(text)

            override fun close(code: Int, reason: String?) {
                socket.close(code, reason)
            }
        }
    }

    private class Relay(private val listener: CnctSocketListener) : WebSocketListener() {
        private val ended = AtomicBoolean(false)

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Answer the close so OkHttp can finish it. Not with the server's own code: a close with no
            // status arrives as 1005, which OkHttp will not send back.
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ended.compareAndSet(false, true)) listener.onClosed(code, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (ended.compareAndSet(false, true)) listener.onClosed(null, t)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
