package com.contextsolutions.mobileagent.ui.branding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

/** Android: the PNG bundled as a JVM classpath resource (`androidMain/resources`
 *  is packaged into the APK), loaded via the classloader — same approach as the
 *  desktop actual, sidestepping the KMP-library plugin's missing `R`/aapt. */
@Composable
actual fun appLogoPainter(): Painter = remember {
    BitmapPainter(
        object {}.javaClass.getResourceAsStream("/app_logo.png")!!
            .use { it.readBytes().decodeToImageBitmap() },
    )
}
