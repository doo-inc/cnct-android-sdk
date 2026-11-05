package ooo.doo.cnct

import java.util.concurrent.ConcurrentHashMap

/**
 * Where a visitor's chat session token is kept between app launches.
 *
 * The token is a thirty-day bearer secret scoped to one conversation. Held, the same person comes
 * back to the same thread with their history intact; lost, they are a stranger who has to start
 * again — which is not a failure, just a worse experience.
 *
 * **The default is memory, and that is deliberate.** This artifact has no Android dependency, so it
 * cannot reach `SharedPreferences` or the Keystore itself; an Android app passes a store that can.
 * Implementing one is three functions.
 */
public interface CnctTokenStore {
    public suspend fun read(key: String): String?

    public suspend fun write(key: String, value: String)

    public suspend fun clear(key: String)

    public companion object {
        /**
         * Keeps nothing across a restart. Correct for a test, a server and a kiosk; wrong for
         * anything a person comes back to.
         */
        public fun memory(): CnctTokenStore = MemoryTokenStore()
    }
}

private class MemoryTokenStore : CnctTokenStore {
    private val held = ConcurrentHashMap<String, String>()

    override suspend fun read(key: String): String? = held[key]

    override suspend fun write(key: String, value: String) {
        held[key] = value
    }

    override suspend fun clear(key: String) {
        held.remove(key)
    }
}
