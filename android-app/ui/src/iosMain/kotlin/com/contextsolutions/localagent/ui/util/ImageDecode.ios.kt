package com.contextsolutions.localagent.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * iOS actual (PR #41): decode via Skia ([Image.makeFromEncoded]) and convert to a
 * Compose [ImageBitmap] — identical to the desktop actual (Skiko ships with CMP
 * on iOS). Returns null on any decode failure.
 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
        .getOrNull()
