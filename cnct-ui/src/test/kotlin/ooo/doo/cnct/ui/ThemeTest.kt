package ooo.doo.cnct.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ooo.doo.cnct.CnctAppearance
import ooo.doo.cnct.CnctChatWidget
import ooo.doo.cnct.CnctWidgetBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private val console = CnctChatWidget(
        brand = CnctWidgetBrand(color = "#ffd200", appearance = CnctAppearance.DARK, avatarUrl = "https://cdn.test/logo.png", showBranding = false),
    )

    @Test
    fun `with nothing from the app or the console, the built-in defaults`() {
        val theme = Themes.resolve(null, null, "Your business", systemDark = false)
        assertEquals(Color(0xFFB52D93), theme.primary)
        assertEquals(Color.White, theme.onPrimary)
        assertEquals(Color(0xFFFFFFFF), theme.background)
        assertEquals(Color(0xFFF6F7F9), theme.surface)
        assertEquals(Color(0xFF111418), theme.text)
        assertEquals(Color(0xFF5B6573), theme.textMuted)
        assertEquals(Color(0xFFE3E6EA), theme.border)
        assertEquals(theme.primary, theme.visitorBubble)
        assertEquals(theme.onPrimary, theme.onVisitorBubble)
        assertEquals(theme.surface, theme.agentBubble)
        assertEquals(theme.text, theme.onAgentBubble)
        assertEquals(18.dp, theme.cornerRadius)
        assertEquals("Your business", theme.title)
        assertTrue(theme.showPoweredBy)
        assertFalse(theme.isDark)
    }

    @Test
    fun `the console's settings win over the defaults`() {
        val theme = Themes.resolve(null, console, "Your business", systemDark = false)
        assertEquals(Color(0xFFFFD200), theme.primary)
        // A pale brand colour gets dark ink, not unreadable white.
        assertEquals(Color(0xFF141414), theme.onPrimary)
        assertTrue(theme.isDark)
        assertEquals(Color(0xFF0F1115), theme.background)
        assertEquals("https://cdn.test/logo.png", theme.logoUrl)
        assertFalse(theme.showPoweredBy)
    }

    @Test
    fun `what the app passes wins over the console`() {
        val app = CnctChatTheme(
            colors = CnctChatColors(primary = Color(0xFFD7262E)),
            dark = CnctChatColors(background = Color.Black),
            cornerRadius = 8.dp,
            title = "Help",
            appearance = CnctAppearance.LIGHT,
            showPoweredBy = true,
        )
        val light = Themes.resolve(app, console, "Your business", systemDark = true)
        assertEquals(Color(0xFFD7262E), light.primary)
        assertEquals(Color.White, light.onPrimary)
        assertFalse(light.isDark)
        assertEquals(Color(0xFFFFFFFF), light.background)
        assertEquals(8.dp, light.cornerRadius)
        assertEquals("Help", light.title)
        assertTrue(light.showPoweredBy)

        // Each appearance has its own colours; one set for one does not leak into the other.
        val dark = Themes.resolve(CnctChatTheme(colors = app.colors, dark = app.dark, appearance = CnctAppearance.DARK), console, "", systemDark = false)
        assertEquals(Color.Black, dark.background)
        assertEquals(Color(0xFFD7262E), dark.primary)
    }

    @Test
    fun `system follows the device, and so does the console's auto`() {
        val auto = CnctChatWidget(brand = CnctWidgetBrand(appearance = CnctAppearance.SYSTEM))
        assertTrue(Themes.resolve(null, auto, "", systemDark = true).isDark)
        assertFalse(Themes.resolve(null, auto, "", systemDark = false).isDark)
        assertTrue(Themes.resolve(CnctChatTheme(appearance = CnctAppearance.SYSTEM), null, "", systemDark = true).isDark)
    }

    @Test
    fun `bubble colours follow the colours they are on`() {
        val app = CnctChatTheme(colors = CnctChatColors(visitorBubble = Color(0xFFE8F0FE), agentBubble = Color(0xFF202124)))
        val theme = Themes.resolve(app, null, "", systemDark = false)
        assertEquals(Color(0xFF141414), theme.onVisitorBubble)
        assertEquals(Color.White, theme.onAgentBubble)
    }

    @Test
    fun `the contrast rule is the server's, luminance over 0_55 takes dark ink`() {
        assertEquals(Color.White, Themes.readableInk(Color(0xFFB52D93)))
        assertEquals(Color.White, Themes.readableInk(Color(0xFFD7262E)))
        assertEquals(Color.White, Themes.readableInk(Color(0xFF000000)))
        assertEquals(Color(0xFF141414), Themes.readableInk(Color(0xFFFFFFFF)))
        assertEquals(Color(0xFF141414), Themes.readableInk(Color(0xFFFFD200)))
        assertEquals(Color(0xFF141414), Themes.readableInk(Color(0xFF7FE0C0)))
        assertNull(Themes.parseHex("red"))
        assertEquals(Color(0xFF00FF00), Themes.parseHex("#00ff00"))
    }
}
