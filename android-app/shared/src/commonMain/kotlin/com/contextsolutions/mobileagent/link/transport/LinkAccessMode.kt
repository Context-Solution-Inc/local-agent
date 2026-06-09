package com.contextsolutions.mobileagent.link.transport

import kotlinx.serialization.Serializable

/**
 * How the phone reaches the paired desktop. Derived from which QR was scanned:
 * the LAN `magent://link` URI ([LAN]) or the Secure Gateway relay QR ([RELAY],
 * requires an active subscription). LAN is the default and the fallback.
 */
@Serializable
enum class LinkAccessMode { LAN, RELAY }
