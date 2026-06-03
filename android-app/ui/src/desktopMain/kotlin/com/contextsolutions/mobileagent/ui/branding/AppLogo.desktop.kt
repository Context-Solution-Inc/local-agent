package com.contextsolutions.mobileagent.ui.branding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

/** Desktop: the PNG bundled as a classpath resource (same approach as the
 *  window/tray icon in desktopApp's `Main.kt`). */
@Composable
actual fun appLogoPainter(): Painter = remember {
    BitmapPainter(
        object {}.javaClass.getResourceAsStream("/app_logo.png")!!
            .use { it.readBytes().decodeToImageBitmap() },
    )
}
