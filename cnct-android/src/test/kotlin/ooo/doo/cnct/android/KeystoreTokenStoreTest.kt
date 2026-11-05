package ooo.doo.cnct.android

import android.content.Context
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// SDK 34 rather than 35: Robolectric's Android 15 runtime needs JDK 21, and CI builds on 17.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreTokenStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences(CnctKeystoreTokenStore.DEFAULT_PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        preferences.edit().clear().commit()
    }

    @Test
    fun `keeps a token, and what is on disk is not the token`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        assertTrue(store.isPersistent)
        assertNull(store.read("cnct_chat_inbox"))

        store.write("cnct_chat_inbox", "visitor-token-1")

        assertEquals("visitor-token-1", store.read("cnct_chat_inbox"))
        val onDisk = preferences.getString("cnct_chat_inbox", null)
        assertNotNull(onDisk)
        assertFalse(onDisk!!.contains("visitor-token-1"))
        assertTrue(onDisk.startsWith("v1:"))
    }

    @Test
    fun `survives a restart - a new store over the same file and key reads it`() = runBlocking {
        CnctKeystoreTokenStore(context).write("cnct_chat_inbox", "visitor-token-1")
        assertEquals("visitor-token-1", CnctKeystoreTokenStore(context).read("cnct_chat_inbox"))
    }

    @Test
    fun `asks the Keystore for an AES-256 GCM key and makes it once`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        store.write("a", "one")
        store.write("b", "two")

        val spec = FakeAndroidKeyStore.specs.getValue(CnctKeystoreTokenStore.DEFAULT_KEY_ALIAS)
        assertEquals(256, spec.keySize)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes.toList())
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertEquals(1, FakeAndroidKeyStore.secretKeys.size)
    }

    @Test
    fun `every write gets a fresh IV, so the same token never looks the same twice`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        store.write("k", "visitor-token-1")
        val first = preferences.getString("k", null)
        store.write("k", "visitor-token-1")
        assertNotEquals(first, preferences.getString("k", null))
        assertEquals("visitor-token-1", store.read("k"))
    }

    @Test
    fun `clear forgets it`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        store.write("k", "visitor-token-1")
        store.clear("k")
        assertNull(store.read("k"))
        assertFalse(preferences.contains("k"))
    }

    @Test
    fun `a token moved to another entry does not open there`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        store.write("inbox-a", "visitor-token-a")
        preferences.edit().putString("inbox-b", preferences.getString("inbox-a", null)).commit()

        assertNull(store.read("inbox-b"))
        assertFalse("the entry that cannot open is dropped", preferences.contains("inbox-b"))
        assertEquals("visitor-token-a", store.read("inbox-a"))
    }

    @Test
    fun `a tampered value reads as no session rather than an exception`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        store.write("k", "visitor-token-1")
        val parts = preferences.getString("k", null)!!.split(':')
        val flipped = parts[2].replaceFirst(parts[2][0], if (parts[2][0] == 'A') 'B' else 'A')
        preferences.edit().putString("k", "${parts[0]}:${parts[1]}:$flipped").commit()

        assertNull(store.read("k"))
        assertFalse(preferences.contains("k"))
    }

    @Test
    fun `garbage on disk reads as no session`() = runBlocking {
        preferences.edit().putString("k", "not-a-token").putString("j", "v1:%%%:%%%").commit()
        val store = CnctKeystoreTokenStore(context)
        assertNull(store.read("k"))
        assertNull(store.read("j"))
    }

    @Test
    fun `a lost Keystore key - a restored backup on a new phone - starts the visitor afresh`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        store.write("k", "visitor-token-1")
        FakeAndroidKeyStore.secretKeys.clear()

        assertNull(store.read("k"))
        assertFalse(preferences.contains("k"))

        // And the next session is kept under a new key.
        store.write("k", "visitor-token-2")
        assertEquals("visitor-token-2", store.read("k"))
    }

    @Test
    fun `its own file and key alias can be chosen`() = runBlocking {
        val store = CnctKeystoreTokenStore(context, preferencesName = "custom", keyAlias = "custom-key")
        store.write("k", "visitor-token-1")
        assertTrue(context.getSharedPreferences("custom", Context.MODE_PRIVATE).contains("k"))
        assertFalse(preferences.contains("k"))
        assertTrue(FakeAndroidKeyStore.secretKeys.containsKey("custom-key"))
        assertEquals("CnctKeystoreTokenStore(custom, custom-key)", store.toString())
    }
}

/** Below API 23 there is no AES key in the Keystore, and the store says so rather than pretending. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
class KeystoreTokenStoreBeforeMarshmallowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `keeps the token in memory and writes nothing to disk`() = runBlocking {
        val store = CnctKeystoreTokenStore(context)
        assertFalse(store.isPersistent)

        store.write("k", "visitor-token-1")

        assertEquals("visitor-token-1", store.read("k"))
        assertFalse(
            context.getSharedPreferences(CnctKeystoreTokenStore.DEFAULT_PREFERENCES_NAME, Context.MODE_PRIVATE).contains("k"),
        )
        // A restart is a stranger.
        assertNull(CnctKeystoreTokenStore(context).read("k"))
        store.clear("k")
        assertNull(store.read("k"))
    }
}
