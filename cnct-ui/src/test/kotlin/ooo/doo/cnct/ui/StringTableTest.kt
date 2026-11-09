package ooo.doo.cnct.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The interface's words are the shared table, `docs/chat-ui/strings.json`, exactly: the same keys in
 * both languages, no Arabic left empty, `{name}` as positional arguments. Regenerate with
 * `./gradlew :cnct-ui:generateCnctStrings` when this fails.
 */
class StringTableTest {
    private val table = Json.parseToJsonElement(File("../docs/chat-ui/strings.json").readText()).jsonObject
    private val en = table.getValue("en").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val ar = table.getValue("ar").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun resources(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to unescape(node.textContent)
        }
    }

    /** What Android reads out of a string resource. */
    private fun unescape(value: String): String = value
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\@", "@")
        .replace("\\?", "?")
        .replace("\\\\", "\\")

    private fun name(key: String) = "cnct_" + Regex("([a-z0-9])([A-Z])").replace(key, "$1_$2").lowercase()

    private fun args(key: String) = Regex("""\{(\w+)\}""").findAll(en.getValue(key)).map { it.groupValues[1] }.distinct().toList()

    private fun positional(key: String, value: String): String {
        val names = args(key)
        return Regex("""\{(\w+)\}""").replace(value) { "%${names.indexOf(it.groupValues[1]) + 1}\$s" }
    }

    @Test
    fun `the two languages of the shared table have the same keys, and no Arabic is empty`() {
        assertEquals(en.keys, ar.keys)
        assertEquals(129, en.size)
        for ((key, value) in ar) assertTrue("ar.$key is empty", value.isNotBlank())
        for ((key, value) in en) {
            // An Arabic string names the same values as the English, whatever order it puts them in.
            assertEquals("ar.$key", Regex("""\{(\w+)\}""").findAll(value).map { it.value }.toSet(), Regex("""\{(\w+)\}""").findAll(ar.getValue(key)).map { it.value }.toSet())
        }
    }

    @Test
    fun `the resources are the shared table, key for key and word for word`() {
        val english = resources("src/main/res/values/strings.xml")
        val arabic = resources("src/main/res/values-ar/strings.xml")
        assertEquals(en.keys.map(::name).toSet(), english.keys)
        assertEquals(english.keys, arabic.keys)
        // No two keys collapse into one resource name.
        assertEquals(en.size, en.keys.map(::name).toSet().size)
        for (key in en.keys) {
            assertEquals("en.$key", positional(key, en.getValue(key)), english[name(key)])
            assertEquals("ar.$key", positional(key, ar.getValue(key)), arabic[name(key)])
            assertFalse("ar.$key has a literal brace left", arabic.getValue(name(key)).contains("{"))
        }
    }

    @Test
    fun `the Kotlin table maps every key to its resource and its arguments`() {
        val entries = CnctStringTable.entries.associateBy { it.key }
        assertEquals(en.keys, entries.keys)
        for (key in en.keys) assertEquals(key, args(key), entries.getValue(key).args)
    }
}
