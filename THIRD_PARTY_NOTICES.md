# Third-party notices

## Android client

Foundation: https://github.com/feggaa/scrcpy-android

Copyright (c) 2026 Laid Feggaa (Rabi3). MIT license: `android/LICENSE`.
Public-edition modifications and integration by NegiGao. Original attribution is retained in the app's About screen and `android/app/src/main/assets/open_source_licenses.txt`.

## scrcpy server

Source: https://github.com/Genymobile/scrcpy/tree/v4.1

Artifact: `scrcpy-server-v4.1`, from the official GitHub release:
https://github.com/Genymobile/scrcpy/releases/tag/v4.1

Location: `android/app/src/main/assets/scrcpy-server`

SHA-256: `deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae`

This matches the official release asset digest checked during preparation. Apache-2.0 license text is included in `android/app/src/main/assets/open_source_licenses.txt`. The server binary is unmodified. The desktop scrcpy/ADB executables are **not** bundled; users install them separately.

## Gradle wrapper and dependencies

The Gradle wrapper belongs to the Gradle project and is distributed under Apache-2.0: https://github.com/gradle/gradle . AndroidX, Kotlin and other build dependencies retain their own licenses and are resolved through the build configuration, not copied from a private dependency cache.

No Google application APKs, proprietary XR demos, game assets, developer signing keys, ADB keys or internal operational documents are distributed in this repository.
