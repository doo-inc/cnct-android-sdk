package ooo.doo.cnct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialsTest {
    @Test
    fun `a public key opens chat and travels on no header`() {
        val key = CnctChatPublicKey("inbox-public-key-1234")
        assertTrue(key.headers.isEmpty())
        assertEquals(setOf(CnctModule.CHAT), Cnct.modulesFor(key))
        assertFailsWith<CnctException> { CnctChatPublicKey("  ") }
    }

    @Test
    fun `an API key opens bookings and tickets, on a bearer header`() {
        val key = CnctApiKey("kaer_sk_live_abcdef")
        assertEquals(mapOf("authorization" to "Bearer kaer_sk_live_abcdef"), key.headers)
        assertEquals(setOf(CnctModule.BOOKINGS, CnctModule.TICKETS), Cnct.modulesFor(key))
    }

    @Test
    fun `the prefix decides the mode`() {
        assertEquals(CnctKeyMode.SANDBOX, CnctApiKey("kaer_sk_test_abc").mode)
        assertTrue(CnctApiKey("kaer_sk_test_abc").isSandbox)
        assertEquals(CnctKeyMode.PRODUCTION, CnctApiKey("kaer_sk_live_abc").mode)
        assertEquals(CnctKeyMode.PRODUCTION, CnctApiKey("kaer_sk_abc").mode)
        assertFalse(CnctApiKey("kaer_sk_abc").isSandbox)
    }

    @Test
    fun `refuses something that is not an API key`() {
        assertEquals(CnctErrorCode.INVALID, assertFailsWith<CnctException> { CnctApiKey("") }.code)
        assertEquals(CnctErrorCode.INVALID, assertFailsWith<CnctException> { CnctApiKey("inbox-public-key") }.code)
    }

    @Test
    fun `printing a credential never prints the secret`() {
        val api = CnctApiKey("kaer_sk_live_supersecretWXYZ")
        val chat = CnctChatPublicKey("publickeyvalueABCD")
        assertEquals("apiKey:…WXYZ", api.toString())
        assertEquals("publicKey:…ABCD", "$chat")
        assertFalse(listOf(api).toString().contains("supersecret"))
        assertEquals("publicKey:****", CnctChatPublicKey("abc").redacted)
    }
}
