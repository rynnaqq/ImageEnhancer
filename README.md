# Local Photo Enhancer

A native Android photo editor that processes entirely on the phone. No Internet
permission, account, telemetry, cloud inference or runtime model download.

This build provides a working local enhancement baseline. It is **not yet the
finished professional restoration product** described in the full PRD. Advanced
face restoration, damage repair, colorization, learned reference fusion, TIFF and
streamed full-resolution processing are explicit technical dependencies, with
visible status in the app and concrete plans in [the dependency document](docs/TECHNICAL_DEPENDENCIES.md).

Download the signed Android 10+ APK from [GitHub Releases](https://github.com/rynnaqq/ImageEnhancer/releases/tag/v0.1.0).
Version 0.1.0 is a preview; see the [release notes](docs/releases/v0.1.0.md) for
the checksum, verified behavior and remaining qualification work.

## What works

- System gallery/file import, single photos and independent batches.
- Private source copies, editable projects, history and lossless output revisions.
- Automatic photo analysis and conditional enhancement.
- Crop, rotate, flip, straighten, noise/blur/clarity filters and tone/color controls.
- Real bundled ONNX super-resolution, overlapping tiles, memory policy and CPU fallback.
- 1×/2×/4×/8× requested output sizes, with safe adaptations disclosed.
- Before/after comparison, synchronized zoom/pan and accessible comparison controls.
- Persisted foreground queue, cancellation, failure isolation and safe stage resume.
- Automatic new gallery exports to `Pictures/LocalPhotoEnhancer/` as JPEG, PNG or WebP.
- Metadata removal by default, local sharing, re-editing a prior result and project deletion.

The bundled Apache-2.0 ESPCN model learns **3× luminance** restoration. Other output
scales locally resample that result. Chroma is interpolated. Denoise/deblur controls
currently use conventional filters; the app identifies this explicitly. Quality is
the default profile; all three profiles currently share the same trained model.

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
The published release is signed with a private distribution key retained locally
in `.signing/`. Private keys and passwords are excluded from the repository and
release assets. The [signing guide](docs/SIGNING.md) explains repeatable signing
and backup requirements for future updates.

```powershell
.\scripts\build.ps1 -Tasks @(':app:assembleRelease')
.\scripts\sign-release.ps1
adb install -r dist/LocalPhotoEnhancer-v0.1.0.apk
```

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

See [validation](docs/VALIDATION.md), [PRD coverage](docs/PRD_COVERAGE.md),
[the implementation plan](docs/superpowers/plans/2026-10-01-local-photo-enhancer.md)
and [bundled model metadata](app/src/main/assets/models/manifest.json).

Verified on 2026-10-02: **10 core tests and 22 offline Android tests passed**.
Debug/release builds and APK model/permission audits passed. Both lint variants
have zero errors and 29 warnings. The native system-picker-to-gallery workflow
was also checked; screenshots and original-byte hashes are in the validation record.

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
