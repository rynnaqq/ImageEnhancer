# Implementation log

- 2026-10-01: Read all 50 PRD sections. Workspace initially contained only the PRD.
- Plan: `docs/superpowers/plans/2026-10-01-local-photo-enhancer.md`.
- Execution: user requested direct execution of the supplied PRD. Main agent owns
  Android integration; bounded toolchain/model research delegated per AGENTS.md.
- Toolchain: SDK 35 and Gradle 8.9 cached; JDK 17 bootstrapped locally.
- Scope rule: apply PRD section 50 for features requiring unverified model weights,
  decoders or hardware. Such features must have visible dependency status and a
  concrete implementation/validation plan; they cannot claim simulated success.
- 2026-10-02: Resumed after usage interruption. Native Compose editor, project store,
  foreground queue, local filters, bundled model and integration tests are present.
  The first debug build succeeded. Device tests exposed a fixed 224x224 model
  contract (incorrectly treated as dynamic), plus re-edit/reference ownership bugs.
  Those failures were reproduced before the fixes; final verification is recorded in VALIDATION.md.
- Resume fixes preserve navigation state, invalidate checkpoints after recipe edits,
  keep saved result dimensions during export/sharing, and retain paused checkpoints
  when current memory is insufficient. Inference working reserve is now 32 MiB,
  consistent with bundled metadata. At the v0.1.0 baseline, advanced model and
  large-raster work remained explicitly tracked in TECHNICAL_DEPENDENCIES.md under
  PRD section 50.
- Offline API 35 suite: 16 tests passed; the lifecycle test first required scrolling
  to a lazily composed action. Once it reached processing, it reproduced a foreground
  startup crash. Bytecode inspection showed pinned AndroidX Core 1.15 filters out
  API 35's mediaProcessing type. The service now uses the platform overload available
  since minSdk 29. A real background job then completed its 640×480 private result
  and gallery export. The test now waits for gallery export to settle after the
  private completion commit.
- State review reproduced two deterministic autosave/enqueue races using real Room
  reads/writes. Conditional column updates now preserve queued state and the latest
  recipe. Gallery writes are guarded by revision, output path and completed state;
  queue claims are atomic and cancel-all reaches the service independently of UI
  flow timing. Timeout/destruction permanently retire the worker.
- A follow-up review caught stale worker cleanup deleting a new revision's
  checkpoint. Cleanup now shares the repository file-mutation lock and checks the
  settled revision/state/output before removing files. Regression tests cover this
  interleaving; read-only follow-up found no further material issue in that scope.
- v0.1.0 final verification: fresh 10/10 core tests; 22/22 offline API 35 Android tests.
  Debug/test/release APKs build; both lint variants have zero errors and 29 warnings;
  both APK model/license/network-permission audits pass. Native picker-to-gallery
  operation produced 640×480 from the 320×240 synthetic source; host/public/private
  original hashes match. Screenshots, hashes and command output are in docs/evidence.
  Physical-device and professional restoration qualification remain release gates.
- Release preparation: generated a dedicated private 4096-bit RSA distribution
  key, retained it under the ignored .signing directory with a Windows-encrypted
  password, aligned/signed the unchanged optimized APK, and verified its signature,
  checksum, model/licenses and offline permissions. Repeated signing retained
  the key and produced the same checksum; all 99 original payload entries match.
  Fresh core verification passed 10/10; after a cold-boot emulator startup ANR
  and System UI warning settled, the full offline Android suite passed 22/22.
  Installed the exact signed APK and checked its on-device hash and non-debuggable
  flags. Native picker/auto/background/compare/gallery workflow passed and preserved
  the synthetic original. v0.1.0 is a preview with outstanding qualification and
  advanced-model dependencies recorded in the release notes.
- 2026-10-02 v0.2.0 restoration implementation: recipe schema version 2 keeps all
  restoration disabled for older projects and adds persisted face/color/repair
  controls plus normalized reconstruction strokes. The editor provides brush,
  erase and clear against the transformed preview and clears incompatible masks
  after crop or orientation changes.
- The bundled bank now contains ESPCN, YuNet, SwinIR face-region detail,
  DDColor512 colorization and FP32 LaMa repair. Manifest schema version 2 records
  complete and per-part lengths/hashes, tensor contracts, provenance and licenses;
  the source artifact gate passes. Large graphs assemble into private checked files,
  and advanced CPU sessions execute serially. DDColor and LaMa require roughly
  1.2 GiB of available memory and report an actionable failure below that headroom.
- Restoration behavior is intentionally bounded: automatic scratch repair uses a
  conservative heuristic mask before LaMa; manual LaMa compositing preserves exact
  unselected pixels and source alpha. DDColor retains original Lab lightness and
  alpha. Generated-region metadata survives re-editing and prevents face processing
  from touching overlapping generated areas. Face processing is capped at 16 regions
  per pass.
- SwinIR is a general real-image neural detail enhancer used on aligned face regions.
  It is not GFPGAN, identity-aware restoration or reference fusion, and generated
  detail and color are not claimed to be historically accurate.
- v0.2.0 verification: 20 core tests pass; final debug/test assemblies pass; initial
  debug lint has zero errors and 30 warnings. The five-model complete/part SHA-256,
  archived-license and no-network audit passes.
- The first 37-test Android attempt exposed three test/integration defects: one test
  method returned a non-`Void` Kotlin value, a Boolean mask used `.any()` instead of
  checking for selected `true` pixels, and a DataStore assertion raced its asynchronous
  update. Those defects were fixed before the full rerun.
- The complete 42-test offline Android suite passed in 161.965 seconds on the owned
  API 35 x86_64 AVD with 3072 MiB RAM, airplane mode 1, Wi-Fi 0 and mobile data 0.
  Real LaMa, DDColor and YuNet/SwinIR paths and the combined all-tools engine passed.
  Coverage includes source/alpha/exact unselected pixels, version-1 recipes,
  generated-region resume and re-edit, missing-metadata rejection, active LaMa
  `RunOptions` native cancellation, large-face protection, and portrait/landscape,
  fully-erased and boundary mask regressions. Evidence is in
  `docs/RESTORATION_VALIDATION.md` and
  `docs/evidence/restoration-v0.2.0/android-tests.txt`.
- The optimized R8/resource-shrunk v0.2.0 release and release lint pass with zero
  errors and 30 warnings. The release was signed with the existing v0.1.0 certificate
  SHA-256 `921853fe2ce9460e7d4304d8f4e146ad045c81b8144e111fc30c50a22da337d0`.
  APK Signature Scheme v3 and 16 KiB zip alignment pass.
- `dist/LocalPhotoEnhancer-v0.2.0.apk` is 497,183,170 bytes with SHA-256
  `356beabcefeda72f6b39690120b444cd15200091188cde400836b8a30285409e`.
  Its five model/full-part hashes, archived licenses and lack of network permission
  passed the signed-package audit.
- A same-key v0.1.0 → v0.2.0 upgrade installed successfully and retained the completed
  baseline project and gallery result. The installed APK hash matched the signed file
  exactly and the package had no `DEBUGGABLE` flag.
- On the owned offline LocalEnhancer API 35 x86_64 emulator with 3072 MiB RAM, the
  signed app ran real native probes and reported all five models Ready. Through native
  UI it enabled all four restoration tools, accepted a painted reconstruction mask and
  processed a 256 px source to a completed 512×512 result. Final source, UI and gallery
  proof is archived under `docs/evidence/restoration-v0.2.0/`.
- GitHub publication remains pending. Physical ARM-device photo quality, thermal,
  huge-input and broader device-matrix qualification remain open, as do reference
  fusion and TIFF. The v0.1.0 evidence and public release are unchanged.
