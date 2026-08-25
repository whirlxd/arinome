<div align="center">

<img src="icons/playstore-icon.png" width="112" alt="arinome icon">

# arinome

### 有りのまま · *ari no mama*

**“as it is” · “in its natural state”**

A small, straightforward camera for Android that tries to give you the image your camera actually saw, *wyswg camera edition!*

<br>

[![Download arinome](https://img.shields.io/badge/Download-latest%20release-9CC0CA?style=for-the-badge\&labelColor=1e1e2e)](../../releases/latest)

<sub>Android 5.0 and newer · FOSS Forever</sub>

</div>

---

## Your Camera unlocked

Modern phone cameras do a lot after you press the shutter.

They sharpen, smooth skin, brighten shadows, push colours, reduce noise and sometimes change the image quite aggressively. This can be useful, but on some phones, particularly those with heavier manufacturer processing(chinese oems), the result is more often than not different from what you saw in the preview pane

**arinome strips that experience back.**

It opens directly into the viewfinder and captures either the camera's RAW sensor data or a JPEG produced directly through Android's camera system.

There are no beauty filters, scene optimisers, AI enhancements, lowlight processing or anything really.

<div align="center">

### Stock camera vs arinome

|                                        Stock camera                                       |                                       arinome                                      |
| :---------------------------------------------------------------------------------------: | :--------------------------------------------------------------------------------: |
| <img src="docs/stock-camera.jpg" width="420" alt="Photo captured using the stock camera"> | <img src="docs/arinome-camera.jpg" width="420" alt="Photo captured using arinome"> |
|                                  Manufacturer processing                                  |                                Direct camera output                                |



</div>

---

## What you get

arinome is lightweight asf coming just under 7~ mb, in that you get -

* **RAW DNG capture** for the closest access to your camera sensor's original data
* **Direct JPEG capture** without arinome adding its own image processing
* **Main and ultrawide cameras**, both supported!
* **Tap to focus** for everyday shooting
* **Manual focus** for macro shots or when you need finer control
* **ISO and shutter controls** with simple presets and optional PRO sliders
* **White balance controls** designed to work around phones that do not properly follow Android's advertised camera behaviour
* **Pinch to zoom**
* **Catppuccin themes** in Mocha, Macchiato, Frappé and Latte

The default experience remains simple: open arinome, **frame** and shoot.

---

<div align="center">

## Install

The easiest way to install arinome is through the latest GitHub release.

<br>

[![Get the APK](https://img.shields.io/badge/Get%20the%20APK-GitHub%20Releases-cba6f7?style=for-the-badge\&labelColor=181825)](../../releases/latest)

<br>

Download the latest `.apk`, open it on your Android device and follow Android's installation prompt.

<sub>You may need to allow installation from your browser or file manager the first time.</sub>

</div>

---

## Why “arinome”?

The name comes from the Japanese expression **有りのまま — *ari no mama***.

It roughly describes something **as it is**, **as it really exists**, or **in its natural state**.

That is the idea really is simple - what you is what you get : camera edition

The name is compressed into **arinome** as its own identity rather than being used as a literal transliteration.

---

## Caveat(s)

Android cameras are messy.

Every manufacturer implements its camera hardware differently, and some devices place restrictions or processing inside the camera hardware itself. arinome can avoid adding its **own** post-processing, but it cannot always remove processing performed internally by the phone before Android receives the image.

For the least processed result your device exposes, use **RAW DNG**.

JPEG mode is there for convenience when you want something immediately usable without developing a RAW file.

---

## Building it yourself

If you simply want to use arinome, you can ignore this section and download the APK above.

For development, arinome requires:

* Android SDK 34
* JDK 17
* Minimum Android version: Android 5.0 / API 21

### Windows

```powershell
.\gradlew.bat assembleDebug --console=plain

adb install -r app\build\outputs\apk\debug\app-debug.apk

adb shell am start -n com.whirlxd.arinome/.CameraActivity
```

### Linux / macOS

```bash
./gradlew :app:assembleDebug --console=plain
```

The workstation Gradle wrapper may point to a checked local Gradle 8.9 ZIP for environments without access to Gradle's distribution servers. CI replaces this URL inside its own isolated checkout.

---

## Project lineage

arinome began as a fork of [`reilandeubank/unprocess`](https://github.com/reilandeubank/unprocess), which itself builds on Google's [`Camera2Basic`](https://github.com/android/camera-samples/tree/main/Camera2Basic) sample.

A small part of that foundation still remains underneath arinome, particularly pieces of the Camera2 RAW capture/session structure and several camera utilities.


Thanks to the original `unprocess` author and the Android camera-samples contributors for providing the foundation arinome grew from.

---

## Artwork & icons



Logo, White-balance and capture-check symbols used inside the app come from [Phosphor Icons](https://phosphoricons.com/) and are distributed under the MIT License.

---

<div align="center">

## License

arinome is released under the **Apache License 2.0**.

See [`LICENSE`](LICENSE) for the full license.

Existing copyright and license headers are retained in inherited files.

<br>

**©️ 2026 arinome by whirl**

</div>
