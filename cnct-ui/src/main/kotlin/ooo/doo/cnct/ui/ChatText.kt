package ooo.doo.cnct.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * A customer's or an operator's words, ready to draw: web addresses made links, and every phone
 * number, email address and web address held left-to-right.
 *
 * The second matters in Arabic. In a right-to-left line, "+973 3311 2233" is three numbers separated
 * by spaces, and the bidirectional algorithm lays them out right to left — "2233 3311 973+", a
 * different number. Wrapping each in a left-to-right isolate (U+2066 … U+2069) keeps it whole and in
 * order, in any language, without touching the direction of the words around it.
 */
internal object ChatText {
    private const val LTR_ISOLATE = '⁦'
    private const val POP_ISOLATE = '⁩'

    /**
     * The hosted widget's web address pattern, character for character: from `http://`, `https://` or
     * `www.` in any case, to before whitespace and before the punctuation a sentence puts after an
     * address — a full stop, the Arabic comma, a closing bracket the address did not open.
     */
    private val WEB = Regex(
        """(?:[Hh][Tt][Tt][Pp][Ss]?://|\b[Ww][Ww][Ww]\.)(?:[^\s()<>"]|\([^\s()<>"]*\))*(?:[^\s()<>".,:;!?'’”»\]}*_~،؛؟…]|\([^\s()<>"]*\))""",
        setOf(RegexOption.MULTILINE),
    )
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    /** Seven or more digits, with the spaces, dashes and dots people write between them. */
    private val PHONE = Regex("""(?<![\w+])\+?\d(?:[ \-.]?\d){6,}""")

    internal class Span(val start: Int, val end: Int, val href: String?)

    /** Where the addresses and numbers are in [text], in order, never overlapping. Web addresses win. */
    fun spans(text: String): List<Span> {
        val found = ArrayList<Span>()
        fun take(regex: Regex, link: (String) -> String?) {
            for (match in regex.findAll(text)) {
                val range = match.range
                if (found.any { range.first < it.end && it.start <= range.last }) continue
                found += Span(range.first, range.last + 1, link(match.value))
            }
        }
        take(WEB) { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        take(EMAIL) { null }
        take(PHONE) { null }
        return found.sortedBy { it.start }
    }

    /** [text] with its addresses as links, in the bubble's own ink and underlined, and isolated. */
    fun annotate(text: String, onLink: ((String) -> Unit)? = null): AnnotatedString {
        val spans = spans(text)
        if (spans.isEmpty()) return AnnotatedString(text)
        val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
        return buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                append(text, cursor, span.start)
                append(LTR_ISOLATE)
                val piece = text.substring(span.start, span.end)
                if (span.href != null) {
                    // Without a handler, Compose opens it with the platform's URI handler.
                    val listener = onLink?.let { open -> LinkInteractionListener { open((it as LinkAnnotation.Url).url) } }
                    val link = LinkAnnotation.Url(span.href, style, listener)
                    withLink(link) { append(piece) }
                } else {
                    append(piece)
                }
                append(POP_ISOLATE)
                cursor = span.end
            }
            append(text, cursor, text.length)
        }
    }

    /** The plain text with the isolates, for places that cannot draw links — a quote, a label. */
    fun isolated(text: String): String = annotate(text).text
}
