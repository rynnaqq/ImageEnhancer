# Local Photo Enhancer implementation design

The binding product specification is `local-photo-enhancer-prd.md`. This is a new
native Android application, minimum API 29. The user explicitly requested execution
of the supplied PRD; implementation proceeds using its suggested native stack.

## Product and presentation

Use Kotlin, Compose and Material 3 with a restrained cream/charcoal/sage palette,
large photo previews, accessible controls and English string resources. Home,
import mode selection, editor, processing queue, results, history, projects and
settings are real application screens. Photos are imported using the system photo
picker or Storage Access Framework. No account or Internet permission exists.

## Architecture

`core` is a platform-independent Kotlin module containing validated edit settings,
image analysis statistics, conditional pipeline planning, resource policy, pixel
adjustments, tile geometry and queue rules. `app` owns Compose/ViewModel/StateFlow,
Room projects, DataStore preferences, Android image decoding, local ONNX inference,
MediaStore export and a foreground processing service. Interfaces between the
modules are small data classes and pure functions.

Sources are copied into project-private storage on import; the app never obtains
write access to a user's original. Each project retains immutable source assets,
edits, references, settings, output revisions and processing state. Gallery exports
use new MediaStore entries in `Pictures/LocalPhotoEnhancer/`, with pending writes
removed on failure. Sharing uses a local FileProvider and strips metadata by
default. Deletion is limited to project-owned paths and generated gallery entries.

## Processing

Analyze a bounded preview for exposure, contrast, noise, sharpness, saturation and
monochrome content. Build conditional stages; manual controls bypass automatic
parameter selection. Implement real edge-aware denoise, restrained deconvolution,
tone/color adjustments and detail enhancement. Bundle a licensed, checksummed
trained super-resolution model; inference runs only through the local Android
runtime. Benchmark supported providers on a small actual input and fall back to
CPU. Never describe a conventional filter as an AI restoration model.

Decode within a calculated memory budget and infer with overlapping tiles. Validate
output dimensions before starting; offer the highest safe multiplier and explicitly
report any source sampling. Keep model concurrency at one, release tensors and
sessions, check cancellation between work units and reduce work under thermal
pressure. Large-image streaming assembly remains a separately identified dependency
until tested without full output allocation.

## Queue and background lifecycle

Persist queue and stage boundaries in Room. Start the foreground service only from
an explicit visible user action. On API 35+, use `mediaProcessing`; use the supported
older service type on previous Android versions. A notification exposes progress
and cancellation. Persist a safe checkpoint on OS timeout or interruption, stop
promptly, and let the user resume from the foreground. Failed items do not stop the
queue. Only completed, atomically committed results may be exported or reviewed.

## Model and format boundaries

PRD section 50 permits a feature to be explicitly marked as a technical dependency
with a concrete local implementation plan. Unverified face restoration,
colorization, learned deblur/denoise, damage inpainting, identity-aware reference
fusion, TIFF variants, vendor acceleration and fully streamed large outputs must
be identified in both product status and `docs/TECHNICAL_DEPENDENCIES.md`. Their
controls cannot simulate successful processing. Model licenses, upstream sources,
versions, SHA-256 values, tensor contracts and measured smoke benchmarks travel
with the bundled assets. Professional restoration and device-class quality are
release validation gates, not claims inferred from a successful build.

## Verification

Test pure planning, tiling, tone operations, memory overflow protection, queue
failure/cancellation and settings round trips. Build and lint Android debug/release
variants, inspect the merged APK permission list and bundled model checksums. Run
available emulator instrumentation with network disabled for import, real model
inference, export, compare, reopen, queue failure and cancellation. Maintain a PRD
coverage report and an honest hardware/quality matrix; physical-device tests that
cannot run locally remain explicitly unverified.
