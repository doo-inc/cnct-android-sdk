package ooo.doo.cnct.ui

import ooo.doo.cnct.CnctLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

class LanguageTest {
    private fun locales(vararg tags: String) = tags.map(Locale::forLanguageTag)

    @Test
    fun `auto takes the first of the device's languages that is Arabic or English`() {
        assertEquals(CnctLanguage.AR, Languages.pick(null, CnctLanguage.AUTO, locales("fr-FR", "ar-BH", "en-GB")))
        assertEquals(CnctLanguage.EN, Languages.pick(null, CnctLanguage.AUTO, locales("de", "en-US", "ar")))
        assertEquals(CnctLanguage.EN, Languages.pick(null, CnctLanguage.AUTO, locales("fr", "de")))
        assertEquals(CnctLanguage.EN, Languages.pick(null, null, emptyList()))
    }

    @Test
    fun `the app's choice wins over the console's, and the console's over the device`() {
        assertEquals(CnctLanguage.AR, Languages.pick(null, CnctLanguage.AR, locales("en-US")))
        assertEquals(CnctLanguage.EN, Languages.pick(CnctLanguage.EN, CnctLanguage.AR, locales("ar")))
        // An app that says AUTO means "the device", whatever the console pinned.
        assertEquals(CnctLanguage.AR, Languages.pick(CnctLanguage.AUTO, CnctLanguage.EN, locales("ar-SA")))
    }

    @Test
    fun `Arabic is written with Latin digits`() {
        val locale = Languages.formatting(CnctLanguage.AR, locales("ar-BH"))
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withDecimalStyle(DecimalStyle.STANDARD)
            .format(Instant.parse("2026-11-09T14:05:00Z").atZone(ZoneId.of("UTC")))
        assertEquals(true, time.contains("2:05"))
        assertEquals(false, time.any { it in '٠'..'٩' })
        assertEquals("latn", locale.getUnicodeLocaleType("nu"))
        assertEquals(Locale.forLanguageTag("en-GB"), Languages.formatting(CnctLanguage.EN, locales("en-GB")))
    }
}
