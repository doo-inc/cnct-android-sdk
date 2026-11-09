package ooo.doo.cnct.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ooo.doo.cnct.CnctLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What Android actually hands back for every key, in both languages, whatever the device is set to —
 * after aapt has had its way with whitespace, quotes and apostrophes. The XML test reads the files;
 * this reads the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StringResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val table = Json.parseToJsonElement(File("../docs/chat-ui/strings.json").readText()).jsonObject

    private fun expected(language: String, entry: CnctStringEntry): String {
        val raw = table.getValue(language).jsonObject.getValue(entry.key).jsonPrimitive.content
        return Regex("""\{(\w+)\}""").replace(raw) { "%${entry.args.indexOf(it.groupValues[1]) + 1}\$s" }
    }

    @Test
    fun `every key reads back from Android exactly as the shared table says, in English and Arabic`() {
        for ((language, code) in listOf(CnctLanguage.EN to "en", CnctLanguage.AR to "ar")) {
            val resources = Words.resourcesFor(context, language)
            for (entry in CnctStringTable.entries) {
                assertEquals("$code.${entry.key}", expected(code, entry), resources.getString(entry.res))
            }
        }
    }

    @Test
    fun `values are filled in by position, so Arabic may put them in its own order`() {
        val ar = Words(Words.resourcesFor(context, CnctLanguage.AR), CnctLanguage.AR, emptyMap())
        assertEquals("سارة وعمر هنا للمساعدة", ar(R.string.cnct_many_here, "سارة", "عمر"))
        assertEquals("يعود الفريق يوم الأحد الساعة 9:00", ar(R.string.cnct_back_on, "الأحد", "9:00"))
    }
}
