package com.contextsolutions.localagent.platform

/**
 * Swift→Kotlin bridge for rendering a LaTeX formula to a raster image on iOS (PR #46).
 * The Swift app implements this with SwiftMath (`MTMathImage` → `UIImage` → PNG) and
 * registers the instance into Koin via `doInitKoin(latexRenderer = …)`. The iOS
 * [PlatformMarkdownMath][com.contextsolutions.localagent.ui.markdown] actual is the sole
 * consumer — it splits an assistant answer into markdown / `$$…$$` math segments (the
 * shared `splitMarkdownAndMath`) and rasterizes each math segment through this bridge, the
 * native counterpart of desktop's JVM JLaTeXMath path (invariant #41). Mirrors the
 * NativeQrScanner / NativeLlmBridge convention so no UIKit types leak into commonMain (#23).
 *
 * SYNCHRONOUS + main-thread: a LaTeX raster is light and UIKit-bound, and Compose-iOS
 * composition already runs on the main thread, matching the desktop path that renders
 * bitmaps synchronously inside `remember`. (Contrast with the async-callback LLM/ONNX
 * bridges, whose inference is heavy + off-main.)
 */
interface NativeLatexRenderer {
    /**
     * Render [latex] at [fontSize] points in the ARGB [argbColor]. Returns null on a
     * malformed formula (the caller then shows the raw LaTeX as inline code) — never throws.
     */
    fun render(latex: String, fontSize: Float, argbColor: Int): LatexImage?
}

/**
 * A rendered LaTeX raster. [png] is encoded at device scale for crisp glyphs; [widthPt]
 * and [heightPt] are the LOGICAL size in points so the Compose `Image` is sized
 * deterministically rather than relying on Compose-iOS `LocalDensity == UIScreen.scale`.
 */
data class LatexImage(val png: ByteArray, val widthPt: Double, val heightPt: Double) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LatexImage) return false
        return widthPt == other.widthPt && heightPt == other.heightPt && png.contentEquals(other.png)
    }

    override fun hashCode(): Int {
        var result = png.contentHashCode()
        result = 31 * result + widthPt.hashCode()
        result = 31 * result + heightPt.hashCode()
        return result
    }
}
