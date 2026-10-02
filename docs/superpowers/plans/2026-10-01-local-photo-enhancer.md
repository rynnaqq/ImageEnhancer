# Local Photo Enhancer Implementation Plan

> For agentic workers: apply the orchestration skill. The main agent owns integration
> and verification; a coder owns the independent `core` module. Steps track completion.

**Goal:** Build an installable, private Android photo enhancer implementing the PRD,
with working local enhancement and explicitly tracked technical dependencies.

**Architecture:** A pure Kotlin `core` module defines image math and planning.
The Compose Android `app` module implements persistent projects, offline ONNX
inference, MediaStore exports and a resumable foreground queue.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JDK 17, Compose Material 3,
Room, DataStore, ONNX Runtime Android. Versions are pinned and build-verified.

**Spec:** `local-photo-enhancer-prd.md` and
`docs/superpowers/specs/2026-10-01-local-photo-enhancer-design.md`.

## Global constraints

- Android 10 (API 29) minimum; compile/target API 35 with modern service limits.
- No INTERNET permission, network calls, uploads, runtime model downloads or telemetry.
- Never overwrite original photos; exports are newly created gallery entries.
- Quality is the default profile; progress reflects actual completed stages/tiles.
- Every major feature is functional or explicitly marked as a technical dependency
  with a concrete local implementation plan (PRD section 50).
- User-visible copy belongs in localizable Android resources.

## Review focus

- Corrupt/inaccessible imports fail individually and leave no partial project files.
- Extreme dimensions and 8× scaling cannot overflow or allocate unsafe buffers.
- Service death, thermal events and cancellation cannot publish partial outputs.
- A failed batch item cannot prevent later items from completing.
- Re-editing, orientation and metadata stripping preserve the immutable original.

## Tasks

### 1. Build foundation and pure domain engine

**Files:** root Gradle/wrapper files, `core/build.gradle.kts`, `core/src/main/kotlin/dev/localphoto/core/`,
`core/src/test/kotlin/dev/localphoto/core/`.

**Produces:** `EnhanceSettings`, `Adjustments`, `TransformSettings`, `OutputSettings`,
`Profile`, `Stage`, `ImageMetrics`, `PipelinePlan`, `PipelinePlanner.plan`,
`MemoryPolicy.assess`, `TilePlanner.tiles`, `PixelProcessor.process`, queue rules.

- [x] Bootstrap JDK/wrapper using the existing SDK; pin dependencies.
- [x] Write and run failing behavioral domain tests before implementing domain code.
- [x] Implement validated parameters, image statistics, adaptive planning, tile geometry,
  memory guards, restrained adjustments and queue transition logic.
- [x] Run the complete core test suite.

### 2. Persistent application data and import/export

**Files:** `app/build.gradle.kts`, manifest/resources, `app/src/main/java/dev/localphoto/enhancer/data/`.

**Consumes:** core settings and queue states. **Produces:** Room `PhotoProject`,
`ProjectDao`, `ProjectRepository`, `SettingsStore`, `ImageFiles`, `GalleryExporter`.

- [x] Test settings round trips, safe owned paths and incomplete write cleanup.
- [x] Implement Room storage, private import copies, bounded previews and orientation.
- [x] Implement unique MediaStore JPEG/PNG/WebP exports and local sharing.
- [x] Verify original bytes remain identical, failed import cleanup and metadata stripping.

### 3. Bundled inference and persistent processing

**Files:** model assets/manifests/licenses, `app/src/main/java/dev/localphoto/enhancer/ai/`,
`processing/`, `scripts/verify-apk.ps1`.

**Consumes:** project settings and domain plans. **Produces:** verified model sessions,
`EnhancementEngine`, safe project output commits and foreground processing service.

- [x] Verify source/weight redistribution license; download at development time only.
- [x] Test actual tensor inference/output, cancellation and failed-item continuation.
- [x] Implement checksum verification, extraction/storage checks, local provider probes,
  tiled super-resolution, thermal response, stage checkpoints and atomic results.
- [x] Implement OS-version-specific foreground types, timeout/resume and notifications.
- [x] Audit APK permissions and model contents; measure available runtime performance.

### 4. Native user workflows

**Files:** `MainActivity.kt`, `AppViewModel.kt`, `ui/`, string/theme/icon resources.

**Consumes:** projects, settings and processing state. **Produces:** complete interactive
home/import/editor/queue/results/history/projects/settings screens.

- [x] Implement system import, explicit batch/reference choice, auto/manual editing,
  crop/rotate/flip/straighten, output/profile controls and settings persistence.
- [x] Implement synchronized comparison, draggable/accessibility slider, zoom/pan,
  fit and 100% preview inspection; re-edit/save/share/delete/project reopening.
  Full-resolution region inspection remains a tracked technical dependency.
- [x] Expose unavailable restoration features honestly with dependency explanations.
- [x] Verify import-to-gallery flow and accessible screen interactions on an emulator.

### 5. Integrated verification and delivery

**Files:** instrumentation tests, `README.md`, `docs/PRD_COVERAGE.md`,
`docs/TECHNICAL_DEPENDENCIES.md`, `docs/VALIDATION.md`, build/privacy scripts.

- [x] Run unit tests, debug/release assembly and Android lint.
- [x] Execute offline emulator smoke/inference/export tests and collect evidence.
- [x] Review the integrated implementation for original safety and background behavior.
- [x] Record measured results, unsupported hardware tests and all remaining dependencies.
- [x] Deliver the APK path and concise instructions; do not claim untested production quality.
