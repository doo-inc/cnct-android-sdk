package ooo.doo.cnct.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ooo.doo.cnct.CnctChatIdentity
import ooo.doo.cnct.CnctLanguage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CNCT's chat as a whole screen, for an app without Compose — two lines from any `Activity` or
 * `Fragment`:
 *
 * ```kotlin
 * startActivity(CnctChatActivity.intent(this, publicKey = "the-inbox-public-key"))
 * ```
 *
 * It is the same [CnctChat], with the header's close button finishing the activity. The public key,
 * host and language travel in the intent; a theme, your own words, a signed-in identity and an
 * event callback cannot (they are not parcelable), so they are held in memory for the activity
 * and, if Android ever recreates it from nothing, it carries on without them — branded from the
 * console, anonymous.
 */
public class CnctChatActivity : ComponentActivity() {
    private var optionsKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val publicKey = intent.getStringExtra(EXTRA_PUBLIC_KEY)
        if (publicKey.isNullOrBlank()) {
            finish()
            return
        }
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
        val language = intent.getStringExtra(EXTRA_LANGUAGE)?.let { name -> CnctLanguage.entries.firstOrNull { it.name == name } }
        optionsKey = intent.getStringExtra(EXTRA_OPTIONS)
        val options = optionsKey?.let(held::get)
        setContent {
            CnctChat(
                publicKey = publicKey,
                baseUrl = baseUrl,
                theme = options?.theme,
                language = language,
                strings = options?.strings,
                identity = options?.identity,
                onClose = ::finish,
                onEvent = options?.onEvent,
            )
        }
    }

    override fun onDestroy() {
        if (isFinishing) optionsKey?.let(held::remove)
        super.onDestroy()
    }

    private class Options(
        val theme: CnctChatTheme?,
        val strings: CnctChatStrings?,
        val identity: CnctChatIdentity?,
        val onEvent: ((CnctChatUiEvent) -> Unit)?,
    )

    public companion object {
        private const val EXTRA_PUBLIC_KEY = "ooo.doo.cnct.ui.PUBLIC_KEY"
        private const val EXTRA_BASE_URL = "ooo.doo.cnct.ui.BASE_URL"
        private const val EXTRA_LANGUAGE = "ooo.doo.cnct.ui.LANGUAGE"
        private const val EXTRA_OPTIONS = "ooo.doo.cnct.ui.OPTIONS"
        private val held = ConcurrentHashMap<String, Options>()

        /** An intent for the chat screen. Every option is [CnctChat]'s, and means the same. */
        public fun intent(
            context: Context,
            publicKey: String,
            baseUrl: String? = null,
            theme: CnctChatTheme? = null,
            language: CnctLanguage? = null,
            strings: CnctChatStrings? = null,
            identity: CnctChatIdentity? = null,
            onEvent: ((CnctChatUiEvent) -> Unit)? = null,
        ): Intent {
            val intent = Intent(context, CnctChatActivity::class.java)
                .putExtra(EXTRA_PUBLIC_KEY, publicKey)
                .putExtra(EXTRA_BASE_URL, baseUrl)
                .putExtra(EXTRA_LANGUAGE, language?.name)
            if (theme != null || strings != null || identity != null || onEvent != null) {
                val key = UUID.randomUUID().toString()
                held[key] = Options(theme, strings, identity, onEvent)
                intent.putExtra(EXTRA_OPTIONS, key)
            }
            return intent
        }
    }
}
