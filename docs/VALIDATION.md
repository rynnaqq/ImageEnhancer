# Validation record

Verified on 2026-10-02. This record distinguishes code/build
verification from the physical-device and photographic-quality release gates in
[TECHNICAL_DEPENDENCIES.md](TECHNICAL_DEPENDENCIES.md).

## Build and packaging

- Windows host, 8 GiB RAM; JDK 17, SDK 35, Gradle 8.9 and pinned application dependencies.
- Pure core: **10 tests, zero failures**. Covers adaptive planning, restrained filter
  behavior, source alpha, parameter validation, exact tile coverage, memory overflow
  and queue transition rules.
- Debug, Android test and R8/resource-shrunk release APK assemblies succeeded.
- Debug and release lint each report **0 errors, 29 warnings**: 14 dependency update
  notices, four plural candidates and 11 unused resources.
- Both APK audits passed: the bundled model and license are present, its SHA-256
  matches the manifest, and INTERNET/ACCESS_NETWORK_STATE are absent.
- Version 0.1.0, minSdk 29, targetSdk 35; arm64-v8a, armeabi-v7a, x86 and x86_64
  native runtimes are packaged. This does not establish a tested device matrix.
- Debug APK: 195,087,991 bytes (186.1 MiB), debug signed, installed and tested.
  Unsigned release build: 138,245,126 bytes (131.8 MiB), optimized.
  Signed distribution APK: 138,265,329 bytes (131.9 MiB), using the dedicated
  private release key. See [release notes](releases/v0.1.0.md) and [signing](SIGNING.md).

Evidence: [integrated build](evidence/build-verification.txt),
[fresh core tests](evidence/core-tests.txt), [debug audit](evidence/apk-audit-debug.txt),
[release audit](evidence/apk-audit-release.txt), [APK metadata](evidence/apk-metadata.txt)
and [artifact SHA-256 hashes](evidence/apk-checksums.txt).

## Signed release verification

The unchanged optimized APK (unsigned SHA-256
`650450c77724e2ec6b395f62b11b793ed4b22f922a3fb0fe5d1edcb7e81b19c6`)
was aligned and signed after assembly. The signed APK verifies with APK Signature
Scheme v3 for minSdk 29, retains 16 KiB ZIP native-library alignment, and passes
the same model/license/network-permission audit. No signing files are published.

Signed APK SHA-256:
`ab1f3e3f5cd3fc3a78d99126ec508249e30b62065511827fe54acebfa8acc781`.
Evidence: [signature](evidence/release-signature.txt),
[signed APK audit](evidence/release-apk-audit.txt) and
[release core verification](evidence/release-core-tests.txt).

Release preparation reran **10 core tests and all 22 offline Android tests**:
[Android regression output](evidence/release-android-regression.txt).
The first cold-boot attempt hit a startup ANR while System UI and unrelated
Google apps also stalled on the memory-constrained host. After the emulator
settled, the unchanged suite passed in 186.122 seconds. The
[initial attempt](evidence/release-android-before-boot-settle.txt) is retained.

All 99 original optimized-APK ZIP payload entries match the signed APK byte for
byte; only signing metadata is added: [payload comparison](evidence/release-payload-match.txt).
The exact signed APK was installed on API 35, its on-device SHA-256 matched the
distribution artifact, and the package is not debuggable:
[installation evidence](evidence/release-device-install.txt).

The signed R8 build also passed the native system-picker -> Auto -> enhancement
-> background/return -> completed result workflow with airplane mode on, Wi-Fi
off and mobile data off. It created a new 640×480 JPEG from the 320×240 synthetic
PNG, retained the original public bytes, and displayed before/after comparison
and the gallery-save confirmation. See [workflow](evidence/release-native-workflow.txt)
and [signed-build screenshot](evidence/release-ui-result.png).

## Offline Android results

**22 tests passed, zero failures**, on Android 15/API 35, x86_64, in the owned
`LocalEnhancer` emulator. Airplane mode was enabled, Wi-Fi was off and the mobile
data setting was off. See [device settings](evidence/device-environment.txt) and
the complete [test output](evidence/android-tests.txt).

| Suite | Passed | Behavior checked |
|---|---:|---|
| Background processing | 1 | Actual auto enhancement after backgrounding; gallery export; activity recreation; History and re-edit from the prior result. |
| Concurrency | 5 | Delayed autosave/enqueue, guarded gallery updates, atomic cancellation/claim and old cleanup preserving new checkpoints. |
| Inference engine | 5 | Corrupt model repair, real SR differing from interpolation, thin/partial edge tiles at 4×/8×, and failed/cancelled-item isolation. |
| Migration | 1 | Actual schema-1 to schema-2 migration preserves settings and prior-result input dimensions. |
| Projects and files | 9 | Offline permissions, settings, immutable originals, crop/export/metadata, corrupt imports, safe paths, re-edit, owned references, checkpoint invalidation and full-width sharing. |
| Native UI | 1 | Home actions, settings, Quality profile and honest unavailable-model explanation. |

## Manual native workflow

The installed app was operated through Android's actual system photo picker:
select the [synthetic fixture](evidence/qa-fixture.png), import, choose Auto,
enhance, minimize/return, open the completed result and compare before/after.

The source stayed 320×240 PNG. A new **640×480 JPEG** appeared in
`Pictures/LocalPhotoEnhancer/`, with a private lossless result retained. The host
fixture, public original and private original all have SHA-256
`9e0d795742dbb4c66f4afc061107fdef34830d9d736c4bee93521d6f61f0be29`.
See [workflow evidence](evidence/manual-workflow.txt).

Screens: [Home](evidence/ui-home.png), [system picker](evidence/ui-picker.png),
[editor](evidence/ui-editor.png), [dependency explanations](evidence/ui-controls.png),
[completed queue](evidence/ui-queue.png) and
[comparison with gallery confirmation](evidence/ui-result.png).

## Regression evidence

Earlier device tests reproduced the fixed-size model failure (`Got 32, Expected
224`) and the re-edit/reference ownership defects before their fixes. The adapter
now pads to the model's fixed input and crops its 3× output; tests also cover narrow
and partial edge tiles. The project fixes preserve prior revisions and give the
main project its own immutable reference assets.

Additional regression checks cover recipe edits invalidating old checkpoints,
full output dimensions during sharing, and a schema-1 project migrating to schema 2.
An Android lifecycle test starts actual auto enhancement, backgrounds the activity,
checks completion and gallery export, recreates the activity, then opens history
and continues editing from the previous result.

Two deterministic Room races were reproduced before the conditional updates:
[failing autosave/enqueue cases](evidence/concurrency-before-fix.txt). Guarded
gallery writes preserve re-edits and queued recipes; atomic claims cannot revive
cancelled items. Follow-up review caught unsafe late file cleanup, now serialized
with re-edit/enqueue and guarded by state, revision and output path. All five
concurrency cases passed in the final suite.

The lifecycle test also exposed AndroidX Core 1.15 masking out API 35's newer
mediaProcessing type. The service now uses the platform overload available at
minSdk 29. Test scrolling locates actions through the lazy list, and gallery
assertions wait for export to settle after the private completion commit.

Recorded provider probes in the final suite reported about **60.5–78.7 ms** per
fixed 224×224 input: [runtime logs](evidence/model-runtime.txt). Selection included
CPU and NNAPI; NNAPI may itself fall back to CPU. These are small probe timings,
not end-to-end photo benchmarks or evidence of physical NPU use.

## Device procedure

The available emulator is Android 15/API 35, x86_64. Compilation and emulator work
are serialized to limit host memory pressure. The app and test APK are installed,
airplane mode is enabled, Wi-Fi/mobile data are disabled, and tests run through
`androidx.test.runner.AndroidJUnitRunner` against the installed application.

```powershell
.\scripts\build.ps1 -Tasks @(':core:test', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:assembleRelease', ':app:lintDebug', ':app:lintRelease')
.\scripts\verify-apk.ps1
.\scripts\verify-apk.ps1 -Apk 'app\build\outputs\apk\release\app-release-unsigned.apk'
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell cmd connectivity airplane-mode enable
adb -s emulator-5554 shell svc wifi disable
adb -s emulator-5554 shell settings put global mobile_data 0
adb -s emulator-5554 shell am instrument -w dev.localphoto.enhancer.test/androidx.test.runner.AndroidJUnitRunner
```

The tests create synthetic fixtures and clean up their own gallery exports and
private project data. They verify real inference and data safety; they do not
establish professional restoration quality on real photographs.

The serial above is this project's emulator; confirm the target when reproducing
elsewhere. Gradle was stopped before booting with hardware graphics. Cold boots
took roughly two minutes. A transient System UI warning during manual setup was
dismissed with Wait; emulator startup is not a physical-device stability qualification.

## Outstanding qualification

No physical low/mid/high-device quality, vendor acceleration, OS-kill/timeout,
screen-off, thermal-pressure, low-disk or 48/108 MP streaming matrix is claimed.
Android 10/other API levels and HEIC vendor variants need physical/device fixtures.
Advanced learned restoration models, TIFF and huge-raster streaming remain the
explicit dependencies listed in the coverage and dependency documents.
