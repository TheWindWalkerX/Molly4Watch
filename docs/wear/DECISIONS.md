# Wear companion: decisions

## D1. Fork Molly; keep new code in new modules

The phone side lives in `:wear-bridge`, the watch app in `:wear`, and shared DTOs in a protocol module.
Edits to Molly's own files stay at a few lines each so rebasing on upstream stays easy.

Rejected:
- Notification mirroring: can't do reactions or thread history.
- A signal-cli linked device on a server: needs Tailscale, which is blocked on school Wi-Fi.

## D2. Transport: Wear Data Layer (decided 2026-11-20)

The phone and watch talk over the Wear OS Data Layer (`com.google.android.gms:play-services-wearable`).

Why:
- Pairing, reconnection and message queuing come with the Data Layer, so we don't build them.
- Pairing a Pixel Watch already requires Google Play services on the phone. On GrapheneOS that means sandboxed Google Play, so this adds no new runtime requirement.

Cost, accepted knowingly:
- It adds a proprietary Google dependency to a fork that otherwise has none. Keep it confined to `:wear-bridge` (phone side) and `:wear`. `:app` should depend only on a bridge interface, so the dependency stays out of the core app and could later be swapped or removed.

Consequences:
- The phone and watch APKs must have the **same applicationId and signing key**, or the Data Layer won't connect them.
- Testing on emulators needs a phone image with Google Play plus a Wear OS image, paired.

Rejected: raw Bluetooth or BLE sockets. That avoids Google code, but we would have to build pairing, reconnection, queuing and encryption ourselves, and emulator support for Bluetooth is limited.

## D3. Native libraries while Molly's Cloudsmith repos return HTTP 402 (decided 2026-11-20)

Molly's Maven repos on Cloudsmith (`mollyim/libsignal`, `mollyim/ringrtc`) answer HTTP 402, so stock Molly can't resolve `im.molly:libsignal-*:0.96.3-1` or `im.molly:ringrtc-android:2.69.5-1`. Cloud Kaffee chose "A then B" for libsignal and a repack of Molly's own build for RingRTC.

### libsignal, A (in place, off by default)

- With `-PmollyWear.upstreamLibsignal=true`, `settings.gradle.kts` adds `https://build-artifacts.signal.org/libraries/maven/`, limited to the two libsignal modules. The root `build.gradle.kts` then substitutes `im.molly:libsignal-{client,android}` with `org.signal:libsignal-{client,android}:0.96.3`. All lines are tagged `MOLLY-WEAR`.
- Molly's verification metadata already pinned Signal's 0.96.3 artifacts. Those pins match the `.sha256` files Signal publishes next to each artifact: client jar `67db9808…edef`, android aar `9acc42c4…0531`.
- Gap: Molly's fork adds "Enforce proxy-only mode when proxy is set" (`rust/net/src/connect_state.rs`). Signal's build lacks it, so A is weaker on privacy when a proxy is set. B fixes that.

### libsignal, B (next)

Build `mollyim/libsignal` v0.96.3-1 from source through Molly's existing `libsignalClientPath` hook, then drop A.

### RingRTC: repack Molly's signed release

Signal's ringrtc is not a drop-in replacement: Molly's adds `PeerConnection.ProxyInfo` arguments, and that class exists only in Molly's patched WebRTC. So `im.molly:ringrtc-android:2.69.5-1` is rebuilt locally and published to `~/.m2` (Molly already searches `mavenLocal` for `im.molly`):

- **Source APK:** `Molly-v8.19.2-4.apk` from Molly's GitHub release. `apksigner` reports valid v2 and v3 signatures from `CN=Oscar Mira, OU=Molly Android, O=Molly Instant Messenger`, cert SHA-256 `6aa80fdf4a8cc13737cfb434fc0cde486f09cf8fcda21a67bea5ee1ca2700886`, which matches Molly's README.
- **`jni/{arm64-v8a,armeabi-v7a,x86_64}/libringrtc{,_rffi}.so`:** copied unchanged from the APK.
- **`libs/libwebrtc.jar`:** Signal's own `libwebrtc.jar` from `org.signal:ringrtc-android:2.69.5`, with `PeerConnection` replaced by Molly's version compiled from `mollyim/webrtc` tag `7778c-1` (`sdk/android/api/org/webrtc/PeerConnection.java`, javac `--release 17` like Signal's).
  - Why this is exact: Molly's ringrtc builds WebRTC from `mollyim/webrtc` `7778c-1`. Compared with `signalapp/webrtc` `7778c`, the only Java change is that one file, +97 lines and no deletions (`ProxyInfo`, its `Builder`, `ProxyType`). Signal's `7778c` → `7778d` changed no Java. The result has 404 classes (Signal's 401 + 3).
  - **Rejected (first attempt, 2026-11-20):** extracting `org.webrtc` from Molly's APK with dex2jar. R8 had optimized those classes together with the whole app (merged lambdas dispatching into Molly screens, outlined helpers in app classes such as `CallTable$$ExternalSyntheticOutline0`). Debug builds don't notice, but calls could crash at runtime. A release build caught it as "Missing classes detected while running R8".
- **`classes.jar`:** `src/android/api/org/signal/ringrtc/*.java` from `mollyim/ringrtc` tag v2.69.5-1 (commit `8d81a87`), compiled with `javac --release 11` (same class version as Signal's classes.jar) against the jar above, `android.jar` 36.1 and androidx.annotation 1.10.0.
  - Checked against the APK: the class list matches apart from R8 synthetic lambdas and the generated `R`. Every public member we compile exists in the APK's classes; the APK has extra public members only because R8 widened access.
- **Other AAR files** (manifest, `R.txt`, `proguard.txt`, acknowledgments, aar-metadata): copied from Signal's `org.signal:ringrtc-android:2.69.5`.
- **POM:** Signal's, re-coordinated to `im.molly`, with the Gradle-metadata marker removed so Gradle uses the POM. Same dependencies.
- **Build script:** `molly-natives/repack-tools/make_aar.py` is deterministic. Two builds were byte-identical: AAR sha256 `e60dd277e628295506ee961613a291221be28a54919541cca6b94757cc74d48d`, POM `e33c4ca2…1666`.
- **Verification:** Molly's real pins stay. Our AAR hash is an `<also-trust>` under the original `7b1a77af…8bb1`, and the POM has its own entry. If Cloudsmith returns, the genuine artifact still verifies.

Licensing: everything here is AGPL-3.0 (ringrtc, Molly) or BSD (WebRTC), unchanged.

## D4. Phone side lives in a separate relay app; Molly stays Google-free (decided 2026-11-20)

**Problem.** Putting `:wear-bridge` (Play services Wearable) into `:app` can't work:
- Molly replaces Google's `play-services-base`, `-basement` and `-tasks` with its own small FOSS versions (`core-gms:*`).
- The Data Layer client needs Google's full versions. 45 of the 69 GMS classes it references are missing from `core-gms`, including the whole `GoogleApi` IPC layer.
- One APK can't contain both. The build fails at manifest merge on a duplicate `com.google.android.gms.tasks` namespace.

(Before that came up, Cloud Kaffee had answered "allow in every build" to adding the Android SDK license exception to `:app`'s licensee check. With D4 that exception isn't needed, so it was removed. Molly's licensee config is unchanged.)

**Decision (Cloud Kaffee):** a separate phone app, **`:wear-relay`**, holds the Data Layer.

```
watch (:wear, im.molly.app.wear) ⇄ Data Layer ⇄ relay (:wear-relay, im.molly.app.wear) ⇄ :wear-ipc (AIDL) ⇄ Molly (:app)
```

- **`:wear-protocol`** now also holds the `MollyGateway` contract (moved from `:wear-bridge`), so Molly can implement it without any Google code.
- **`:wear-ipc`** (Android library, no Google code): the AIDL `IMollyWearGateway`, JSON encoding (no 90 KiB cap on local IPC; the bridge enforces the Data Layer limit), and the signature-level permission `im.molly.wear.permission.BRIDGE`. The permission is defined in both apps, so install order doesn't matter.
- **Molly (`:app`)**:
  - `MollyWearGateway` implements the contract on Molly's database and senders.
  - `MollyWearGatewayService` is exported but requires the permission, and checks Molly's lock itself (defense in depth).
  - `WearRelayNotifier` sends a data-free explicit broadcast to `<molly package>.wear` with the permission and `FLAG_INCLUDE_STOPPED_PACKAGES`.
  - Hooks: one line each in `DefaultMessageNotifier.updateNotification` and `KeyCachingService.handleClearKey`.
  - Plus `implementation(project(":wear-ipc"))`, a `<queries>` entry, and the service entry in the manifest. All tagged `MOLLY-WEAR`.
  - Molly's APK contains no `com.google.android.gms.wearable` or `GoogleApi` classes (checked in the dex).
- **`:wear-relay`** (`<molly package>.wear`):
  - Contains `:wear-bridge` and `IpcGateway`.
  - `MollyConnection` binds on demand and unbinds after 30 s idle, so it doesn't pin Molly's process.
  - `StateChangedReceiver` triggers a republish.
  - `StatusActivity` is a setup screen, and opening it once takes the app out of the stopped state.
  - **If Molly can't be reached or speaks another IPC version, it counts as locked**, so only a content-free snapshot is published.
- **`:wear`** now uses applicationId `<molly package>.wear`, the same as the relay. That's what the Data Layer needs; Molly's own id no longer matters for it.

Consequences:
- Three APKs, all signed with the same key: Molly fork, relay, watch app.
- Molly's `staging` flavor (`im.molly.app.staging`) would look for `im.molly.app.staging.wear`. The relay is built only for the prod id for now.
- Play services' proprietary code now lives only in the relay and the watch app. Molly's AGPL code and its no-Google design are unchanged.
