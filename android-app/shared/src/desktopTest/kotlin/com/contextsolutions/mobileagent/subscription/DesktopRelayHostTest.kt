package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR #90 — the persisted "peer paired" marker keeps "Mobile agent offline" + Disconnect
 * visible while a paired phone is away, including across a desktop restart, and clears on
 * a desktop-initiated Disconnect. (The UP/REVOKED transitions run inside the relay
 * collector over a live `DesktopClient` — exercised by manual end-to-end testing.)
 */
class DesktopRelayHostTest {

    @Test
    fun peerPairedSeededFalseWhenNoMarker() {
        val host = newHost(FakeSecureStorage())
        assertFalse(host.peerPaired.value)
    }

    @Test
    fun peerPairedSeededTrueFromPersistedMarker() {
        // Simulates a desktop restart while a previously-paired phone is offline.
        val store = FakeSecureStorage().apply { put(SecureStorageKeys.RELAY_PEER_PAIRED, "1") }
        val host = newHost(store)
        assertTrue(host.peerPaired.value)
    }

    @Test
    fun disconnectClearsTheMarker() = runBlocking {
        val store = FakeSecureStorage().apply { put(SecureStorageKeys.RELAY_PEER_PAIRED, "1") }
        val host = newHost(store)
        assertTrue(host.peerPaired.value)

        host.disconnect()

        assertFalse(host.peerPaired.value)
        assertFalse(store.contains(SecureStorageKeys.RELAY_PEER_PAIRED))
    }

    private fun newHost(store: SecureStorage) = DesktopRelayHost(
        prefs = NoOpSubscriptionPreferences(),
        secureStorage = store,
        gatewayBaseUrl = "http://localhost",
        relayWsUrl = "ws://localhost",
        keyStorePath = Files.createTempFile("relay_identity", ".key"),
    )

    private class FakeSecureStorage : SecureStorage {
        private val map = HashMap<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
    }
}
