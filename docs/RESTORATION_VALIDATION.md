# v0.2.0 restoration validation

Verification date: 2026-10-02. The v0.1.0 record remains in [VALIDATION.md](VALIDATION.md).

## Change and scope

The four restoration cards previously opened hardcoded unavailable-model dialogs.
They now persist real, opt-in recipes and execute bundled CPU inference: YuNet and
SwinIR for face-region detail, DDColor for learned chroma, and FP32 LaMa for automatic
scratch masks and brush-selected missing areas. Each model has independent readiness
and failure reporting. No runtime download or Internet permission is added.

Old recipes retain disabled restoration defaults. The repair stage preserves every
unselected pixel and source alpha. Generated-region companions survive checkpoint
resume and re-editing; overlapping faces are protected. Face alignments that would
downsample existing detail are preserved instead of sent through the 96-pixel model.
Brush selection respects transformed image aspect and ends at the image boundary.

## Artifact and host checks

The source bank contains five checked models, totaling 401,431,222 bytes. Models
larger than 32 MiB use checked asset parts; the assembled file has a separate complete
SHA-256. The [manifest](../app/src/main/assets/models/manifest.json) records pinned
sources, exact hashes, contracts and archived licenses. SwinIR is exported from
pinned official Apache-2.0 code and weights using [export-swinir.py](../scripts/export-swinir.py).
Three PyTorch/ONNX parity inputs passed; maximum absolute error was 1.216e-5.

The source gate first failed because four required model IDs were absent, then
passed after bundling. All candidate graphs ran on stock ONNX Runtime 1.30 CPU with
finite outputs and the required shapes. Host checks use the public-domain NASA
astronaut image with synthetic damage, not private photographs.

| Model | Artifact bytes | Host peak process RSS | CPU contract |
|---|---:|---:|---|
| ESPCN | 240,078 | Baseline validation | 224-pixel luminance to 3× luminance |
| YuNet | 229,738 | 72 MiB | BGR 0–255, dynamic padded dimensions |
| SwinIR | 57,472,188 | 690 MiB | RGB 96×96 to 384×384 |
| DDColor | 135,444,402 | 1,031 MiB | Neutral RGB 512×512 to two Lab chroma planes |
| FP32 LaMa | 208,044,816 | 952 MiB | RGB plus selected mask, fixed 512×512 |

DDColor's default allocator reached 3.21 GB. Disabling its CPU arena and memory
pattern and selecting BASIC graph optimization reduced the fresh production-settings
benchmark to 1,081,499,648 bytes. Model weights and resolution are unchanged. A
separate output comparison against ALL optimization measured RGB PSNR 87.04 dB.
Sessions are serialized for their entire native lifetime. Advanced modules check
available memory before loading: 850 MiB for SwinIR, 1,200 MiB for DDColor and
1,150 MiB for LaMa. These checks are safeguards, not physical-device qualification.

The smaller quantized LaMa candidate differed from FP32 by mean 0.703/255 and
maximum 14.577/255 in selected RGB pixels. The FP32 graph is shipped to retain the
better measured output. Timing depends on host load and is not a phone benchmark.

Evidence: [DDColor](evidence/restoration-v0.2.0/ddcolor-cpu.json),
[SwinIR](evidence/restoration-v0.2.0/face-swinir-cpu.json),
[LaMa FP32](evidence/restoration-v0.2.0/lama-fp32-cpu.json),
[LaMa comparison candidate](evidence/restoration-v0.2.0/lama-int8-cpu.json),
[YuNet](evidence/restoration-v0.2.0/yunet-cpu.json), and
[export provenance/parity](../app/src/main/assets/licenses/SWINIR-PROVENANCE.json).

## Integration checks

The pure core suite has 20 passing tests with no failures or skips. It includes
recipe/planner compatibility, normalized paint/erase masks, Lab conversion,
selected-only compositing and fair cancellation-aware native session leases.

The first debug and test APK assemblies and debug lint succeeded after removing
an invalid test import. Lint reported zero errors and 30 warnings. The debug APK
passed model-part/full-hash/license and offline-permission audits.

Final debug and test assemblies succeeded. The complete offline Android suite
passed **42 tests, zero failures**, in **161.965 seconds** on the owned
`LocalEnhancer` API 35 x86_64 emulator with 3,072 MiB RAM. Airplane mode was on,
Wi-Fi off and the mobile-data setting off. Every model ran locally; the combined
repair → colorization → face stage plan completed and exported a gallery result.

| Suite | Passed | Evidence |
|---|---:|---|
| Existing project/queue/SR/migration/UI coverage | 22 | Source ownership, gallery formats, background processing, concurrency and old recipes |
| Bundled restoration | 6 | Exact model assembly, real repair/chroma/face outputs, source/alpha preservation and combined engine completion |
| Face math and large-detail guard | 5 | Detector heads/NMS/alignment and exact preservation of a large face without opening SwinIR |
| Restoration recipe | 1 | New round-trip and version-1 disabled defaults |
| Restoration safety | 3 | Resume and re-edit protection, missing-companion rejection and termination during active native LaMa inference |
| Restoration UI and geometry | 5 | Real switches, portrait/landscape erasing and letterbox boundary behavior |

The first run exposed an invalid non-void coroutine test signature, a mask probe
that checked array existence instead of selected pixels, and an assertion racing
asynchronous preferences. The signature, probe and test synchronization were
corrected; the entire suite then passed. The fully-erased regression was observed
failing before the predicate fix and passing afterward.

Evidence: [complete Android output](evidence/restoration-v0.2.0/android-tests.txt),
[device settings](evidence/restoration-v0.2.0/android-environment.txt),
[engine stages](evidence/restoration-v0.2.0/android-engine-log.txt),
[initial failures](evidence/restoration-v0.2.0/android-first-run.txt),
[build/lint](evidence/restoration-v0.2.0/debug-build-lint.txt),
[source model audit](evidence/restoration-v0.2.0/source-model-audit.txt), and
[core results](evidence/restoration-v0.2.0/core-tests.json).

Saved Android outputs from the public fixture:
[selected repair](evidence/restoration-v0.2.0/android-repair.png),
[colorization](evidence/restoration-v0.2.0/android-color.png), and
[face detail](evidence/restoration-v0.2.0/android-face.png). They were visually
inspected; one fixture does not qualify broader photographic quality. The color
test also verifies retained geometry/alpha and mean Lab-lightness error below 2.5;
RGB gamut clipping and quantization prevent an exact encoded-lightness guarantee.

## Signed release and upgrade

The optimized R8/resource-shrunk release and release lint passed. Release lint
reported zero errors and 30 warnings. The final APK is **497,183,170 bytes**
(474.15 MiB), versionCode 2 / versionName 0.2.0, with SHA-256:
`356beabcefeda72f6b39690120b444cd15200091188cde400836b8a30285409e`.

APK Signature Scheme v3 and 16 KiB zip alignment verify successfully. The signing
certificate is the existing v0.1.0 identity, SHA-256
`921853fe2ce9460e7d4304d8f4e146ad045c81b8144e111fc30c50a22da337d0`.
The signed package passes all five complete/part model hashes, archived licenses
and the absence of Internet/network-state permissions.

On the same owned offline emulator, installing the signed v0.2.0 APK over signed
v0.1.0 retained the completed baseline project and its 640×480 gallery result.
The installed package's SHA-256 matches the final artifact exactly and its flags
exclude `DEBUGGABLE`. Preparing the model bank performed real native inference
probes and showed all five models Ready in the optimized build.

The native editor enabled automatic scratch repair, face detail and learned
colorization, then accepted a painted reconstruction mask. The combined job
completed a 256×256 public fixture as a 512×512 JPEG in
`Pictures/LocalPhotoEnhancer`; the result remained available after Home and return.
Both source-file SHA-256 values remained unchanged. The saved output was visually
inspected. These checks validate execution and preservation on the QA emulator;
they do not qualify photographic accuracy or physical-device performance.

Evidence: [release build/lint](evidence/restoration-v0.2.0/release-build-lint.txt),
[signature and APK audit](evidence/restoration-v0.2.0/signing-audit.txt),
[signed native workflow](evidence/restoration-v0.2.0/signed-native-workflow.txt),
[five Ready models](evidence/restoration-v0.2.0/signed-models-all-five.xml),
[retained project](evidence/restoration-v0.2.0/upgrade-after.png),
[painted mask](evidence/restoration-v0.2.0/signed-mask.png),
[completed result](evidence/restoration-v0.2.0/signed-completed-result.png), and
[gallery image](evidence/restoration-v0.2.0/signed-gallery-result.jpg).

GitHub publication and public asset digest verification remain pending.

## Preview limits

SwinIR is a general real-image detail model applied to face regions; it does not
recover identity or prove historical accuracy. Large faces are preserved by the
source-resolution guard. Automatic scratch detection is conservative classical
mask detection; LaMa performs the learned filling. Brush repair rejects selections
covering more than 45% of a photo. Color and reconstructed content are predictions.

Physical ARM phones, photographic quality across diverse inputs, acceleration,
thermal pressure and 48/108 MP workloads remain unqualified. Reference-photo fusion,
TIFF decoding and huge-raster streaming remain explicit dependencies in
[TECHNICAL_DEPENDENCIES.md](TECHNICAL_DEPENDENCIES.md).
