package com.contextsolutions.localagent.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus
import platform.posix.memcpy

/**
 * iOS [SecureStorage] (PR #41) — the system **Keychain** (`kSecClassGenericPassword`),
 * the iOS counterpart of Android Keystore-AES-GCM / desktop PKCS#12. Each secret is
 * one keychain item keyed by (service = bundle id, account = [key]); the value is
 * stored as `kSecValueData`. Items are `AccessibleAfterFirstUnlock` so a background
 * relaunch can read them. Holds the optional Ollama / Brave API keys; never written
 * to plaintext disk (PRD §4.4).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStorage(
    private val service: String = "com.contextsolutions.localagent",
) : SecureStorage {

    override fun put(key: String, value: String) {
        // Upsert = delete-then-add (simplest correct path; secret writes are rare).
        remove(key)
        val data = value.encodeToByteArray().toNSData()
        val cfData = CFBridgingRetain(data)
        val query = newQuery()
        CFDictionaryAddValue(query, kSecAttrAccount, key.toCFString())
        CFDictionaryAddValue(query, kSecValueData, cfData)
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        SecItemAdd(query, null)
        CFRelease(query)
        cfData?.let { CFBridgingRelease(it) }
    }

    override fun get(key: String): String? = memScoped {
        val query = newQuery()
        CFDictionaryAddValue(query, kSecAttrAccount, key.toCFString())
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status: OSStatus = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return@memScoped null
        val nsData = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        nsData.toByteArray().decodeToString()
    }

    override fun remove(key: String) {
        val query = newQuery()
        CFDictionaryAddValue(query, kSecAttrAccount, key.toCFString())
        SecItemDelete(query)
        CFRelease(query)
    }

    override fun contains(key: String): Boolean = get(key) != null

    /** A fresh mutable query seeded with class + service (caller adds account etc.). */
    private fun newQuery(): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, service.toCFString())
        return dict
    }

    private fun String.toCFString(): CFStringRef? =
        CFBridgingRetain(this as platform.Foundation.NSString)?.reinterpret()
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
