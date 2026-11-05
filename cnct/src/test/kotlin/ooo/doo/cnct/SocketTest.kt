package ooo.doo.cnct

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The default socket factory, against a real WebSocket upgrade. */
class SocketTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private class Heard : CnctSocketListener {
        val frames = LinkedBlockingQueue<String>()
        val closes = LinkedBlockingQueue<Pair<Int?, Throwable?>>()

        override fun onMessage(text: String) {
            frames.put(text)
        }

        override fun onClosed(code: Int?, error: Throwable?) {
            closes.put(code to error)
        }
    }

    private fun url() = CnctConfig(baseUrl = server.url("/").toString()).socketBaseUrl + "/ws/chat"

    @Test
    fun `frames written before the upgrade are delivered, and the server's close code is reported`() {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.put(text)
                        webSocket.send("""{"type":"authenticated","accepts":["send"]}""")
                        webSocket.close(1008, "unauthenticated")
                    }
                },
            ),
        )
        val heard = Heard()

        val socket = CnctSocketFactory.okHttp().open(url(), mapOf("user-agent" to "cnct-test"), heard)
        // Written the moment the socket exists — the server gives a connection five seconds to
        // authenticate, so the handshake cannot wait for an open callback.
        socket.send("""{"type":"auth","token":"t"}""")

        assertEquals("""{"type":"auth","token":"t"}""", received.poll(5, TimeUnit.SECONDS))
        assertEquals("""{"type":"authenticated","accepts":["send"]}""", heard.frames.poll(5, TimeUnit.SECONDS))
        val (code, error) = assertNotNull(heard.closes.poll(5, TimeUnit.SECONDS))
        assertEquals(1008, code)
        assertNull(error)
        assertEquals("/ws/chat", server.takeRequest().path)
        assertNull(heard.closes.poll(200, TimeUnit.MILLISECONDS), "closed is reported once")
    }

    @Test
    fun `a connection that fails is reported once, with no code`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val heard = Heard()

        CnctSocketFactory.okHttp().open(url(), emptyMap(), heard)

        val (code, error) = assertNotNull(heard.closes.poll(5, TimeUnit.SECONDS))
        assertNull(code)
        assertNotNull(error)
    }

    @Test
    fun `a close from this side is answered and reported`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"pong"}""")
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }
                },
            ),
        )
        val heard = Heard()
        val socket = CnctSocketFactory.okHttp().open(url(), emptyMap(), heard)
        heard.frames.poll(5, TimeUnit.SECONDS)

        socket.close(1000, "Client closed")

        assertEquals(1000, assertNotNull(heard.closes.poll(5, TimeUnit.SECONDS)).first)
    }
}
