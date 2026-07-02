import Foundation
import UIKit
import ComposeApp
// SwiftMath renders LaTeX math to a UIImage via CoreText (no WebView). Add via Xcode →
// Add Package Dependencies:  https://github.com/mgriebling/SwiftMath  (pin to a released
// tag, like the LiteRT-LM / ORT packages). Add the `SwiftMath` library product to the
// iosApp target. SwiftMath bundles its math fonts (latinmodern-math, …) in its own SPM
// resource bundle, so no Copy-Bundle-Resources step is needed. It is pure Swift/CoreText
// with no vendored C deps, so it coexists cleanly under LiteRT-LM's `-all_load`
// (ComposeApp stays a DYNAMIC framework, invariant #78). See docs/IOS_BUILD.md.
import SwiftMath

// Swift implementation of the Kotlin `NativeLatexRenderer` (PR #46): rasterize a LaTeX
// formula to a PNG for the iOS `PlatformMarkdownMath` actual, the native counterpart of
// desktop's JLaTeXMath path (invariant #41). Registered into Koin by `iOSApp.init`.
//
// SCAFFOLD (invariant #78): the exact SwiftMath API (`MTMathImage` vs an `MTMathUILabel`
// + `UIGraphicsImageRenderer` rasterize) is version-sensitive and can only be finalized +
// verified in Xcode on a device (CI is compile-only). The Kotlin contract — latex +
// fontSize + ARGB colour → PNG bytes + logical point size — is stable.
final class LatexBridge: NativeLatexRenderer {

    func render(latex: String, fontSize: Float, argbColor: Int32) -> LatexImage? {
        // MTMathImage rasterizes on the main thread (UIKit/CoreText). Compose-iOS calls
        // this from the main thread already, so a synchronous render is safe (matching the
        // desktop path that renders inside `remember`).
        let mathImage = MTMathImage(
            latex: latex,
            fontSize: CGFloat(fontSize),
            textColor: Self.color(argbColor),
            labelMode: .display   // matches desktop's STYLE_DISPLAY
        )
        let (error, image) = mathImage.asImage()
        guard error == nil, let uiImage = image, let png = uiImage.pngData() else { return nil }
        // uiImage.size is in POINTS; the PNG is encoded at uiImage.scale (device scale) for
        // crisp glyphs. Returning the point size lets Compose size the Image deterministically.
        return LatexImage(
            png: KotlinByteArray.from(png),
            widthPt: Double(uiImage.size.width),
            heightPt: Double(uiImage.size.height)
        )
    }

    /// ARGB Int (from Compose `Color.toArgb()`) → UIColor.
    private static func color(_ argb: Int32) -> UIColor {
        let v = UInt32(bitPattern: argb)
        return UIColor(
            red: CGFloat((v >> 16) & 0xFF) / 255.0,
            green: CGFloat((v >> 8) & 0xFF) / 255.0,
            blue: CGFloat(v & 0xFF) / 255.0,
            alpha: CGFloat((v >> 24) & 0xFF) / 255.0
        )
    }
}

// Data → KotlinByteArray (same per-element fill idiom as OnnxRuntimeBridge's KotlinFloatArray).
// LaTeX rasters are tiny, so the element-wise copy is fine.
private extension KotlinByteArray {
    static func from(_ data: Data) -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(data.count))
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let p = raw.bindMemory(to: Int8.self)
            for i in 0..<data.count { out.set(index: Int32(i), value: p[i]) }
        }
        return out
    }
}
