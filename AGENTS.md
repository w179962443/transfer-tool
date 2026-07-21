# Transfer Tool Agent Notes

## Project Overview

This is a small Android file transfer app written in Kotlin. The app uses the Android Storage Access Framework through `DocumentFile`, so users can select a source directory on the phone and a target directory, then move files into target subfolders grouped by file extension and month.

The current UI is XML + ViewBinding in a single `MainActivity`.

## Important Files

- `app/src/main/java/com/w179962443/transfertool/MainActivity.kt`: transfer state machine, SAF directory traversal, file copy/delete, retry handling, and dynamic status rendering.
- `app/src/main/res/layout/activity_main.xml`: main screen controls.
- `app/src/main/res/values/strings.xml`: all visible UI strings.
- `app/build.gradle.kts`: Android app configuration and dependencies.

## Transfer Model

Transfers are intentionally incremental:

1. The user chooses a batch size `a`.
2. The app walks source directories until it discovers up to `a` files.
3. Directory walking pauses while that batch is transferred.
4. If automatic mode is enabled, the next batch is discovered and transferred immediately.
5. If automatic mode is disabled, the app pauses after each batch and waits for the user to tap continue.

Each discovered file becomes a `FileTask` and belongs to a `TransferBatch`. Failed file tasks remain visible and can be retried individually or together.

## Development Notes

- Keep file operations on `Dispatchers.IO`.
- Do not collect the entire source tree before transferring; preserve the incremental discovery behavior.
- Avoid placing the target directory inside the source directory, otherwise moved files may be rediscovered.
- Prefer focused changes; this app currently has no repository-wide architecture layer.

## Verification

Use the Gradle wrapper when possible:

```powershell
.\gradlew.bat assembleDebug
```
