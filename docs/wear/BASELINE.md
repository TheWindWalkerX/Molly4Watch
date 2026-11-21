# Baseline: stock Molly before the `:app` integration

Recorded 2026-11-20 on the Ryzen 7 5825U server (16 threads, 30 GB, Fedora 44). JDK Temurin 21.0.13.1, Gradle 9.4.1.
Base: upstream `892d626` (Molly v8.19.2-4), plus only the wear modules and the D3 dependency workarounds. No edits to `:app` yet.
All runs use `-PmollyWear.upstreamLibsignal=true` (D3 libsignal A) and the locally repacked `im.molly:ringrtc-android:2.69.5-1` (D3).

## Build

`./gradlew -PmollyWear.upstreamLibsignal=true :app:assembleProdWebsiteDebug` succeeds and produces `app-prod-website-debug.apk` (148,510,380 bytes).
- `lib/arm64-v8a/libringrtc.so` in that APK is byte-identical to the one in Molly's signed v8.19.2-4 release (sha256 `33f071f2…d50d`).
- Memory: `:app:compileProdWebsiteDebugKotlin` ran out of memory with a 3 GB Kotlin daemon. It passes with `kotlin.daemon.jvmargs=-Xmx6g` (Gradle heap 8g).

## Unit tests (all pass)

`:app:testProdWebsiteDebugUnitTest` plus `testDebugUnitTest` on every Android module (wear modules excluded), took 8m26s:

| Module | Tests | Failed | Skipped |
|---|---:|---:|---:|
| app (prodWebsiteDebug) | 2596 | 0 | 99 |
| core/models | 4 | 0 | 0 |
| core/util | 265 | 0 | 2 |
| feature/camera | 37 | 0 | 0 |
| feature/registration | 543 | 0 | 6 |
| lib/apng | 51 | 0 | 0 |
| lib/device-transfer | 8 | 0 | 0 |
| lib/donations | 27 | 0 | 0 |
| lib/paging | 5 | 0 | 0 |

JVM modules (`test`):

| Module | Tests | Failed | Skipped |
|---|---:|---:|---:|
| core/models-jvm | 17 | 0 | 0 |
| core/util-jvm | 266 | 0 | 1 |
| lib/network | 34 | 0 | 0 |
| lib/libsignal-service | 592 | 0 | 7 |
| fast-lint | 43 | 0 | 0 |
| lintchecks | 32 | 0 | 0 |

**Total: 4,520 tests, 0 failed, 115 skipped.** Any failure after the `:app` integration is a regression.

Wear modules, for reference: wear-protocol 13/13, wear-bridge 19/19, wear 12/12.

Not covered here: instrumentation tests, lint and `make assemble` (release + bundle).
