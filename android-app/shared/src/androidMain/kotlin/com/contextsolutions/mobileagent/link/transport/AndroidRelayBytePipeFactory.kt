package com.contextsolutions.mobileagent.link.transport

import android.content.Context
import com.securegateway.core.auth.QrPayload
import com.securegateway.mobile.MobileConfig
import com.securegateway.mobile.SecureGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a connected [AndroidRelayBytePipe] from a scanned relay QR. Parses the QR
 * for the auth/relay endpoints + account secret, pairs the mobile SDK, wires the
 * byte-pipe callbacks, then connects. The X25519 identity persists across re-launch via
 * [AndroidKeystoreKeyStore] — an androidx EncryptedFile under a hardware-backed Android
 * Keystore master key (CLAUDE.md #55; AndroidKeyStore can't hold raw X25519 directly).
 */
class AndroidRelayBytePipeFactory(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = {},
) : RelayBytePipeFactory {

    override suspend fun create(relayQrJson: String): RelayBytePipe? = withContext(ioDispatcher) {
        runCatching {
            val qr = QrPayload.fromJson(relayQrJson)
            val config = MobileConfig().apply {
                authUrl = qr.authEndpoint() ?: error("relay QR missing auth endpoint")
                relayUrl = qr.relayEndpoint()
                accountSecret = qr.accountSecret
                keyStore = AndroidKeystoreKeyStore(context)
            }
            val client = SecureGateway.mobile(config)
            client.pair(qr)
            val pipe = AndroidRelayBytePipe(client) // wires callbacks before connect()
            client.connect()
            pipe
        }.getOrElse {
            logger("relay pairing/connect failed: ${it.message}")
            null
        }
    }
}
