# HANDOFF — unprocess camera app

Recap for the next agent. Written 2026-08-25 after the "motion system" pass was
rejected as spaghetti and reverted. Repo reset target: `474b632`
(see commit map below).

## What this app is

Minimal RAW-first camera app ("unprocess", Catppuccin-styled, zero-cam
inspired). Package `com.reilandeubank.unprocess`. Opens straight into the
viewfinder — no selector screen. Captures save to `DCIM/Camera` and the camera
stays ready for the next shot.

## Commit map

| Commit | Contents |
|---|---|
| `b3dbd8f` | History root (rebuilt from shallow clone; "Update README.md") |
| `0e4d1f5` | "upgrades and fix jpg bug" (user's original work) |
| `6a7c0e5` | UI overhaul: direct-to-camera flow, chip UI, Catppuccin Mocha, JetBrains Mono, aperture icon |
| `44f86cd` | WB attempt (gains + FAST), AF-trigger removal, 35mm-equiv lens labels, 4 themes, pro sliders, shutter dot |
| `630f161` | EV row removed, focus/WB sliders, animations (weak), inset chrome, non-wrapping top bar |
| `474b632` | DNG watermark wording "shot through unprocess" — **last known-good, likely reset target** |
| `a52b39e` | (pre-motion) top-bar pill, WB cast fix attempt, slider sync, metadata stamp |
| `bac5d1b` | **REJECTED** motion-system implementation — do not cherry-pick; use its ideas only via the spec below |

## Architecture

- `CameraActivity` (AppCompatActivity) hosts a NavHostFragment.
  Nav graph: `permissions_fragment` → `camera_fragment` directly.
  CameraFragment nav args are OPTIONAL: `camera_id` nullable (null → app picks
  best back RAW camera), `pixel_format` default `32` (ImageFormat.RAW_SENSOR),
  `convert_to_jpeg` default false.
- `CameraFragment.kt` is a ~1600-line god-file: camera2 pipeline, all UI
  wiring, theming, capture/save. **Fragile under line-anchored edits — the
  previous agent repeatedly corrupted it. Re-read before every edit; prefer
  replacing whole functions or rewriting the file.**
- `utils` module: `AutoFitSurfaceView`, `CameraSizes` (getPreviewOutputSize),
  `OrientationLiveData`, `ExifUtils`. `Yuv*` files are unused legacy.
- Layouts: `layout/fragment_camera.xml` and `layout-land/fragment_camera.xml`
  are **identical files** — keep ViewBinding IDs in sync (cp one to the other).
- ViewBinding only (no Compose). minSdk 21, target/compileSdk 34.
- Gradle wrapper points at `file:///D:/codes/wyswg-cam/unprocess/gradle-8.9-bin.zip`
  — do not delete that zip.

## Feature state at `474b632` (works)

- Direct-to-camera launch; capture stays in camera; async save; no viewer.
- Lens chips labeled by 35mm-equivalent ratio (main ≈26mm = "1X"), deduped,
  RAW-capable preferred; in-place lens switching (close session/camera, reopen).
- Chip rows: ISO / SHUTTER / WB presets; PRO switch reveals sliders
  (ISO 50–6400 log, shutter 1/30–1/4000 log, WB 2500–8000K, focus diopter).
  Two-way chip↔slider sync with tolerance matching (ISO/shutter ±0.18 ln,
  WB ±150K). `simpleSeek` MUST ignore `!fromUser` or chip→slider sync feeds
  back and breaks highlights.
- Pinch zoom (CONTROL_ZOOM_RATIO, API R+), tap-to-focus via metering regions
  on CONTINUOUS_PICTURE — **never send AF triggers** (a START followed by a
  quick IDLE wedges the lens mid-sweep; that was the original "stuck focus").
- Catppuccin palettes (Mocha/Macchiato/Frappe/Latte) with distinct accents
  (yellow/mauve/green/blue), runtime-themed, persisted in SharedPreferences
  ("ui" / "palette"). `applyTheme()` restyles chrome; chip rows restyled via
  per-row closures if you keep that pattern.
- JetBrains Mono in `res/font`; theme applies it globally.
- Metadata: JPEGs get `ExifInterface.TAG_SOFTWARE = "unprocess"` via
  file-descriptor rewrite after MediaStore insert (wrapped in try/catch —
  some OEMs reject post-insert rewrites; original bytes survive). DNGs get
  `DngCreator.setDescription("shot through unprocess")`.
- Inset-aware chrome (top/bottom bars pad by systemWindowInset).

## Known open bugs at `474b632` (fix these first)

1. **Manual WB produces blue/green casts.** Current impl: `AWB OFF` +
   `COLOR_CORRECTION_GAINS` with `COLOR_CORRECTION_MODE_FAST` — HAL ignores
   app gains under FAST, and the old Kelvin→RGB→Bayer formula was nonsense.
   The user supplied a fix spec (originally `local://paste-1.md`), summarized:
   - AUTO (wbKelvin == 0): keep `AWB AUTO` + `FAST`.
   - API 36+ AND `COLOR_CORRECTION_MODE_CCT` (=3) advertised: use
     `AWB OFF` + `MODE_CCT` + `COLOR_CORRECTION_COLOR_TEMPERATURE` (clamped
     to the advertised range) + tint 0.
   - Otherwise: map Kelvin → nearest advertised `CONTROL_AWB_AVAILABLE_MODES`
     preset: <3500 INCANDESCENT, <4000 WARM_FLUORESCENT, <4500 FLUORESCENT,
     <5600 DAYLIGHT, <6500 CLOUDY_DAYLIGHT, else SHADE. Never synthesize
     RGGB gains. Same path for preview and still via `applyState()`.
   - Keep the existing chips/slider UI untouched; wbKelvin==0 means AUTO.
   Expected: 2500K cool/blue → 5200K neutral → 8000K warm/orange in daylight.
2. **Manual focus bugs.** Device facts (OnePlus CPH2661, API 36):
   main lens `minimumFocusDistance = 10.0D` (APPROXIMATE calibration),
   ultrawide `0.0` (fixed focus). Bugs: `focusDiopter` persists across lens
   switch (forces a diopter onto the fixed-focus ultrawide), and the linear
   0–10D slider is hyper-sensitive near infinity. Fix: reset focusDiopter +
   slider UI on lens switch; skip `LENS_FOCUS_DISTANCE` when
   `minimumFocusDistance <= 0`; quadratic slider curve (`maxFocus * t²`).
3. **Crash: `refreshPreview()` NPEs/ISEs when camera is closed.**
   `camera.createCaptureRequest(...)` sits outside the try and there is no
   liveness guard; tapping a chip after the camera closes (screen off, lock
   screen, camera disconnected) throws "CameraDevice was already closed".
   Fix: guard `if (!::camera.isInitialized || !::session.isInitialized) return`
   and move request creation inside the try. Apply the same guard to the
   capture-button listener.

## Motion system spec (user-approved, implement cleanly)

Rejected the first implementation; the design itself is approved. Primitives:
**Snap** (chips compress 0.96 on touch, settle with low-bounce spring),
**Glide** (a shared selection pill physically travels between related
choices — not per-chip background swaps), **Unfold** (panel reveals via
bottom-up clipping + small translation + slight scale, ~240ms),
**Aperture** (circular close/open mask reserved for lens switching).
Motion 60–260ms; closing faster than opening; opacity almost never primary;
no bounce excess/rotations/pulsing/glow. Catppuccin is the expressive layer.

Per element:
- Chips: shared sliding pill + 0.96 press compression. (Implementation idea
  from the rejected pass: wrap each chip row in a FrameLayout with a pill
  View behind the chips; animate pill translationX/width to the selected
  chip. Register per-row restylers so theme animation can recolor cheaply.)
- Control panel: bottom-up clipped unfold ~240ms (animate `clipBounds` from a
  bottom sliver to full + 16dp rise + scale 0.985→1; note GONE views measure 0 —
  set VISIBLE first and `post` the animation).
- PRO rows: 20–30ms rise-in stagger.
- Slider thumb: grows while dragging (swap GradientDrawable oval 16→22dp),
  CLOCK_TICK haptic when the thumb crosses chip-equivalent detents.
- Numeric readouts: 4dp vertical roll ~100ms (out down, swap, in from top).
- Focus: reticle (56dp accent ring) contracts 1.5→1.0 into the tap point,
  one lock pulse (1.12), VIRTUAL_KEY haptic; reticle view must be
  `invisible` (not `gone`) so it measures.
- Capture: shutter compression + very brief exposure blink (existing white
  overlay flash is fine).
- Lens switching: aperture-close/open via `ViewAnimationUtils.createCircularReveal`
  on a black overlay (close 170ms → switch → open 220ms).
- Theme changes: interpolate palette colors (ArgbEvaluator, ~220ms) without
  moving layout; restyle chrome per frame.
- Never animate viewfinder exposure/WB changes.

## Build / deploy / test

```
cd D:/codes/wyswg-cam/unprocess
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" MSYS_NO_PATHCONV=1 \
  cmd /c "gradlew.bat assembleDebug --console=plain"
ADB="C:/Users/whirl/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s aafd5c1 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s aafd5c1 shell am start -n com.reilandeubank.unprocess/.CameraActivity
```
- `./gradlew` does not work (win32); use gradlew.bat via cmd as above.
- Device `aafd5c1`: OnePlus CPH2661, Android 16 (API 36), 1240x2772 @ 560dpi
  (density 3.5). USB is flaky — device drops off adb regularly; poll with
  `adb devices`, wake + `wm dismiss-keyguard` before launching (launching
  behind the lock screen crashes the old surface race).
- UI automation: `uiautomator dump /sdcard/ui.xml` then parse bounds for tap
  targets. Shutter center ≈ (620, 2590) in portrait.
- Camera characteristics: `adb shell dumpsys media.camera > dump.txt` then
  grep the file (grep on the pipe returns nothing). Known values:
  colorCorrection.availableModes `[0 1 2]` (NO CCT on this device),
  control.awbAvailableModes `[1 2 3 4 5 6 7 8 0]`,
  lens.info.minimumFocusDistance: main 10.0D, ultrawide 0.0.
- Verify EXIF stamp: pull a JPEG, `PIL.Image._getexif()`, expect
  `Software: unprocess` with Make/Model/ISO/Exposure intact.

## API-level gotchas (compileSdk 34)

- `CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES` is NOT in the
  public android-34 stubs — fetch via reflection:
  `Class.forName("android.hardware.camera2.CameraCharacteristics$Key")`
  `.getConstructor(String, Class)` (see `characteristicsKey` in bac5d1b).
- API-36 constants (`COLOR_CORRECTION_MODE_CCT`=3,
  `android.colorCorrection.colorTemperature` request key) also need
  reflection + `Build.VERSION.SDK_INT >= 36` guards, wrapped in try/catch
  with preset fallback.
- `ColorSpaceTransform` 9-arg constructor does not resolve from Kotlin with
  this toolchain — use the `intArrayOf(...)` (18-int) constructor if ever
  needed. (Current spec says: don't use transforms at all.)
- `ExifInterface.saveAttributes()` works via `openFileDescriptor(uri, "rw")`;
  there is no `writeExif` API.

## User preferences (learned the hard way)

- Commit before starting any new batch of work; user commits checkpoints.
- Minimal UI: no status text in the top bar; wordmark pill left, theme +
  RAW/JPEG chips pinned right; errors as toasts only.
- No EV compensation row (removed — user didn't understand it).
- Themes must be visibly distinct: Mocha=yellow, Macchiato=mauve,
  Frappe=green, Latte=blue-on-light.
- RAW looking softer than JPEG is accepted (that's the app's point); don't
  "fix" it.
- User tests manually over adb after each batch; keep builds installable and
  verify launch + capture over adb before handing back.
