# iosApp — staging only (Phase 2)

There is **no Xcode project here yet.** iOS is stubbed for Phase 2
(`shared/src/iosMain`). This directory stages assets the future iOS app can
adopt as-is.

- `Assets.xcassets/AppIcon.appiconset/` — the app launcher icon
  (`icon-1024.png`, opaque 1024×1024, single-size "universal" iOS app icon for
  Xcode 14+). When the iOS app target is created, point its
  `ASSETCATALOG_COMPILER_APPICON_NAME` / `Assets.xcassets` at this catalog.

Regenerate the icon from the shared brand artwork with
`desktopApp/icons/generate_icons.py` (see that script's header).
