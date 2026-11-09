package ooo.doo.cnct.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ooo.doo.cnct.Cnct
import ooo.doo.cnct.CnctChatClient
import ooo.doo.cnct.CnctChatIdentity
import ooo.doo.cnct.CnctChatPublicKey
import ooo.doo.cnct.CnctLanguage
import ooo.doo.cnct.android.CnctKeystoreTokenStore

/**
 * CNCT's chat, ready-made: the conversation screen of CNCT's own hosted chat, as a composable, built
 * only on the headless [CnctChatClient] — so anything it does, an app can do itself.
 *
 * ```kotlin
 * CnctChat(publicKey = "the-inbox-public-key", onClose = ::finish)
 * ```
 *
 * It brands itself from the inbox's widget settings in the CNCT console; [theme] pins anything in
 * code (see [CnctChatTheme] for the precedence). It speaks English and Arabic, mirrors itself for
 * Arabic, and takes photos and files both ways. It fills the space it is given, pads itself for the
 * system bars and the keyboard, and keeps its state across a rotation.
 *
 * @param client The chat client to draw. It is yours: the screen never closes it. Make it on a
 *   `viewModelScope` with the Keystore token store from `cnct-android`, so a returning customer
 *   comes back to their own conversation.
 * @param theme Colours, corner radius, font, logo, title, appearance. Null leaves it all to the console.
 * @param language `EN` or `AR` to pin it, `AUTO` to follow the device; null to let the console decide.
 * @param strings Your own words for any key of the shared string table, per language.
 * @param identity One of your app's signed-in users, signed on your server. Applied before anything
 *   else; if it is refused, the customer still gets a working anonymous chat (it is logged for you).
 * @param onClose Called by the header's close button. Without it there is no close button.
 * @param onEvent What the visitor did, for analytics: opened, started, sent, sent files, ended, rated.
 */
@Composable
public fun CnctChat(
    client: CnctChatClient,
    modifier: Modifier = Modifier,
    theme: CnctChatTheme? = null,
    language: CnctLanguage? = null,
    strings: CnctChatStrings? = null,
    identity: CnctChatIdentity? = null,
    onClose: (() -> Unit)? = null,
    onEvent: ((CnctChatUiEvent) -> Unit)? = null,
) {
    val application = LocalContext.current.applicationContext as Application
    val holder = viewModel(key = "cnct-chat-${System.identityHashCode(client)}-${identity?.userId}") {
        ChatHolder(application, client, ownsClient = false, identity = identity)
    }
    ChatHost(holder, modifier, theme, language, strings, onClose, onEvent)
}

/**
 * CNCT's chat for an inbox's public key: the same screen, with the client made for you — kept in the
 * Android Keystore-backed token store from `cnct-android`, so a returning customer comes back to
 * their own conversation, and closed when the screen is gone for good.
 *
 * @param baseUrl Where CNCT is. Null for production, `https://app.doo.ooo`.
 */
@Composable
public fun CnctChat(
    publicKey: String,
    modifier: Modifier = Modifier,
    baseUrl: String? = null,
    theme: CnctChatTheme? = null,
    language: CnctLanguage? = null,
    strings: CnctChatStrings? = null,
    identity: CnctChatIdentity? = null,
    onClose: (() -> Unit)? = null,
    onEvent: ((CnctChatUiEvent) -> Unit)? = null,
) {
    val application = LocalContext.current.applicationContext as Application
    val holder = viewModel(key = "cnct-chat-$publicKey-$baseUrl-${identity?.userId}") {
        ChatHolder.forPublicKey(application, publicKey, baseUrl, identity)
    }
    ChatHost(holder, modifier, theme, language, strings, onClose, onEvent)
}

@Composable
internal fun ChatHost(
    holder: ChatHolder,
    modifier: Modifier,
    theme: CnctChatTheme?,
    language: CnctLanguage?,
    strings: CnctChatStrings?,
    onClose: (() -> Unit)?,
    onEvent: ((CnctChatUiEvent) -> Unit)?,
) {
    SideEffect { holder.onEvent = onEvent }
    LaunchedEffect(holder) { holder.openOnce() }
    ChatScreen(holder.controller, holder.store, theme, language, strings, onClose, modifier)
}

/**
 * The screen's state, kept across a rotation: the controller, the tray of files on their way up, the
 * attachment cache. A client it made itself it closes when it goes.
 */
internal class ChatHolder(
    application: Application,
    val client: CnctChatClient,
    private val ownsClient: Boolean,
    identity: CnctChatIdentity?,
    private val onCleared: (() -> Unit)? = null,
    private val afterOpen: (suspend (ChatController) -> Unit)? = null,
) : ViewModel() {
    var onEvent: ((CnctChatUiEvent) -> Unit)? = null
    val store = AttachmentStore(application, client)
    val controller = ChatController(client, viewModelScope, store, identity) { onEvent }
    private var opened = false

    suspend fun openOnce() {
        if (opened) return
        opened = true
        controller.open().join()
        afterOpen?.invoke(controller)
    }

    override fun onCleared() {
        if (ownsClient) client.close()
        onCleared?.invoke()
    }

    companion object {
        fun forPublicKey(application: Application, publicKey: String, baseUrl: String?, identity: CnctChatIdentity?): ChatHolder {
            val cnct = if (baseUrl.isNullOrBlank()) Cnct() else Cnct.host(baseUrl)
            // The client lives on a scope of its own, closed with the holder.
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
            val client = cnct.chat(CnctChatPublicKey(publicKey), tokenStore = CnctKeystoreTokenStore(application), scope = scope)
            return ChatHolder(application, client, ownsClient = true, identity = identity, onCleared = { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() })
        }
    }
}
