package ooo.doo.cnct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ConfigTest {
    @Test
    fun `defaults to production`() {
        assertEquals("https://app.doo.ooo/", CnctConfig().baseUrl.toString())
        assertEquals(CnctConfig().baseUrl, Cnct().config.baseUrl)
    }

    @Test
    fun `reports the version gradle publishes`() {
        assertEquals(System.getProperty("cnct.version"), CnctConfig.SDK_VERSION)
        assertEquals("cnct-android-sdk/${CnctConfig.SDK_VERSION}", CnctConfig().userAgent)
    }

    @Test
    fun `refuses an empty host rather than treating it as left out`() {
        for (raw in listOf("", "   ")) {
            val error = assertFailsWith<CnctException> { CnctConfig(baseUrl = raw) }
            assertEquals(CnctErrorCode.INVALID, error.code)
        }
    }

    @Test
    fun `refuses anything that is not absolute http(s)`() {
        for (raw in listOf("app.doo.ooo", "ftp://x.test", "/relative", "wss://x.test")) {
            assertFailsWith<CnctException>(raw) { CnctConfig(baseUrl = raw) }
        }
    }

    @Test
    fun `strips trailing slashes, query and fragment once`() {
        assertEquals("https://x.test/", CnctConfig(baseUrl = "https://x.test/").baseUrl.toString())
        assertEquals("https://x.test/support", CnctConfig(baseUrl = " https://x.test/support// ").baseUrl.toString())
        assertEquals("https://x.test/a", CnctConfig(baseUrl = "https://x.test/a?b=c#d").baseUrl.toString())
    }

    @Test
    fun `derives the socket origin and keeps the prefix`() {
        assertEquals("wss://app.doo.ooo", CnctConfig().socketBaseUrl)
        assertEquals("ws://localhost:3001/p", CnctConfig(baseUrl = "http://localhost:3001/p").socketBaseUrl)
    }

    @Test
    fun `resolves paths under the prefix`() {
        val config = CnctConfig(baseUrl = "https://x.test/support")
        assertEquals("https://x.test/support/api/tools", config.resolve("/api/tools").toString())
        assertEquals("https://x.test/support/api/tools", config.resolve("api/tools").toString())
        assertEquals("https://app.doo.ooo/api/tools", CnctConfig().resolve("/api/tools").toString())
    }

    @Test
    fun `encodes a plus in a query value rather than letting it become a space`() {
        val url = CnctConfig().resolve("/api/x", mapOf("phone" to "+97312345678"))
        assertEquals("https://app.doo.ooo/api/x?phone=%2B97312345678", url.toString())
    }

    @Test
    fun `drops null and empty query values and repeats iterables, in order`() {
        val url = CnctConfig().resolve(
            "/api/x",
            linkedMapOf("z" to "1", "a" to null, "e" to "", "tags" to listOf("one", "", "two"), "n" to 3, "b" to true),
        )
        assertEquals("z=1&tags=one&tags=two&n=3&b=true", url.encodedQuery)
    }

    @Test
    fun `encodes an id as a single path segment`() {
        val id = CnctConfig.pathSegment("a/b c")
        assertEquals("a%2Fb%20c", id)
        assertEquals("https://app.doo.ooo/api/files/a%2Fb%20c", CnctConfig().resolve("/api/files/$id").toString())
    }

    @Test
    fun `copy carries everything but the host`() {
        val config = CnctConfig(headers = mapOf("x-trace" to "1"), connectTimeout = 5.seconds)
        val moved = config.copy(baseUrl = "https://staging.example.com")
        assertEquals("https://staging.example.com/", moved.baseUrl.toString())
        assertEquals(mapOf("x-trace" to "1"), moved.headers)
        assertEquals(5.seconds, moved.connectTimeout)
        assertEquals("http://localhost:3001/", Cnct().withBaseUrl("http://localhost:3001").config.baseUrl.toString())
        assertEquals("http://localhost:3001/", Cnct.host("http://localhost:3001").config.baseUrl.toString())
    }
}
