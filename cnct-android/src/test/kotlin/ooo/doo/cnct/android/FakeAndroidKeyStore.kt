package ooo.doo.cnct.android

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * An "AndroidKeyStore" for the JVM.
 *
 * Robolectric runs on a desktop JVM, which has no Android Keystore, so the store under test would
 * find no provider by that name. This registers one that keeps AES keys in a map and generates them
 * with the JVM's own AES — enough for the production code to run its real path, unchanged:
 * `KeyStore.getInstance("AndroidKeyStore")`, `KeyGenerator.getInstance("AES", "AndroidKeyStore")`
 * with a `KeyGenParameterSpec`, then GCM. What it cannot prove is how a real device's Keystore
 * behaves; that needs an instrumented test on a device.
 */
// The (String, double, String) constructor is the one android.jar and every JDK share; newer JDKs
// deprecate it for a String version.
@Suppress("DEPRECATION")
object FakeAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "In-memory stand-in for tests") {
    val secretKeys = ConcurrentHashMap<String, SecretKey>()

    /** The specs keys were generated with, so a test can check what the store asked for. */
    val specs = ConcurrentHashMap<String, KeyGenParameterSpec>()

    init {
        put("KeyStore.AndroidKeyStore", Store::class.java.name)
        put("KeyGenerator.AES", Generator::class.java.name)
    }

    /** Install, replacing whatever a previous sandbox left under the same name. */
    fun install() {
        Security.removeProvider(name)
        Security.addProvider(this)
        secretKeys.clear()
        specs.clear()
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = secretKeys[alias]

        override fun engineContainsAlias(alias: String): Boolean = secretKeys.containsKey(alias)

        override fun engineDeleteEntry(alias: String) {
            secretKeys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(secretKeys.keys.toList())

        override fun engineSize(): Int = secretKeys.size

        override fun engineIsKeyEntry(alias: String): Boolean = secretKeys.containsKey(alias)

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            secretKeys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    }

    class Generator : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("Needs a KeyGenParameterSpec")

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = params as KeyGenParameterSpec
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("Needs a KeyGenParameterSpec")

        override fun engineGenerateKey(): SecretKey {
            val spec = checkNotNull(spec) { "Not initialised" }
            val key = KeyGenerator.getInstance("AES", "SunJCE").apply { init(spec.keySize) }.generateKey()
            secretKeys[spec.keystoreAlias] = key
            specs[spec.keystoreAlias] = spec
            return key
        }
    }
}
