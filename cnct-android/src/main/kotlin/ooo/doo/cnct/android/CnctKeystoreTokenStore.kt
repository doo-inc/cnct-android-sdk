package ooo.doo.cnct.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ooo.doo.cnct.CnctLogLevel
import ooo.doo.cnct.CnctLogger
import ooo.doo.cnct.CnctTokenStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a visitor's chat session token across launches, encrypted with a key that never leaves the
 * Android Keystore.
 *
 * The token is a thirty-day bearer secret for one conversation. Held, the same person comes back to
 * the same thread; lost, they are a stranger who starts again — which is not a failure, just a worse
 * experience. So this store is built to lose a token rather than to leak one or crash over one:
 *
 * - **The ciphertext lives in `SharedPreferences`; the key does not.** It is an AES-256-GCM key
 *   generated inside the Android Keystore, so a copy of the app's files — a backup, a rooted
 *   device's dump — holds nothing that opens it.
 * - **Each token is bound to the key it is stored under.** A ciphertext moved to another entry does
 *   not decrypt.
 * - **Anything that will not decrypt reads as "no session"** and is removed: a backup restored onto
 *   a new phone (the Keystore key does not travel with it), a Keystore cleared by the system, a value
 *   somebody edited. The chat client then starts the visitor afresh.
 *
 * This deliberately does not use `androidx.security:security-crypto`, which is deprecated; it is the
 * same Keystore primitives that library wrapped, and nothing more.
 *
 * **Below Android 6.0 (API 23) the Keystore has no AES keys**, and rather than invent weaker
 * cryptography this store keeps the token in memory there: a visitor on Android 5 is a stranger
 * after a restart, and nothing is written to disk.
 *
 * ```kotlin
 * val chat = Cnct().chat(
 *     CnctChatPublicKey(BuildConfig.CNCT_PUBLIC_KEY),
 *     tokenStore = CnctKeystoreTokenStore(context),
 *     scope = viewModelScope,
 * )
 * if (!chat.resume()) chat.start(displayName = "Layla")
 * ```
 *
 * Reads and writes run on [Dispatchers.IO]; call them from anywhere. One instance per
 * [preferencesName] is plenty — several are safe, since they share the same file and key.
 */
public class CnctKeystoreTokenStore @JvmOverloads constructor(
    context: Context,
    /** The `SharedPreferences` file the ciphertext is kept in. */
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
    /** The Android Keystore alias of the AES key. Created on the first write. */
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    /** Where to say that a stored token could not be read and was dropped. Never given the token. */
    private val logger: CnctLogger? = null,
) : CnctTokenStore {
    private val context: Context = context.applicationContext ?: context

    /** True where tokens are encrypted and kept on disk; false below API 23, where they are kept in memory. */
    public val isPersistent: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    // Chosen once, on first use, by the platform version — so nothing below API 23 ever touches a
    // Keystore class, and `SharedPreferences` is not opened until something needs it.
    private val backend: Backend by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Encrypted(this.context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE), keyAlias, preferencesName, logger)
        } else {
            InMemory()
        }
    }

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) { backend.read(key) }

    override suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.IO) { backend.write(key, value) }

    override suspend fun clear(key: String): Unit = withContext(Dispatchers.IO) { backend.clear(key) }

    override fun toString(): String = "CnctKeystoreTokenStore($preferencesName, $keyAlias)"

    public companion object {
        public const val DEFAULT_PREFERENCES_NAME: String = "ooo.doo.cnct.chat"
        public const val DEFAULT_KEY_ALIAS: String = "ooo.doo.cnct.chat.token-key"
    }
}

private interface Backend {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun clear(key: String)
}

/** Below API 23: held for the life of the process, written nowhere. */
private class InMemory : Backend {
    private val held = ConcurrentHashMap<String, String>()

    override fun read(key: String): String? = held[key]

    override fun write(key: String, value: String) {
        held[key] = value
    }

    override fun clear(key: String) {
        held.remove(key)
    }
}

@RequiresApi(Build.VERSION_CODES.M)
private class Encrypted(
    private val preferences: SharedPreferences,
    private val keyAlias: String,
    private val preferencesName: String,
    private val logger: CnctLogger?,
) : Backend {
    override fun read(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        return try {
            Vault.open(keyAlias, stored, boundTo = key)
        } catch (error: GeneralSecurityException) {
            forget(key, error)
        } catch (error: IllegalArgumentException) {
            forget(key, error)
        }
    }

    override fun write(key: String, value: String) {
        val sealed = Vault.seal(keyAlias, value, boundTo = key)
        if (!preferences.edit().putString(key, sealed).commitNow()) {
            throw IOException("Could not save the chat session to \"$preferencesName\".")
        }
    }

    override fun clear(key: String) {
        preferences.edit().remove(key).commitNow()
    }

    private fun forget(key: String, error: Exception): String? {
        logger?.log(
            CnctLogLevel.WARNING,
            "A stored chat session could not be decrypted and was dropped; the visitor will start afresh.",
            error,
        )
        preferences.edit().remove(key).commitNow()
        return null
    }

    // `commit` rather than `apply`: this already runs on the IO dispatcher, and a caller that has
    // been told the token is kept should be able to rely on it being on disk.
    @SuppressLint("ApplySharedPref")
    private fun SharedPreferences.Editor.commitNow(): Boolean = commit()
}

/**
 * The cryptography, and nothing else. AES-256 in GCM, a fresh IV for every write (the Keystore insists
 * on choosing it), and the storage key as associated data so a ciphertext only opens where it was
 * written. Stored as `v1:<iv>:<ciphertext>`, so a future format can tell itself apart.
 */
@RequiresApi(Build.VERSION_CODES.M)
private object Vault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT = "v1"
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    private val lock = Any()

    fun seal(alias: String, plaintext: String, boundTo: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias, create = true))
        cipher.updateAAD(boundTo.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$FORMAT:${encode(cipher.iv)}:${encode(sealed)}"
    }

    fun open(alias: String, stored: String, boundTo: String): String {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == FORMAT) { "Not a sealed token." }
        val key = key(alias, create = false) ?: throw GeneralSecurityException("The Keystore key is gone.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(parts[1])))
        cipher.updateAAD(boundTo.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(decode(parts[2])).toString(Charsets.UTF_8)
    }

    /** The key under [alias], made the first time it is needed. Locked so two first writes make one. */
    private fun key(alias: String, create: Boolean): SecretKey? = synchronized(lock) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
