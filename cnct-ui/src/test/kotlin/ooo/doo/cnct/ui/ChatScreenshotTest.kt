package ooo.doo.cnct.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import ooo.doo.cnct.CnctAppearance
import ooo.doo.cnct.CnctLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The fixture conversation, rendered by the real [CnctChat] on the JVM, as the PNGs a reviewer looks
 * at to approve a release: `{en,ar} × {light,dark}` with the default theme, `{en,ar}` with a custom
 * one, and — beyond the spec's set — the name form and the ended conversation with its rating, in
 * both languages. Written to `build/outputs/roborazzi` when run with `-Proborazzi.test.record=true`
 * (`./gradlew :cnct-ui:recordRoborazziDebug`); CI uploads them as the `screenshots` artifact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1100dp-xhdpi")
class ChatScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Lets the screen's real work — the fake server's threads, decoding — finish, frame by frame. */
    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(32)
            Thread.sleep(15)
            if (until()) break
        }
        repeat(40) {
            compose.mainClock.advanceTimeBy(32)
            Thread.sleep(15)
        }
    }

    private fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /** The tray's photo has reached the point the fixture holds it at — about 40%. */
    private fun photoHeld(): Boolean = compose.onAllNodes(
        SemanticsMatcher("upload at 40%") { node ->
            node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current?.let { it >= 0.38f } == true
        },
    ).fetchSemanticsNodes().isNotEmpty()

    private fun fixture(language: CnctLanguage, theme: CnctChatTheme?, fresh: Boolean = false, content: @Composable () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CnctChatFixture.Chat(
                modifier = androidx.compose.ui.Modifier,
                language = language,
                theme = theme,
                onClose = {},
                onEvent = null,
                fresh = fresh,
            )
            content()
        }
    }

    /** The fixture with its two files in the tray: the PDF done, the photo held part of the way. */
    private fun conversation(language: CnctLanguage, theme: CnctChatTheme?) {
        fixture(language, theme)
        val failed = if (language == CnctLanguage.AR) "هل يمكنني الدفع بالبطاقة؟" else "Can I pay by card?"
        val uploading = if (language == CnctLanguage.AR) "جارٍ الرفع…" else "Uploading…"
        settle { exists(failed) && count(uploading) == 1 && photoHeld() }
    }

    private fun shoot(language: CnctLanguage, appearance: CnctAppearance) {
        conversation(language, CnctChatTheme(appearance = appearance))
        capture("chat-${language.name.lowercase()}-${appearance.name.lowercase()}")
    }

    @Test fun enLight() = shoot(CnctLanguage.EN, CnctAppearance.LIGHT)

    @Test fun enDark() = shoot(CnctLanguage.EN, CnctAppearance.DARK)

    @Test fun arLight() = shoot(CnctLanguage.AR, CnctAppearance.LIGHT)

    @Test fun arDark() = shoot(CnctLanguage.AR, CnctAppearance.DARK)

    @Test
    fun enCustom() = customShot(CnctLanguage.EN)

    @Test
    fun arCustom() = customShot(CnctLanguage.AR)

    private fun customShot(language: CnctLanguage) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CnctChatFixture.Chat(
                modifier = androidx.compose.ui.Modifier,
                language = language,
                theme = customTheme(),
                onClose = {},
                onEvent = null,
                fresh = false,
            )
        }
        val failed = if (language == CnctLanguage.AR) "هل يمكنني الدفع بالبطاقة؟" else "Can I pay by card?"
        val uploading = if (language == CnctLanguage.AR) "جارٍ الرفع…" else "Uploading…"
        settle { exists(failed) && count(uploading) == 1 && photoHeld() }
        capture("chat-${language.name.lowercase()}-custom")
    }

    @Test fun enIntro() = intro(CnctLanguage.EN)

    @Test fun arIntro() = intro(CnctLanguage.AR)

    private fun intro(language: CnctLanguage) {
        fixture(language, CnctChatTheme(appearance = CnctAppearance.LIGHT), fresh = true)
        val start = if (language == CnctLanguage.AR) "ابدأ المحادثة" else "Start chatting"
        settle { exists(start) }
        capture("chat-${language.name.lowercase()}-intro")
    }

    @Test fun enEnded() = ended(CnctLanguage.EN)

    @Test fun arEnded() = ended(CnctLanguage.AR)

    private fun ended(language: CnctLanguage) {
        conversation(language, CnctChatTheme(appearance = CnctAppearance.LIGHT))
        val end = if (language == CnctLanguage.AR) "إنهاء المحادثة" else "End chat"
        compose.onAllNodes(hasText(end))[0].performClick()
        settle { count(end) >= 2 }
        compose.onAllNodes(hasText(end))[1].performClick()
        val prompt = if (language == CnctLanguage.AR) "كيف كانت تجربتك معنا؟" else "How did we do?"
        settle { exists(prompt) }
        capture("chat-${language.name.lowercase()}-ended")
    }

    @Composable
    private fun customTheme(): CnctChatTheme {
        val red = CnctChatColors(primary = Color(0xFFD7262E))
        return CnctChatTheme(
            colors = red,
            cornerRadius = 8.dp,
            font = tajawal,
            logo = rememberVectorPainter(LOGO),
            appearance = CnctAppearance.LIGHT,
        )
    }

    private companion object {
        /** A non-system font with Latin and Arabic: Tajawal (SIL Open Font License, see resources/cnct-fonts/OFL.txt). */
        val tajawal: FontFamily by lazy {
            fun font(name: String): File {
                val file = File.createTempFile("cnct-$name", ".ttf")
                ChatScreenshotTest::class.java.getResourceAsStream("/cnct-fonts/$name.ttf")!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                return file
            }
            FontFamily(
                Font(font("Tajawal-Regular"), FontWeight.Normal),
                Font(font("Tajawal-Bold"), FontWeight.SemiBold),
                Font(font("Tajawal-Bold"), FontWeight.Bold),
            )
        }

        /** A made-up mark for the custom theme: a white wave on the brand red. Nobody's brand. */
        val LOGO: ImageVector = ImageVector.Builder("logo", 64.dp, 64.dp, 64f, 64f).apply {
            path(fill = SolidColor(Color(0xFFD7262E))) {
                moveTo(0f, 0f)
                horizontalLineTo(64f)
                verticalLineTo(64f)
                horizontalLineTo(0f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 38f)
                curveTo(20f, 26f, 28f, 26f, 32f, 34f)
                curveTo(36f, 42f, 44f, 42f, 52f, 30f)
                verticalLineTo(40f)
                curveTo(44f, 52f, 36f, 52f, 32f, 44f)
                curveTo(28f, 36f, 20f, 36f, 12f, 48f)
                close()
                moveTo(32f, 14f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 32f, 26f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 32f, 14f)
                close()
            }
        }.build()
    }
}
