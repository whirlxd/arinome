# arinome

A minimal camera for android stripping away the post processing from your manufacturer

arinome opens directly into the viewfinder and captures either DNG sensor data or a direct camera-HAL JPEG. It's useful especially if you have a chinese brand who likes to throttle and intentionally degrade cameras.

It is entirely based on camera2 api to ensure there isn't any interference and the produced output is as close to what you see when you shoot

## Features

- RAW DNG and direct HAL JPEG capture
- Main/ultrawide lens switching with 35 mm-equivalent labels
- ISO and shutter presets plus optional PRO sliders
- Quadratic manual-focus control and tap-to-focus
- Camera2 white-balance preset controls with a device-calibrated fallback for HALs that ignore their advertised modes
- Pinch zoom
- Catppuccin Mocha, Macchiato, Frappe, and Latte palettes



## Android 

- Minimum SDK: 21
- Compile/target SDK: 34


## Build

JDK 17 and Android SDK 34 are required.

Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.whirlxd.arinome/.CameraActivity
```

Linux/macOS and GitHub Actions:

```bash
./gradlew :app:assembleDebug --console=plain
```

The workstation wrapper points to the checked local Gradle 8.9 ZIP because that machine may not reach Gradle distribution servers. CI rewrites the wrapper URL in its isolated checkout.


## Project lineage and acknowledgement

arinome was forked from [`reilandeubank/unprocess`](https://github.com/reilandeubank/unprocess), which itself builds on Google's [`Camera2Basic`](https://github.com/android/camera-samples/tree/main/Camera2Basic) sample.

Only a small, barebones part of that upstream foundation remains at arinome's core: the Camera2 RAW capture/session structure and a handful of camera utilities. The application flow, interface, branding, RAW/JPEG selection, lens controls, focus behavior, white-balance handling, themes, motion, save experience, performance work, package identity, release assets, and automation have been substantially rewritten for arinome.

Thank you to the original `unprocess` author and the Android camera-samples contributors for the foundation.

## Icons

Launcher artwork is supplied by whirlxd under `icons/`. In-app white-balance and capture-check symbols use [Phosphor Icons](https://phosphoricons.com/) under the MIT License.

## License

arinome continues under the **Apache License 2.0**. See [`LICENSE`](LICENSE).

Existing upstream copyright and license headers are retained in inherited files. The acknowledgement above documents the project's origin; arinome is maintained by whirlxd.
