# SnapShelf

SnapShelf is an offline-first Android library for organizing long screenshots into subjects and units. Images selected through the Android file picker are copied into app-private storage, while Room stores the hierarchy and reading metadata.

## Build

Open this directory in Android Studio with JDK 17 or newer, allow Gradle to sync, and run:

```text
gradlew.bat :app:assembleDebug
```

The debug APK will be written to `app\build\outputs\apk\debug\app-debug.apk`.

## Download the latest APK from GitHub Actions

Every push, pull request, and manual workflow run builds a debug APK. To download it:

1. Open the [SnapShelf Actions page](https://github.com/SriramBharath-7/SnapShelf/actions).
2. Select the **Android** workflow and open a successful run.
3. Scroll to **Artifacts** and download **SnapShelf-debug-apk**.
4. Extract the downloaded ZIP to obtain `app-debug.apk`.

The artifact is retained for 30 days. A workflow run must finish successfully before the artifact is available.

## Current scope

The project includes subject and unit creation, multi-image importing, persistent Room metadata, app-private image copies, rename/delete confirmation flows, persistent move-up screenshot ordering, collection-aware previous/next navigation, zoom/pan gestures, and a dark Compose UI. Imported originals are copied byte-for-byte into subject/unit-owned app storage; the original document display name is stored separately from the internal UUID filename. Library cards use bounded thumbnails, while the reader decodes only visible regions of large images and retains saved reading positions.

Protected screenshots are encrypted with AES-GCM using an Android Keystore key and require `BiometricPrompt` authentication (biometric or device credential) before entering the vault or decrypting a viewer image. Decrypted viewer files are placed under an app-private temporary directory and removed when that viewer leaves composition. App lock can be enabled in Settings and requests authentication when the app returns from the background.

## Important limitations

- The project includes a Gradle 8.9 wrapper for Android Studio and GitHub Actions. This environment still cannot execute it because Gradle and the Android SDK are not installed locally.
- A device/emulator test is still required for biometric fallback, frame delivery on representative very tall images, image decoding limits, and Photo Picker behavior.
- Reader controls hide after a short idle period and reappear on tap; the viewer provides button and swipe navigation, but does not include a thumbnail strip or fit-to-width toggle.
- Import failures are surfaced to the coroutine log but do not yet have a dedicated in-app error banner.
