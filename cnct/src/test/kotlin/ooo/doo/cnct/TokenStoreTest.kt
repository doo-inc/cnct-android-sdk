package ooo.doo.cnct

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenStoreTest {
    @Test
    fun `the memory store keeps, reads and clears`() = runTest {
        val store = CnctTokenStore.memory()
        assertNull(store.read("k"))
        store.write("k", "token")
        assertEquals("token", store.read("k"))
        store.clear("k")
        assertNull(store.read("k"))
    }
}
