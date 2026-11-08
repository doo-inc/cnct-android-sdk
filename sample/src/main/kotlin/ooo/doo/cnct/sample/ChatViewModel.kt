package ooo.doo.cnct.sample

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ooo.doo.cnct.Cnct
import ooo.doo.cnct.CnctChatClient
import ooo.doo.cnct.CnctChatEvent
import ooo.doo.cnct.CnctChatPublicKey
import ooo.doo.cnct.CnctChatStatus
import ooo.doo.cnct.CnctException
import ooo.doo.cnct.android.CnctKeystoreTokenStore

/**
 * Holds the chat client for as long as the screen exists, rotations included.
 *
 * The client is made on [viewModelScope], so it lives exactly as long as this does: when the screen
 * goes for good, the scope is cancelled and the client closes its own socket. Nothing here renders —
 * the screen reads [CnctChatClient.state] directly, because rendering that is always rendering the
 * truth, straight after a reconnect included.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    /** Null when there is no public key to talk with — see `sample/build.gradle.kts`. */
    val chat: CnctChatClient?

    /** Why the screen could not open at all: a bad host, an unknown key, no network on launch. */
    var failure: CnctException? by mutableStateOf(null)
        private set

    init {
        chat = try {
            makeClient(application)
        } catch (error: CnctException) {
            failure = error
            null
        }
        chat?.let { client ->
            viewModelScope.launch { open(client) }
            viewModelScope.launch {
                client.events.collect { event ->
                    // Their thirty days ran out, or the conversation was closed from the other side.
                    // They are a stranger again; start them over rather than leave a dead screen.
                    if (event is CnctChatEvent.Unauthenticated) start(displayName = "Visitor")
                }
            }
        }
    }

    private fun makeClient(application: Application): CnctChatClient? {
        if (BuildConfig.CNCT_PUBLIC_KEY.isBlank()) return null
        // One place holds the host. HTTP and the socket both follow it.
        val cnct = if (BuildConfig.CNCT_BASE_URL.isBlank()) Cnct() else Cnct.host(BuildConfig.CNCT_BASE_URL)
        return cnct.chat(
            CnctChatPublicKey(BuildConfig.CNCT_PUBLIC_KEY),
            tokenStore = CnctKeystoreTokenStore(application),
            scope = viewModelScope,
        )
    }

    private suspend fun open(chat: CnctChatClient) {
        try {
            val inbox = chat.boot()
            // A visitor who has been here before comes straight back to their own thread. False
            // simply means this is their first time — not an error.
            val resumed = chat.resume()
            // An inbox that wants a phone number first gets the pre-chat step on screen instead.
            if (!resumed && inbox.isActive && !inbox.requirePhone) chat.start(displayName = "Visitor")
        } catch (error: CnctException) {
            failure = error
        }
    }

    fun start(displayName: String, phone: String? = null) {
        val chat = chat ?: return
        viewModelScope.launch {
            // A failure lands in state.error, where the screen shows it.
            try {
                chat.start(displayName = displayName, phone = phone)
            } catch (_: CnctException) {
            }
        }
    }

    fun send(text: String) {
        val chat = chat ?: return
        viewModelScope.launch {
            // Nothing to do on a failure: the bubble is still on screen, marked failed, with a retry.
            try {
                chat.send(text)
            } catch (_: CnctException) {
            }
        }
    }

    fun retry(clientKey: String) {
        val chat = chat ?: return
        viewModelScope.launch {
            try {
                chat.retry(clientKey)
            } catch (_: CnctException) {
            }
        }
    }

    /** Throttled inside the SDK, and again on the server, so calling it per keystroke is the intent. */
    fun typing() {
        chat?.typing()
    }

    fun end() {
        val chat = chat ?: return
        viewModelScope.launch {
            try {
                chat.end()
            } catch (_: CnctException) {
            }
        }
    }

    /**
     * The app came to the front. A socket held open in the background is a battery cost and a
     * connection the system will kill anyway; coming back reconnects, and a reconnect reloads.
     */
    fun foreground() {
        val chat = chat ?: return
        if (chat.hasSession && chat.state.value.status != CnctChatStatus.ENDED) chat.connect()
    }

    fun background() {
        chat?.disconnect()
    }
}
