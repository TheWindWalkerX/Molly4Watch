# Wear companion: status (updated 2026-11-20)

## Blocker: stock Molly won't build
`im.molly:libsignal-client` / `libsignal-android` 0.96.3-1 and `im.molly:ringrtc-android` 2.69.5-1 come from Molly's Cloudsmith repos, which return **HTTP 402 Payment Required**. Checked by hand and again later the same day.
- Molly's GitHub has no announcement and no issue about it. Molly's `mollyim/libsignal` and `mollyim/ringrtc` repos publish source tags only, no prebuilt files.
- **Option A:** Signal's official `org.signal` builds of the same versions (libsignal 0.96.3, ringrtc 2.69.5) are available at `build-artifacts.signal.org`. Substituting them changes where the crypto and calling libraries come from. **This is Cloud Kaffee's decision.**
- **Option B:** build Molly's libsignal fork from source (`libsignalClientPath`). This needs Rust. RingRTC from source (WebRTC) isn't feasible on this machine.
- **Option C:** wait for Molly to fix Cloudsmith.

**Decided 2026-11-20 (Cloud Kaffee):**
- libsignal: **A now** (Signal's `org.signal` 0.96.3 from build-artifacts.signal.org; not on Maven Central), **then B** (Molly's fork from source through `libsignalClientPath`).
- ringrtc: Signal's build isn't a drop-in, because Molly's fork adds `ProxyInfo` parameters and a patched `org.webrtc.PeerConnection.ProxyInfo`. So **repackage Molly's own ringrtc** from the signed v8.19.2-4 release APK. The APK's signature is verified and matches the README fingerprint `6aa80fdf…0886`.

Full plan and progress: `~/Documents/Claude/Molly-Wear-Handoff.md`, section 5.

There's still no baseline. `BASELINE.md` is unwritten, and none of `:app`'s tests can run.

## Done
- **Toolchain:**
  - JDK 21 in `~/.local/jdk/current`, SDK in `~/Android/Sdk`, environment in `~/.android-env.sh`.
  - Gradle heap capped in `~/.gradle/gradle.properties`.
  - Real variant names: `prodWebsiteDebug`, `prodStoreDebug`, `stagingWebsiteDebug`.
- **Emulators:** see `EMULATORS.md`. Phone and Wear OS 6.0 watch both boot, windowed. **Not paired yet.**
- **Modules**, registered in `settings.gradle.kts` (all Molly edits are tagged `MOLLY-WEAR`):
  - `:wear-protocol`: DTOs, `WearPaths`, versioned `WearCodec`.
  - `:wear-bridge`:
    - `MollyGateway` (the only contract with Molly)
    - `CommandHandler`, which applies the lock gate before anything else
    - `SnapshotPublisher`, which coalesces requests, always publishes a content-free locked snapshot on lock, and never publishes content when no watch is present
    - `DataLayerTransport`
    - `WearBridge` entry point, `WearBridgeListenerService`, manifest and capability resource
    - No dependency on Molly modules.
  - `:wear` (watch app, Wear Compose M3 **1.6.2**; 1.7.x needs compileSdk 37 and Molly uses 36.1):
    - chat list, thread view with history paging, reply through the system voice/keyboard input, long-press reaction picker with "remove my reaction", mark read, and locked / no-phone / connecting states
    - applicationId read from `app/gradle.properties` (`basePackageId`), the same as `:app`
    - nothing written to disk; everything dropped on lock.
- **Dependencies:**
  - Wear libraries are in `gradle/wear-libs.versions.toml`.
  - `verification-metadata.xml` only has entries added, never removed. Gradle's `--write-verification-metadata` prunes pins that the current build doesn't use. So: back up the file, run Gradle with `--write-verification-metadata sha256 <tasks>`, then run `python3 -I docs/wear/tools/merge_meta.py <backup> gradle/verification-metadata.xml gradle/verification-metadata.xml`. That adds only the new entries to the backup and writes the result.

## Test results
| Suite | Where | Result |
|---|---|---|
| `:wear-protocol:test` (`WearCodecTest`) | JVM | 13/13 pass |
| `:wear-bridge:testDebugUnitTest` (`CommandHandlerTest`, `SnapshotPublisherTest`) | JVM | 19/19 pass |
| `:wear:testDebugUnitTest` (`WatchControllerTest`) | JVM | 12/12 pass |
| `:wear:connectedDebugAndroidTest` (`WatchUiTest`) | molly-watch60 | 7/7 pass |
| `:wear:connectedDebugAndroidTest` (`WatchUiTest`) | **real Pixel Watch 3** (Android 17 / SDK 37, wireless ADB) | 7/7 pass |

On Android 17 the tests first failed 7/7 in Espresso 3.5, which calls `InputManager.getInstance` by reflection, and Android 17 removed that method. Fixed by pinning newer test libraries for `:wear` only, in `wear-libs.versions.toml`: espresso-core 3.7.0, test runner/core 1.7.0, ext-junit 1.3.0. Molly's checksum file already listed all of them, and Molly's own `testLibs` are unchanged.

**Wireless ADB to the watch:** over the laptop's Wi-Fi (MT7921) the watch's network announcements (mDNS) arrived, but direct traffic to it got no reply, and `adb pair` failed with "protocol fault". With the laptop on wired Ethernet to the same router, pairing works at once. **Use Ethernet on the laptop for watch debugging.** The watch is fragile, so keep the load low: scrcpy `--max-fps 15 --video-bit-rate 2M --max-size 320 --no-audio`, run one short job at a time, and don't poll it in loops.

All of these run under Molly's strict dependency verification.

The UI tests found a real bug: opening the reaction picker cleared the open thread, so reactions were dropped. It's fixed: the thread is now cleared only when the chat list comes back.

## Next steps
1. **Cloud Kaffee:** pick a libsignal/ringrtc option (see the blocker above).
2. **Cloud Kaffee:** sign into Google on the phone emulator, then pair (`EMULATORS.md`).
3. Implement `MollyGateway` in `:app` (a new file):
   - `ThreadTable.getUnarchivedConversationList` / `getUnreadThreadCount`
   - `MessageTable.getConversation`
   - `MessageSender.send` (the `RemoteReplyReceiver` pattern)
   - `sendNewReaction` / `sendReactionRemoval`
   - `ThreadTable.setRead` + `MarkReadReceiver.process`
   - lock: `KeyCachingService.isLocked()`

   Then hook `WearBridge.onStateChanged()` into Molly in two places:
   - **`DefaultMessageNotifier.updateNotification` (:136).** This covers new messages and also **unlock**: `KeyCachingService.handleCacheKey()` (:154) already calls `updateNotification` after the passphrase is entered.
   - **Lock: `KeyCachingService.handleClearKey()`, right after `KeyCachingService.locking = true;` (:181).** From then on `isLocked()` returns true, so the publisher replaces the snapshot with the content-free locked one. This is a one-line edit, tagged `MOLLY-WEAR`.
4. Phone instrumentation tests for `:wear-bridge`, and end-to-end tests on the paired emulators.
5. Tile and complication.

## Open questions
- **Opt-in setting in Molly:** for now the bridge publishes only when the watch app is detected.
- **Snapshot storage on the watch:** the snapshot DataItem is kept by Play services on the watch, in its own storage protected by device encryption. The watch app writes no cache of its own. The handoff's "encrypt the cache with a Keystore key" therefore only applies if we add one. Encrypting the payload end to end would need a key-exchange step between phone and watch.
- **Horologist:** not used. Wear Compose M3 covers the layout.

## 2026-11-20: moved to the Ryzen 7 5825U server
- Restored the work onto a fresh clone at 892d626 (branch wear-companion); it matches the old laptop's diff.
- JDK Temurin 21.0.13.1 is in ~/.local/jdk/current. SDK packages installed: platform 36.1, NDK 28.0.13004108, emulator, Wear OS 6.0 and phone API 36 images.
- Gradle heap is 8g with 8 workers.
- JVM tests: wear-protocol 13/13, wear-bridge 19/19, wear 12/12 (first run took 2m56s).
- Pixel Watch 3 paired over Ethernet with the SDK adb. A failed pair attempt burns the code, so get a new one before retrying.
- Next: handoff 5b (libsignal A), then the 5d ringrtc repack.

## 2026-11-20 (later): blocker cleared, integration built (D3, D4)
- D3: libsignal A (Signal's official 0.96.3, behind `-PmollyWear.upstreamLibsignal=true`) plus Molly's RingRTC 2.69.5-1 repacked from the signed APK into `~/.m2`. Stock Molly builds. `BASELINE.md`: 4,520 unit tests, 0 failures.
- Gradle: `:app` Kotlin needs `kotlin.daemon.jvmargs=-Xmx6g`.
- D4: Play services can't go into Molly (it clashes with core-gms), so a relay app `:wear-relay` holds the Data Layer and reaches Molly through `:wear-ipc`. Watch app id is now `im.molly.app.wear`.
- Built: Molly APK (no Play services classes), `wear-relay-debug.apk`, `wear-debug.apk`. Wear unit tests: protocol 13, ipc 4, bridge 19, relay 6, wear 12, all passing.
- Next:
  1. Regression run against BASELINE.
  2. End-to-end on emulators: Molly + relay on `molly-phone`, `:wear` on `molly-watch60`. Needs Cloud Kaffee's Google sign-in on the phone emulator and pairing.
  3. libsignal B from source (Rust nightly-2026-03-23, targets and protoc 29.6 are installed; checkout in `~/src/libsignal`).
- Real devices: nothing installed. The phone switch still needs Molly backup, uninstall, restore.

## 2026-11-20 (evening): real devices, Google Messages-style watch UI
- Real devices: the Pixel 7 had no Molly at all (no backup needed). Installed the Molly fork (debug), the relay and the watch app (`optimized` build type). Cloud Kaffee registered Molly.
- Tested on real hardware: lock privacy, chat list, history, react, remove reaction, mark read, swipe-to-reply (quoted), and a quoted reply sent by hand. Write tests ran in Note to Self only. Watch keyboard send ignores injected taps, so it needs a real press.
- Regression run: 4,574 tests, 0 failures (matches BASELINE plus the wear tests).
- New: Google Messages-style bubbles (mine right in the chat color, theirs left), time labels, edge scaling on the round screen; reactions as chips; long-press actions (6 reactions, Reply, Remove my reaction); swipe LEFT to quote-reply (right is system back).
- Protocol additions (all optional): `chatColor`, `avatar` (48 px WebP, base64), `Snapshot.theme` (phone Material You palette, added by the relay), `WearMessage.quote`, `SendText.quoteMessageId`. IPC_VERSION 2.
- Performance: the `optimized` build type (R8, debug-signed) cut jank from 45% to 4.8%. Data Layer callbacks run on a background HandlerThread.
- Fixed: R8 stripped `android_wear_capabilities`, so the relay never saw the watch. Now kept by `res/raw/keep.xml`.
- Tests: protocol 13, ipc 4, bridge 21, relay 8, wear 13 unit tests; watch UI 9/9 on the molly-watch60 emulator (run with `ANDROID_SERIAL=emulator-5554`).
- Ideas next: group member avatars next to incoming bubbles, Tile and complication, libsignal B.
