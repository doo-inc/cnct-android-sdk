package ooo.doo.cnct.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import ooo.doo.cnct.CnctLanguage
import java.util.Locale

/**
 * Per-language overrides of any of the interface's words, keyed exactly as the shared table
 * `docs/chat-ui/strings.json` keys them — `writeMessage`, `notTakingChats` — with `{name}` where a
 * value goes, as in the table. A key you do not override keeps the table's words; a key that is not
 * in the table is ignored.
 *
 * ```kotlin
 * CnctChatStrings(
 *     en = mapOf("writeMessage" to "Ask us anything…"),
 *     ar = mapOf("writeMessage" to "اسألنا عن أي شيء…"),
 * )
 * ```
 */
@Immutable
public class CnctChatStrings(
    public val en: Map<String, String> = emptyMap(),
    public val ar: Map<String, String> = emptyMap(),
) {
    internal fun of(language: CnctLanguage): Map<String, String> = if (language == CnctLanguage.AR) ar else en
}

/** One key of the shared table: its resource and the names of its `{placeholders}`, in argument order. */
internal class CnctStringEntry(val key: String, @StringRes val res: Int, val args: List<String> = emptyList())

/**
 * The interface's words in one language: the shared table's, as Android resources in that language
 * whatever the device's own, with the app's overrides on top.
 *
 * Arguments are passed already formatted — numbers with Latin digits, times in the chosen language
 * — so a word never formats anything itself.
 */
internal class Words(
    private val resources: Resources,
    val language: CnctLanguage,
    private val overrides: Map<String, String>,
) {
    operator fun invoke(@StringRes res: Int, vararg args: Any): String {
        val entry = byRes[res]
        val custom = entry?.let { overrides[it.key] }
        if (entry != null && custom != null) {
            var text: String = custom
            entry.args.forEachIndexed { index, name -> if (index < args.size) text = text.replace("{$name}", args[index].toString()) }
            return text
        }
        return if (args.isEmpty()) resources.getString(res) else resources.getString(res, *args)
    }

    /** The words for an attachment kind, as a quote says it: "Photo", "File". */
    fun kind(kind: ooo.doo.cnct.CnctAttachmentKind): String = invoke(
        when (kind) {
            ooo.doo.cnct.CnctAttachmentKind.IMAGE -> R.string.cnct_quote_image
            ooo.doo.cnct.CnctAttachmentKind.VIDEO -> R.string.cnct_quote_video
            ooo.doo.cnct.CnctAttachmentKind.AUDIO -> R.string.cnct_quote_audio
            ooo.doo.cnct.CnctAttachmentKind.STICKER -> R.string.cnct_quote_sticker
            else -> R.string.cnct_quote_document
        },
    )

    companion object {
        private val byRes: Map<Int, CnctStringEntry> by lazy { CnctStringTable.entries.associateBy { it.res } }

        /** Resources in [language], whatever the device is set to. */
        fun resourcesFor(context: Context, language: CnctLanguage): Resources {
            val configuration = Configuration(context.resources.configuration)
            val locale = Locale.forLanguageTag(if (language == CnctLanguage.AR) "ar" else "en")
            configuration.setLocale(locale)
            configuration.setLayoutDirection(locale)
            return context.createConfigurationContext(configuration).resources
        }
    }
}

internal object Languages {
    /**
     * The language the interface speaks: the app's choice, else the inbox's, and `AUTO` takes the
     * first of the device's preferred languages that is Arabic or English — so one install serves an
     * Arabic phone in Arabic and an English one in English — else English.
     */
    fun pick(app: CnctLanguage?, server: CnctLanguage?, device: List<Locale>): CnctLanguage {
        val setting = app ?: server ?: CnctLanguage.AUTO
        if (setting != CnctLanguage.AUTO) return setting
        for (locale in device) {
            when (locale.language.lowercase()) {
                "ar" -> return CnctLanguage.AR
                "en" -> return CnctLanguage.EN
            }
        }
        return CnctLanguage.EN
    }

    /**
     * The locale times and dates are written in. **Latin digits in Arabic** (`ar-u-nu-latn`), as
     * Gulf apps write them; the device's own English otherwise, so an English phone in Britain keeps
     * its 24-hour clock.
     */
    fun formatting(language: CnctLanguage, device: List<Locale>): Locale = when (language) {
        CnctLanguage.AR -> Locale.forLanguageTag("ar-u-nu-latn")
        else -> device.firstOrNull { it.language == "en" } ?: Locale.ENGLISH
    }
}
