package ooo.doo.cnct.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ooo.doo.cnct.CnctAppearance
import ooo.doo.cnct.CnctLanguage
import ooo.doo.cnct.ui.CnctChat
import ooo.doo.cnct.ui.CnctChatActivity
import ooo.doo.cnct.ui.CnctChatColors
import ooo.doo.cnct.ui.CnctChatFixture
import ooo.doo.cnct.ui.CnctChatTheme

/**
 * The SDK's chat UI, with the switches a reviewer wants: the language, the appearance, the custom
 * theme, and a fixture mode that needs no key and no network.
 *
 * Everything the chat itself does is [CnctChat]'s. This app only chooses its options.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Sample() }
    }
}

/** Where the sample points, from `local.properties` — see `sample/build.gradle.kts`. */
private val publicKey: String = BuildConfig.CNCT_PUBLIC_KEY
private val baseUrl: String? = BuildConfig.CNCT_BASE_URL.ifBlank { null }

private enum class Mode { FIXTURE, LIVE }

private enum class Look { DEFAULT, CUSTOM }

@Composable
private fun Sample() {
    var mode by rememberSaveable { mutableStateOf(if (publicKey.isBlank()) Mode.FIXTURE else Mode.LIVE) }
    var language by rememberSaveable { mutableStateOf(CnctLanguage.AUTO) }
    var appearance by rememberSaveable { mutableStateOf(CnctAppearance.SYSTEM) }
    var look by rememberSaveable { mutableStateOf(Look.DEFAULT) }
    var chatting by rememberSaveable { mutableStateOf(false) }

    val theme = theme(look, appearance)
    if (chatting) {
        BackHandler { chatting = false }
        when (mode) {
            // The whole integration, in an app with a key: one composable.
            Mode.LIVE -> CnctChat(
                publicKey = publicKey,
                baseUrl = baseUrl,
                theme = theme,
                language = language,
                onClose = { chatting = false },
                onEvent = { event -> android.util.Log.d("CnctSample", "Chat event: $event") },
            )
            Mode.FIXTURE -> CnctChatFixture.Chat(language = language, theme = theme, onClose = { chatting = false })
        }
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = when (appearance) {
        CnctAppearance.DARK -> true
        CnctAppearance.LIGHT -> false
        CnctAppearance.SYSTEM -> isSystemInDarkTheme()
    }
    // The sample's own screen uses Material; the chat does not, and looks the same without it.
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("CNCT chat", style = MaterialTheme.typography.headlineMedium)
                Choice(
                    "Conversation",
                    listOf(Mode.FIXTURE to "Fixture (no key)", Mode.LIVE to "Live"),
                    mode,
                    enabled = { it == Mode.FIXTURE || publicKey.isNotBlank() },
                ) { mode = it }
                if (publicKey.isBlank()) {
                    Text(
                        "Live needs your inbox's public key in local.properties:\ncnct.publicKey=…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Choice("Language", listOf(CnctLanguage.AUTO to "Device", CnctLanguage.EN to "English", CnctLanguage.AR to "العربية"), language) { language = it }
                Choice(
                    "Appearance",
                    listOf(CnctAppearance.SYSTEM to "System", CnctAppearance.LIGHT to "Light", CnctAppearance.DARK to "Dark"),
                    appearance,
                ) { appearance = it }
                Choice("Theme", listOf(Look.DEFAULT to "From the console", Look.CUSTOM to "Custom"), look) { look = it }
                Button({ chatting = true }, Modifier.fillMaxWidth()) { Text("Open the chat") }
                if (mode == Mode.LIVE) {
                    // For an app without Compose: the same screen as an Activity.
                    OutlinedButton(
                        {
                            context.startActivity(
                                CnctChatActivity.intent(context, publicKey, baseUrl = baseUrl, language = language, theme = theme),
                            )
                        },
                        Modifier.fillMaxWidth(),
                    ) { Text("Open as an Activity") }
                }
            }
        }
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, enabled: (T) -> Boolean = { true }, pick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in options) {
                FilterChip(selected = value == selected, onClick = { pick(value) }, label = { Text(label) }, enabled = enabled(value))
            }
        }
    }
}

/**
 * The custom theme: the brand red, square-ish corners, a font of its own and a logo — every token
 * the spec's screenshots exercise. The default theme passes only the appearance, so the rest comes
 * from the inbox's settings in the CNCT console.
 */
@Composable
private fun theme(look: Look, appearance: CnctAppearance): CnctChatTheme = when (look) {
    Look.DEFAULT -> CnctChatTheme(appearance = appearance)
    Look.CUSTOM -> CnctChatTheme(
        colors = CnctChatColors(primary = Color(0xFFD7262E)),
        cornerRadius = 8.dp,
        font = FontFamily(Font(R.font.tajawal_regular, FontWeight.Normal), Font(R.font.tajawal_bold, FontWeight.SemiBold), Font(R.font.tajawal_bold, FontWeight.Bold)),
        logo = painterResource(R.drawable.sample_logo),
        appearance = appearance,
    )
}
