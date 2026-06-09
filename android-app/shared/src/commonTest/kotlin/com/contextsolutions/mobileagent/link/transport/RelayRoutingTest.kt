package com.contextsolutions.mobileagent.link.transport

import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** P4 routing: relay-QR detection, config gating, and LAN/relay transport selection. */
class RelayRoutingTest {

    private val relayQr = """
        {"v":1,"pairing_token":"tok123","desktop_pubkey":"pk","desktop_device_id":"dev-d",
         "endpoints":{"relay":"wss://gw/v1/connect","auth":"https://gw"},"account_secret":"sek"}
    """.trimIndent()

    @Test
    fun detectsRelayQrAndIgnoresLanAndJunk() {
        val relay = RelayQrPayload.parseOrNull(relayQr)
        assertTrue(relay != null && relay.isRelayQr)
        assertEquals("sek", relay.accountSecret)
        assertEquals("dev-d", relay.desktopDeviceId)

        assertNull(RelayQrPayload.parseOrNull("magent://link?h=1.2.3.4&p=47215&t=tok&d=dev"))
        assertNull(RelayQrPayload.parseOrNull("""{"v":1,"endpoints":{}}""")) // no token/relay
        assertNull(RelayQrPayload.parseOrNull("not json"))
    }

    @Test
    fun configGatesByAccessMode() {
        val lan = DesktopLinkConfig(enabled = true, peerHost = "1.2.3.4", peerPort = 47215, pairingToken = "t")
        assertTrue(lan.isLinkConfigured && !lan.isRelayConfigured)

        val relay = DesktopLinkConfig(enabled = true, accessMode = LinkAccessMode.RELAY, relayQrJson = relayQr)
        assertTrue(relay.isPaired && relay.isLinkConfigured && relay.isRelayConfigured)
        assertNull(relay.baseUrl()) // relay has no LAN base URL

        val relayOff = relay.copy(enabled = false)
        assertTrue(!relayOff.isLinkConfigured && !relayOff.isRelayConfigured)
    }

    private class FakePrefs(private var cfg: DesktopLinkConfig) : DesktopLinkPreferences {
        private val flow = MutableStateFlow(cfg)
        override fun config() = cfg
        override fun configFlow(): Flow<DesktopLinkConfig> = flow
        override fun setConfig(config: DesktopLinkConfig) { cfg = config; flow.value = config }
    }

    private class FakeTransport(override val target: String) : LinkTransport {
        override suspend fun unary(request: LinkRequest) = LinkResponse(200, "")
        override fun serverStream(request: LinkRequest): Flow<LinkStreamEvent> =
            kotlinx.coroutines.flow.flow { emit(LinkStreamEvent.End(200)) }
    }

    @Test
    fun providerReturnsLanWhenLanConfiguredAndNullWhenUnconfigured() = runTest {
        val lan = FakeTransport("lan")

        val unconfigured = DefaultLinkTransportProvider(FakePrefs(DesktopLinkConfig()), lan)
        assertNull(unconfigured.current())

        val lanCfg = DesktopLinkConfig(enabled = true, peerHost = "1.2.3.4", peerPort = 47215, pairingToken = "t")
        val provider = DefaultLinkTransportProvider(FakePrefs(lanCfg), lan)
        assertSame(lan, provider.current())
    }

    @Test
    fun providerReturnsNullForRelayUntilPipeConnects() = runTest {
        // No relay factory ⇒ relay-configured but no pipe ⇒ current() is null (→ local fallback).
        val relayCfg = DesktopLinkConfig(enabled = true, accessMode = LinkAccessMode.RELAY, relayQrJson = relayQr)
        val provider = DefaultLinkTransportProvider(FakePrefs(relayCfg), FakeTransport("lan"))
        assertNull(provider.current())
    }
}
