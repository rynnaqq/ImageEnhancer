# Restoration models implementation plan

> For implementation use orchestrate roles for independent bounded tasks; the main agent owns integration and verification. Follow test-driven development for observable behavior.

**Goal:** replace all four locked restoration tools with real bundled offline model pipelines and publish a signed update.

**Spec:** ../specs/2026-10-02-restoration-models.md

**Architecture:** optional restoration recipe fields and stages in core; a manifest-driven, hash-checked multi-model catalog; dedicated Android ONNX processors; Compose controls with normalized brush masks; result metadata retained across re-editing.

**Stack:** Kotlin, Compose, ONNX Runtime Android 1.30, Room, existing PowerShell build/sign/release tools.

## Global constraints

- No app network permission, runtime model downloads, remote inference, mocked successful stages, or plaintext signing/Git credentials.
- Preserve immutable source images, existing concurrency guards, checkpoint semantics, same signing certificate and existing public release.
- No restoration by default for old projects. Exact unmasked repair pixels and alpha remain unchanged by the repair stage.
- Validate model provenance and run actual inference before bundling; record measured memory/latency and quality limits.
- Serialize Gradle, native model benchmarks and emulator tests on this 8 GiB host.

## Task 1: Recipe, stages and pixel contracts

Own core Models/PipelinePlanner/RestorationPixels and tests, plus SettingsCodec. Add `REPAIR`, `COLORIZE`, `FACE_RESTORE` stages and `EnhanceSettings.restoration: RestorationSettings`. Fields: faceStrength (0 default), colorize (false), colorStrength (70), scratchRepair (false), repairStrength (100), maskStrokes (empty). Strokes contain normalized points, radius as fraction of shorter image side, and erase flag. Normalize finite values and bound stroke/point counts. Persist explicit JSON with version 2 and version-1 defaults.

Tests first: opt-in stage order, old recipe behavior, finite/bounded strokes, brush interpolation/erase, RGB/Lab known values and alpha preservation, selected-only repair blending. Run core tests and covering Android codec tests during integration.

## Task 2: Artifacts and model catalog

Main owns ModelManager/manifest/assets/licenses/audit script. Verify downloaded model hashes, inspect input/output/operator contracts and benchmark real inputs. Split assets exceeding GitHub's file limit into checked parts while assembling a fully checked private ONNX at first use. Preserve default ESPCN API and provider probes; expose per-model status and initialize each independently. Advanced sessions use verified CPU contracts and are opened/closed one at a time.

## Task 3: Controls and masks

Own UI restoration controls, editor wiring, stage/message labels and relevant strings/tests. Replace locked cards with switches/strengths and actual model status in settings. Provide brush/erase/clear on a transformed preview using normalized coordinates, excluding letterbox space. Changing transforms clears incompatible strokes. Reconstruction requires a nonempty selected mask. Existing available enhancement actions stay usable if a different module fails. Keep the unrelated reference/TIFF dependency notices accurate.

## Task 4: Neural execution and generated-region tracking

Main owns restoration processors and engine/repository integration. Convert Lab accurately for DDColor. Pad/resize inputs without changing final geometry; keep native inference cancellable and report module-specific failure. Run LaMa only for nonempty masks; composite only selected original pixels. Detect/align faces and blend restored patches conservatively. Protect previously reconstructed regions in re-editing and store selected/generated mask metadata alongside result revisions. Preserve new-bitmap ownership, checkpoints and progress.

## Task 5: Verification and release

Add real-model Android tests for session creation/inference, masks, codec compatibility and all selected stages. Run core suite, Android regressions, build/lint and APK license/model/network audit. Install signed update over v0.1.0 on owned offline QA emulator; exercise real tools through native UI and verify source preservation and gallery output. Review integrated changes with a bounded independent agent. Commit/push new tag and publish signed APK/checksum to GitHub, verify public asset digests.

## Execution ledger

- Root cause confirmed: hardcoded restoration dependency dialogs; one SR-only runtime/manifest.
- Published v0.1.0 and its signing certificate are the upgrade baseline.
- Interfaces Task1 -> Task3/4: recipe and stages; exact field names above bind all consumers.
- Interfaces Task2 -> Task3/4: catalog reports independent state; processors request explicit model ids.
- Shared resources Task1/5: Gradle runs serialized by main agent; workers request tests if a heavy operation is active.
- Model selection remains subject to real contract/CPU/quality verification; research evidence recorded before artifacts enter source.
- Task 1 completed: observed RED (missing restoration API), then 17/17 core tests, no failures/skips. Android codec tests written; execution pending integration.
- Task 3 implemented and scoped diff reviewed: real controls, letterbox-aware brush, independent catalog, settings/labels. Android UI tests pending integration.
- Task 2 artifact gate observed RED: installed bank missing yunet/face-swinir/color-ddcolor/repair-lama. Downloaded YuNet, DDColor and LaMa candidates verified against pinned SHA-256; ONNX checker and real CPU inference passed.
- SwinIR export from pinned official Apache source/checkpoint passed three PyTorch/ORT parity inputs (max absolute error 1.216e-5). Artifact is 57,472,188 bytes, SHA-256 c9b93e139b7d01e22041f8884cef6b80ff67fbc0f16f7fef21b0dfacc55ce041.
- LaMa candidate comparison: quantized-vs-FP32 selected RGB mean absolute difference 0.703/255, max 14.577/255, p95 2.447/255 on a public-domain portrait with two synthetic damaged areas. Use the full FP32 model to retain the better measured output, despite package size.
- DDColor CPU default allocator observed 3.21 GB peak RSS. Disabling arena/memory-pattern and using BASIC optimization reduced fresh production-settings peak to 1,081,499,648 bytes; source graph/resolution unchanged. Separate comparison against ALL optimization measured RGB PSNR 87.04 dB. Adopted those settings and a 1,200 MiB headroom check.
- Task 4 implemented: new bitmap ownership, masked inpainting, source-L chroma reconstruction, YuNet/SwinIR face patch processing, persistent region/checkpoint metadata, re-edit resets recipe but retains protected-region companion. Real Android tests written; integration build pending.
- Usage-limit interruption resumed from files and ledger; completed exports/tests are not repeated.
- Native session lifetime gate completed: 20/20 core tests, including waiting cancellation and idempotent release.
- All five source models/part digests/full hashes/licenses pass the artifact gate. Initial debug APK audit passed with no Internet/network-state permissions.
- Initial debug and Android test APK assembly plus debug lint succeeded; invalid test import corrected. Lint: zero errors, 30 warnings.
- Independent review identified large-face downsampling, non-square mask probing and letterbox re-entry bridges. Implemented conservative face preservation, a shared aspect-aware mask status/erase probe and stroke termination at bounds. Targeted follow-up reports no remaining Critical/Important findings.
- Added Android evidence checks for protected checkpoint resume and re-edit, missing-companion rejection and cancellation during actual native inference. Total Android suite: 42 tests, runtime execution pending.
- First offline runtime pass exposed a non-void JUnit coroutine method, BooleanArray.any() checking array existence rather than selected pixels, and an assertion racing asynchronous preferences. Corrected each from observed failures; full 42/42 Android suite then passed offline in 161.965 seconds. All real repair/color/face graphs, combined engine, source/alpha and mask/region/cancellation safeguards passed. R8/sign/upgrade/publication checks remain.
- Optimized R8/resource-shrunk release and release lint passed (zero errors, 30 warnings). Signed v0.2.0 APK is 497,183,170 bytes, SHA-256 356beabcefeda72f6b39690120b444cd15200091188cde400836b8a30285409e; existing certificate retained, v3/16 KiB alignment and signed five-model/hash/license/no-network audit pass.
- Signed v0.1.0 → v0.2.0 upgrade retained the baseline completed project and gallery output. Installed APK hash matched exactly and package is non-debuggable. Offline native probes showed all five Ready; all four restoration tools plus painted mask completed a 512×512 gallery result, both source hashes unchanged, with result retained after Home/return. Evidence archived in docs/evidence/restoration-v0.2.0/. GitHub publication/public digest verification remains.
- Task 5 completed: code commit d8f86cbcbcd38438d782fd5fe39135865a0258c1 and annotated v0.2.0 tag pushed; signed APK and SHA256SUMS.txt published at https://github.com/rynnaqq/ImageEnhancer/releases/tag/v0.2.0 on 2026-10-02 10:40:15 UTC. Hosted asset digests match local hashes; unauthenticated public APK Content-Length and downloaded checksum content pass. Original v0.1.0 release and both assets preserved. Public verification archived in docs/evidence/restoration-v0.2.0/public-release.json. The restoration implementation and signed-publication scope are complete; broader physical-device/photo-quality, reference fusion, TIFF and huge-input qualification remain documented preview limits.
