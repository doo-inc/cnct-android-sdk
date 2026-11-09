package ooo.doo.cnct.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ooo.doo.cnct.CnctAppearance
import ooo.doo.cnct.CnctChatWidget
import ooo.doo.cnct.CnctWidgetBrand
import kotlin.math.pow

/**
 * The chat's colours, by the token names every CNCT SDK shares. Leave a colour out
 * ([Color.Unspecified], the default) and it comes from the inbox's settings in the CNCT console, then
 * from the built-in defaults.
 */
@Immutable
public class CnctChatColors(
    /** The brand colour: the send button, links, the header's accent. */
    public val primary: Color = Color.Unspecified,
    /** Text and icons on [primary]. Left out, black or white — whichever reads on it. */
    public val onPrimary: Color = Color.Unspecified,
    /** The screen. */
    public val background: Color = Color.Unspecified,
    /** The header, the composer and cards. */
    public val surface: Color = Color.Unspecified,
    /** Main text. */
    public val text: Color = Color.Unspecified,
    /** Timestamps and hints. */
    public val textMuted: Color = Color.Unspecified,
    /** Dividers and outlines. */
    public val border: Color = Color.Unspecified,
    /** The customer's own messages. Left out, [primary]. */
    public val visitorBubble: Color = Color.Unspecified,
    public val onVisitorBubble: Color = Color.Unspecified,
    /** The team's and the assistant's messages. Left out, [surface]. */
    public val agentBubble: Color = Color.Unspecified,
    public val onAgentBubble: Color = Color.Unspecified,
)

/**
 * How the chat looks, where the app wants a say.
 *
 * **Precedence: what you pass here > the inbox's widget settings in the CNCT console > built-in
 * defaults.** So a business can brand the chat from the console with no code at all, and an app can
 * pin anything in code. Leave a field out to let the console decide it.
 *
 * Colours come three ways: [colors] for both appearances, and [light] and [dark] for one of them,
 * which win over [colors]. Light and dark each have their own defaults, so setting only `primary`
 * leaves both readable.
 *
 * ```kotlin
 * CnctChatTheme(
 *     colors = CnctChatColors(primary = Color(0xFFD7262E)),
 *     cornerRadius = 8.dp,
 *     font = FontFamily(Font(R.font.brand)),
 *     logo = painterResource(R.drawable.logo),
 * )
 * ```
 *
 * It does not use or change the app's `MaterialTheme`: the chat draws with these tokens alone, so it
 * looks the same inside a Material 2, Material 3 or no-Material app.
 */
@Immutable
public class CnctChatTheme(
    public val colors: CnctChatColors = CnctChatColors(),
    public val light: CnctChatColors = CnctChatColors(),
    public val dark: CnctChatColors = CnctChatColors(),
    /** Bubbles and cards. Default 18 dp. */
    public val cornerRadius: Dp = Dp.Unspecified,
    /** The typeface. Default the system's. */
    public val font: FontFamily? = null,
    /** The header image. Default the inbox's logo, else the business's first letter on [CnctChatColors.primary]. */
    public val logo: Painter? = null,
    /** The header title. Default the business's name. */
    public val title: String? = null,
    /** Light, dark, or following the device. Default the inbox's setting. */
    public val appearance: CnctAppearance? = null,
    /** The "Powered by CNCT" line. Default the inbox's setting. */
    public val showPoweredBy: Boolean? = null,
)

/** Every token decided: what the screen draws with. */
@Immutable
internal class ResolvedTheme(
    val isDark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val visitorBubble: Color,
    val onVisitorBubble: Color,
    val agentBubble: Color,
    val onAgentBubble: Color,
    val cornerRadius: Dp,
    val font: FontFamily,
    val logo: Painter?,
    val logoUrl: String?,
    val title: String,
    val showPoweredBy: Boolean,
) {
    /** The brand mixed towards the ink, so a pale brand colour still reads as text in both themes. */
    val accentInk: Color get() = mix(primary, text, 0.28f)

    /** Red for "not sent", readable on both backgrounds. */
    val danger: Color get() = if (isDark) Color(0xFFFF8A80) else Color(0xFFC5221F)
}

internal object Themes {
    /** The CNCT brand colour, when neither the app nor the console says otherwise. */
    val DEFAULT_PRIMARY = Color(0xFFB52D93)

    private val LIGHT = Defaults(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF6F7F9),
        text = Color(0xFF111418),
        textMuted = Color(0xFF5B6573),
        border = Color(0xFFE3E6EA),
    )
    private val DARK = Defaults(
        background = Color(0xFF0F1115),
        surface = Color(0xFF171A21),
        text = Color(0xFFEEF0F3),
        textMuted = Color(0xFF9AA3AF),
        border = Color(0xFF2A2F38),
    )

    private class Defaults(val background: Color, val surface: Color, val text: Color, val textMuted: Color, val border: Color)

    /** Whether the screen is dark: the app's choice, else the console's, with `system` following the device. */
    fun isDark(app: CnctChatTheme?, widget: CnctChatWidget?, systemDark: Boolean): Boolean =
        when (app?.appearance ?: widget?.brand?.appearance ?: CnctWidgetBrand().appearance) {
            CnctAppearance.DARK -> true
            CnctAppearance.LIGHT -> false
            CnctAppearance.SYSTEM -> systemDark
        }

    /**
     * Every token, by precedence: the app's [CnctChatTheme] (its [CnctChatTheme.light] or
     * [CnctChatTheme.dark], then its [CnctChatTheme.colors]), then the inbox's widget settings, then
     * the built-in defaults.
     */
    fun resolve(app: CnctChatTheme?, widget: CnctChatWidget?, business: String, systemDark: Boolean): ResolvedTheme {
        val dark = isDark(app, widget, systemDark)
        val defaults = if (dark) DARK else LIGHT
        val mine = if (dark) app?.dark else app?.light
        val both = app?.colors
        fun pick(token: (CnctChatColors) -> Color): Color? =
            mine?.let(token)?.takeIf { it.isSpecified() } ?: both?.let(token)?.takeIf { it.isSpecified() }

        val primary = pick { it.primary } ?: widget?.brand?.let { parseHex(it.colorOrDefault) } ?: DEFAULT_PRIMARY
        val onPrimary = pick { it.onPrimary } ?: readableInk(primary)
        val background = pick { it.background } ?: defaults.background
        val surface = pick { it.surface } ?: defaults.surface
        val text = pick { it.text } ?: defaults.text
        val visitorBubble = pick { it.visitorBubble }
        val agentBubble = pick { it.agentBubble }
        return ResolvedTheme(
            isDark = dark,
            primary = primary,
            onPrimary = onPrimary,
            background = background,
            surface = surface,
            text = text,
            textMuted = pick { it.textMuted } ?: defaults.textMuted,
            border = pick { it.border } ?: defaults.border,
            visitorBubble = visitorBubble ?: primary,
            onVisitorBubble = pick { it.onVisitorBubble } ?: visitorBubble?.let(::readableInk) ?: onPrimary,
            agentBubble = agentBubble ?: surface,
            onAgentBubble = pick { it.onAgentBubble } ?: agentBubble?.let(::readableInk) ?: text,
            cornerRadius = app?.cornerRadius?.takeIf { it != Dp.Unspecified } ?: 18.dp,
            font = app?.font ?: FontFamily.Default,
            logo = app?.logo,
            logoUrl = widget?.brand?.avatarUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            title = app?.title ?: business.ifEmpty { widget?.brand?.businessName.orEmpty() },
            showPoweredBy = app?.showPoweredBy ?: widget?.brand?.showBranding ?: true,
        )
    }

    /**
     * Which ink reads on a colour — the sRGB relative luminance the CNCT server computes and the
     * hosted widget repeats. Half the palette a non-designer reaches for makes white text unreadable,
     * and the alternative is asking them to solve contrast by eye.
     */
    fun readableInk(color: Color): Color {
        val argb = color.toArgb()
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        return if (luminance > 0.55) Color(0xFF141414) else Color(0xFFFFFFFF)
    }

    fun parseHex(hex: String): Color? {
        if (!Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return null
        return Color(0xFF000000 or hex.substring(1).toLong(16))
    }

    private fun Color.isSpecified(): Boolean = this != Color.Unspecified
}

/** [a] moved [amount] of the way towards [b]. */
internal fun mix(a: Color, b: Color, amount: Float): Color = Color(
    red = a.red + (b.red - a.red) * amount,
    green = a.green + (b.green - a.green) * amount,
    blue = a.blue + (b.blue - a.blue) * amount,
    alpha = a.alpha + (b.alpha - a.alpha) * amount,
)
