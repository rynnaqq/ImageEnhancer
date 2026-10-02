# Local Photo Enhancer

A native Android photo editor that processes entirely on the phone. No Internet
permission, account, telemetry, cloud inference or runtime model download.

Version 0.2.0 adds bundled offline face-region detail enhancement, learned
colorization, automatic scratch repair and brush-selected reconstruction. It is
still a preview: physical-photo quality and device qualification, learned reference
fusion, TIFF and streamed full-resolution processing remain release gates in
[the dependency document](docs/TECHNICAL_DEPENDENCIES.md).

The v0.2.0 release page is [GitHub Releases](https://github.com/rynnaqq/ImageEnhancer/releases/tag/v0.2.0).
Publication is still pending while the final evidence and release assets are being
archived. The
[v0.1.0 release](https://github.com/rynnaqq/ImageEnhancer/releases/tag/v0.1.0),
[release notes](docs/releases/v0.1.0.md) and validation evidence remain archived.

## What works

- System gallery/file import, single photos and independent batches.
- Private source copies, editable projects, history and lossless output revisions.
- Automatic photo analysis and conditional enhancement.
- Crop, rotate, flip, straighten, noise/blur/clarity filters and tone/color controls.
- Real bundled ONNX super-resolution, overlapping tiles, memory policy and CPU fallback.
- Opt-in YuNet face detection with SwinIR face-region detail enhancement, limited to
  16 faces per pass and protected from previously reconstructed regions.
- Opt-in DDColor512 black-and-white colorization with strength control, original
  lightness and alpha preservation.
- Automatic scratch detection feeding FP32 LaMa, plus normalized brush, erase and
  clear controls for selected reconstruction. Repair preserves exact unselected
  pixels and original alpha.
- 1×/2×/4×/8× requested output sizes, with safe adaptations disclosed.
- Before/after comparison, synchronized zoom/pan and accessible comparison controls.
- Persisted foreground queue, cancellation, failure isolation and safe stage resume.
- Automatic new gallery exports to `Pictures/LocalPhotoEnhancer/` as JPEG, PNG or WebP.
- Metadata removal by default, local sharing, re-editing a prior result and project deletion.

The bundled Apache-2.0 ESPCN model learns **3× luminance** restoration. Other output
scales locally resample that result and chroma is interpolated. Denoise/deblur controls
remain conventional filters. The restoration bank also includes MIT YuNet,
Apache-2.0 SwinIR, DDColor512 and FP32 LaMa weights. Every asset or asset part,
SHA-256 digest, tensor contract and archived license is recorded in the
[model manifest](app/src/main/assets/models/manifest.json).

Restoration is disabled in old recipes and remains opt-in. SwinIR is a general
real-image neural detail model applied to aligned face regions; it is not GFPGAN or
an identity-aware face generator, and generated detail or color is not claimed to
be historically accurate. Advanced CPU sessions run one at a time. Colorization
and repair need about 1.2 GiB of available memory and are rejected safely when a
device lacks that headroom.

## Build

Requirements: JDK 17, Android SDK platform 35/build-tools 34+, Gradle 8.9 (wrapper
included). Android Studio can open this directory directly.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\scripts\verify-apk.ps1
```

On this workspace, `scripts/build.ps1` also discovers the locally bootstrapped JDK
under `.tools/jdk/`. Build dependencies need Internet access on the developer's
machine the first time; the installed app is entirely offline. Bundled weights
and full licenses are checked into `app/src/main/assets/`.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Unsigned release build: `app/build/outputs/apk/release/app-release-unsigned.apk`.
The release artifact is signed with a private distribution key retained locally
in `.signing/`. Private keys and passwords are excluded from the repository and
release assets. The [signing guide](docs/SIGNING.md) explains repeatable signing
and backup requirements for future updates.

```powershell
.\scripts\build.ps1 -Tasks @(':app:assembleRelease')
.\scripts\sign-release.ps1 -OutputApk dist/LocalPhotoEnhancer-v0.2.0.apk
adb install -r dist/LocalPhotoEnhancer-v0.2.0.apk
```

Update releases reuse the existing signing identity. Do not pass `-InitializeKey`.

A debug installation uses a different certificate. Export any results you want
to keep and uninstall that debug build before installing the signed release.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app, add a photo, choose Auto or Manual, and enhance. Completed results
are saved as new gallery files and retained in the project. Resume interrupted
jobs from the queue after returning to the app. The OS and device manufacturer
can still pause background work; the app never promises indefinite execution.

## Verify on Android

With an Android 10+ device/emulator attached:

```powershell
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
.\gradlew.bat :app:connectedDebugAndroidTest
```

The tests create their own private fixtures and remove their generated test
exports. They exercise actual ONNX inference, settings, original protection,
gallery formats, queue behavior and native UI controls.

See [v0.2.0 restoration validation](docs/RESTORATION_VALIDATION.md),
[baseline validation](docs/VALIDATION.md), [PRD coverage](docs/PRD_COVERAGE.md),
[the implementation plan](docs/superpowers/plans/2026-10-01-local-photo-enhancer.md)
and [bundled model metadata](app/src/main/assets/models/manifest.json).

For v0.2.0, **20 core tests pass**, final debug/test assemblies pass, and initial
debug lint reports zero errors and 30 warnings. The complete **42-test offline
Android suite passed in 161.965 seconds** on the owned API 35 x86_64 AVD with
3072 MiB RAM, airplane mode enabled, Wi-Fi disabled and mobile data disabled.
It exercised real LaMa, DDColor, YuNet/SwinIR and all-tools engine paths, including
source/alpha/unselected-pixel preservation, version-1 recipes, generated-region
resume/re-edit protection, missing-metadata rejection, native LaMa cancellation,
large-face guards and mask aspect/boundary regressions. The five-model part/full
hash, license and no-network audits also pass. See the
[validation record](docs/RESTORATION_VALIDATION.md) and
[Android test log](docs/evidence/restoration-v0.2.0/android-tests.txt).

The optimized R8/resource-shrunk release and release lint pass with zero errors and
30 warnings. `dist/LocalPhotoEnhancer-v0.2.0.apk` is 497,183,170 bytes with SHA-256
`356beabcefeda72f6b39690120b444cd15200091188cde400836b8a30285409e`.
It uses the existing v0.1.0 certificate
`921853fe2ce9460e7d4304d8f4e146ad045c81b8144e111fc30c50a22da337d0`;
v3 signature verification and 16 KiB zip alignment pass. The signed APK also passes
all five model/hash/license/no-network audits.

A same-key v0.1.0 → v0.2.0 upgrade retained the completed baseline project and
gallery result. The installed APK hash matched exactly and had no `DEBUGGABLE` flag.
On the offline owned AVD, the signed app prepared all five models through real native
probes, showed every model Ready, enabled all four restoration tools, accepted a
painted mask and completed a 256 px source as a 512×512 result through native UI.

GitHub publication remains pending. Physical ARM-device photo quality, thermal and
huge-input qualification remain open, along with reference fusion and TIFF. The
archived v0.1.0 record retains its 10 core and 22 offline Android test results.

## Source organization

```text
core/                         Pure image math, planning, tiling and resource rules
app/.../data/                 Room projects, settings, private files and MediaStore
app/.../ai/                   Model integrity, provider probes, tiled inference
app/.../processing/           Foreground service, cancellation and queue lifecycle
app/.../ui/                   Native Compose screens and photo comparison
app/src/main/assets/          Bundled weights, metadata and licenses
app/src/androidTest/          Real Android integration and inference tests
docs/                         Coverage, remaining local dependencies and validation
scripts/                      Build and APK privacy/model auditing
```
