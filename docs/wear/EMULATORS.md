# Emulators

Host: i5-5300U (2 cores, 4 threads), 16 GB RAM, Debian 13 with GNOME on Wayland, emulator 37.3.10. Run `. ~/.android-env.sh` first.

## AVDs

| AVD | Image | Profile | RAM | Status |
|---|---|---|---|---|
| `molly-phone` | `android-36;google_apis_playstore;x86_64` | pixel_7 | 2 GB | Boots. **Use this.** `PlayStore.enabled=yes` was set by hand, because avdmanager writes `no`. |
| `molly-watch60` | `android-36;android-wear-signed;x86_64` (Wear OS 6.0) | wearos_large_round | 1.5 GB | Boots in about 45 s. **Use this.** |
| `molly-watch` | `android-36.1;android-wear-signed;x86_64` (Wear OS 6.1) | wearos_large_round | 1.5 GB | **Broken.** `system_server` crash-loops and never finishes booting. |
| `molly-watch-square` | Wear OS 6.1 | wearos_square | 1.5 GB | **Broken** in the same way. |

### Wear OS 6.1 boot failure
The crash is in `WindowManagerService.showEmulatorDisplayOverlay`, which calls `EmulatorDisplayOverlay.drawIfNeeded` and then `Surface.lockCanvas`. The abort message is
`Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma` (from `mapper.ranchu.so`).
It happens with `-gpu swiftshader_indirect`, `swangle_indirect` and `host` (Intel HD 5500), on both round and square profiles. `-feature -ReadColorBufferDma` isn't a valid feature name. The cause is an incompatibility between this image and emulator 37.3.10. Try again after the next emulator or image update.

The Wear OS 6.0 image logs a harmless sensor HAL abort (`unexpected sensor type: 26`) that doesn't affect boot.

## Boot (windowed)

```sh
export QT_QPA_PLATFORM=xcb   # the emulator's Qt UI is most reliable through XWayland on GNOME Wayland
emulator -avd molly-watch60 -port 5554 -no-audio -no-snapshot-save -gpu swangle_indirect &
emulator -avd molly-phone   -port 5556 -no-audio -no-snapshot-save -gpu host &
adb -s emulator-5556 wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 5; done'
```
- The fixed ports give stable serials: the watch is `emulator-5554` and the phone is `emulator-5556`.
- For headless use, add `-no-window`.
- The phone's first cold boot takes several minutes on this CPU.
- **RAM:** both emulators together leave about 4 GB. Stop Gradle daemons first (`./gradlew --stop`), and build with a smaller heap:
  `./gradlew "-Dorg.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC -XX:MaxMetaspaceSize=768m" ...`
- **Stopping:** `adb -s emulator-5554 emu kill` and `adb -s emulator-5556 emu kill`.

## Pairing (NOT DONE YET)
Google's current page (developer.android.com/training/wearables/get-started/connect-phone) documents only Android Studio's pairing assistant. Plan:
1. On `molly-phone`, Cloud Kaffee signs into a Google account himself (preferably a throwaway). Claude never signs in.
2. Install the Pixel Watch app (`com.google.android.apps.wear.companion`) from the Play Store.
3. Try the manual route: `adb -s emulator-5556 forward tcp:5601 tcp:5601`, then pair from the companion app.
4. If that fails, install Android Studio and use Device Manager, then the device's menu, then **Pair Wearable**.

Record what actually worked here.
