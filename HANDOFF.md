# HANDOFF — arinome camera app

Current implementation handoff for the next agent. Updated 2026-08-25 after the arinome identity/release cleanup.

Read `AGENTS.md` first; it contains permanent architecture and change rules.

## Current identity

- App: **arinome**
- Maintainer: **whirlxd**
- Application ID: `com.whirlxd.arinome`
- Kotlin packages: `com.whirlxd.arinome`, `.fragments`, `.utils`
- License: Apache-2.0, inherited and retained
- Upstream credit: `reilandeubank/unprocess` and Google Camera2Basic

Do not reset to the old `474b632` checkpoint: it predates all current WB, focus, performance, release, icon, package, and branding work.

## Current feature state

- Direct permissions → camera launch; no selector/viewer flow.
- RAW DNG and direct HAL JPEG toggle.
- Main/ultrawide lens chips, 35 mm-equivalent labels, in-place switching.
- Lens switch: 90 ms black fade-in, hidden camera re-open, 70 ms preview allowance, 150 ms fade-out.
- ISO/shutter preset pills and PRO sliders.
- Manual focus: quadratic slider, immediate request updates, fixed-focus lens guard.
- Tap focus reticle without AF triggers.
- WB: advertised Camera2 preset icons. The CPH2661 ignores advertised preset modes, so non-AUTO modes use cached AUTO `RggbChannelVector` + `ColorSpaceTransform`; preview and still requests share this state.
- Catppuccin Mocha/Macchiato/Frappe/Latte palettes.
- Immediate haptics for shutter, ISO, and shutter pills; detent ticks on PRO sliders.
- Capture acknowledgement: centered Phosphor check in shutter, immediate 900 ms fade cycle. No viewfinder flash.
- Captures remain in camera and save asynchronously to `DCIM/Camera`.
- JPEG EXIF software: `arinome`; DNG description: `shot through arinome`.
- Supplied arinome launcher icons integrated for all densities, adaptive icons, round icons, and Play Store artwork.

## Performance/cleanup state

Removed:

- Rejected shared-pill/circular-reveal motion systems.
- Per-frame theme drawable/tint recreation.
- Full-screen capture flash/blink.
- RAW→temporary DNG→Bitmap→JPEG branch and `convert_to_jpeg` nav argument.
- Glide and kapt.
- Unused ViewPager2 and ConstraintLayout dependencies.
- Unused RenderScript YUV converter/YUV helper.
- Unused utils AppCompat/RecyclerView/coroutines dependencies.
- Unused legacy utils shutter selector/drawables.
- Obsolete launcher foreground vector.
- Debug signing from release builds.

Current performance controls:

- Chip restyling caches selection/palette.
- Camera control requests are coalesced to ~30 Hz, except focus and WB taps.
- AUTO WB metadata reads are throttled to every 16 frames after initial calibration.
- Old `ImageReader`s close during lens switches.
- Motion modifies one property or drawable concern at a time.

## Release/CI status

`.github/workflows/android.yml` is present:

- Push/PR: clean checkout, public Gradle 8.9 distribution, debug build, APK artifact.
- `v*` tag: validates signing secrets, decodes keystore, overrides version from tag/run number, builds signed release, creates GitHub release with APK and SHA-256.

Required GitHub secrets:

- `ARINOME_KEYSTORE_BASE64`
- `ARINOME_KEYSTORE_PASSWORD`
- `ARINOME_KEY_ALIAS`
- `ARINOME_KEY_PASSWORD`

Local wrapper remains `file:///D:/codes/wyswg-cam/unprocess/gradle-8.9-bin.zip`; workflow rewrites only its checkout to the public URL.

## Build/deploy

```powershell
cd D:\codes\wyswg-cam\unprocess
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat clean assembleDebug --console=plain
adb -s aafd5c1 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s aafd5c1 shell am start -n com.whirlxd.arinome/.CameraActivity
```

Latest clean debug build, release build, and package-identity unit test after cleanup: **successful**.

Because the application ID changed, Android treats arinome as a separate app from the old `com.reilandeubank.unprocess` installation. Do not uninstall the old package without the user explicitly requesting it; photos are in shared `DCIM/Camera`, but app preferences are package-scoped.

## Known risks / next manual checks

- Launching behind the lock screen can still trigger the inherited `Surface was abandoned` race. Wake/unlock first.
- USB connection for `aafd5c1` is intermittent.
- GitHub tag releases require signing secrets before first use.
- Android deprecation warnings remain for legacy system UI/insets, camera session creation, and permission callbacks. They compile and are not current behavior regressions.
- After identity cutover, verify launcher icon, installed label, direct launch, RAW/JPEG capture, focus, WB presets, lens crossfade, EXIF/DNG branding, and immediate shutter check on-device.

## User preferences

- Minimal camera-first UI; no status clutter or viewer screen.
- No EV row.
- RAW softness is intentional.
- Keep Catppuccin palettes visibly distinct.
- Avoid web-like fades/bounces, animation frameworks, dependency growth, and large rewrites.
- Prefer small verified iterations and clean cutovers without compatibility aliases.
