package ooo.doo.cnct.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import ooo.doo.cnct.CnctChatAvailability
import ooo.doo.cnct.CnctLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatFormatTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Bahrain")
    private val now = Instant.parse("2026-11-09T10:00:00Z") // Monday 13:00 in Bahrain

    private fun format(language: CnctLanguage, overrides: Map<String, String> = emptyMap()): ChatFormat {
        val words = Words(Words.resourcesFor(context, language), language, overrides)
        return ChatFormat(words, Languages.formatting(language, listOf(Locale.US)), zone) { now }
    }

    /** Newer locale data puts a narrow no-break space before "AM"; the words are what is under test. */
    private fun ChatFormat.line(now: CnctChatAvailability?): String = availability(now).replace('\u202F', ' ')

    @Test
    fun `the availability sentence is the hosted widget's`() {
        val en = format(CnctLanguage.EN)
        assertEquals("The team will reply here as soon as they can", en.line(null))
        assertEquals("Our assistant replies straight away", en.line(CnctChatAvailability(assistant = true)))
        assertEquals("Usually replies in under a minute", en.line(CnctChatAvailability(open = true, replyMinutes = 0.5)))
        assertEquals("Usually replies in a few minutes", en.line(CnctChatAvailability(open = true, replyMinutes = 4.0)))
        assertEquals("Usually replies in under 20 minutes", en.line(CnctChatAvailability(replyMinutes = 20.0)))
        assertEquals("Usually replies in under an hour", en.line(CnctChatAvailability(replyMinutes = 45.0)))
        assertEquals("Usually replies in a few hours", en.line(CnctChatAvailability(replyMinutes = 200.0)))
        assertEquals("Usually replies within a day", en.line(CnctChatAvailability(replyMinutes = 600.0)))

        val tomorrow = CnctChatAvailability(open = false, opensAt = Instant.parse("2026-11-10T06:00:00Z"))
        assertEquals("The team is back tomorrow at 9:00 AM", en.line(tomorrow))
        assertEquals(
            "The team is back tomorrow at 9:00 AM · our assistant can help now",
            en.line(CnctChatAvailability(open = false, opensAt = tomorrow.opensAt, assistant = true)),
        )
        assertEquals("The team is back today at 5:00 PM", en.line(CnctChatAvailability(open = false, opensAt = Instant.parse("2026-11-09T14:00:00Z"))))
        assertEquals("The team is back Thursday at 9:00 AM", en.line(CnctChatAvailability(open = false, opensAt = Instant.parse("2026-11-12T06:00:00Z"))))
    }

    @Test
    fun `in Arabic, with Latin digits`() {
        val ar = format(CnctLanguage.AR)
        val line = ar.line(CnctChatAvailability(open = false, opensAt = Instant.parse("2026-11-10T06:00:00Z")))
        assertEquals(true, line.startsWith("يعود الفريق غداً الساعة 9:00"))
        assertEquals("اليوم", ar.day(now))
        assertEquals("أمس", ar.day(now.minusSeconds(86_400)))
        assertEquals("انتهت المحادثة اليوم", ar.ended(now))
    }

    @Test
    fun `an app's own words win, with their placeholders filled`() {
        val en = format(CnctLanguage.EN, mapOf("backTomorrow" to "Back tomorrow, {time}"))
        assertEquals("Back tomorrow, 9:00 AM", en.line(CnctChatAvailability(open = false, opensAt = Instant.parse("2026-11-10T06:00:00Z"))))
    }

    @Test
    fun `sizes are decimal, Latin, and held left to right`() {
        assertEquals("⁦1.2 MB⁩", ChatFormat.size(1_200_000))
        assertEquals("⁦220 KB⁩", ChatFormat.size(220_000))
        assertEquals("⁦12 MB⁩", ChatFormat.size(12_400_000))
        assertEquals("⁦980 B⁩", ChatFormat.size(980))
        assertEquals(null, ChatFormat.size(null))
    }
}
