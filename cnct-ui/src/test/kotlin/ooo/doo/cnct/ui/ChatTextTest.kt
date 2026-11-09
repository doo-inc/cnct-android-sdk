package ooo.doo.cnct.ui

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    private fun links(text: String) = ChatText.annotate(text).getLinkAnnotations(0, Int.MAX_VALUE).map { (it.item as LinkAnnotation.Url).url }

    @Test
    fun `a web address is a link, and the punctuation after it is not part of it`() {
        assertEquals(listOf("https://pay.example.com/i/42"), links("Pay here: https://pay.example.com/i/42."))
        assertEquals(listOf("https://www.example.com"), links("See www.example.com, thanks"))
        assertEquals(listOf("HTTPS://Example.com/a_(b)"), links("HTTPS://Example.com/a_(b) works"))
        assertEquals(listOf("https://example.com/مرحبا"), links("الرابط https://example.com/مرحبا، شكراً"))
        assertTrue(links("no links here").isEmpty())
    }

    @Test
    fun `phone numbers, emails and addresses are held left to right`() {
        assertEquals(
            "اتصلوا بي على ⁦+973 3311 2233⁩ من فضلك",
            ChatText.isolated("اتصلوا بي على +973 3311 2233 من فضلك"),
        )
        assertEquals("راسلنا على ⁦help@example.com⁩", ChatText.isolated("راسلنا على help@example.com"))
        assertEquals("from 2pm to 10pm", ChatText.isolated("from 2pm to 10pm"))
        assertEquals("Order 1234 is ready", ChatText.isolated("Order 1234 is ready"))
    }
}
