# Technical dependencies and release gates

This file applies the explicit dependency provision in PRD §50. The application
contains real local processing, not simulated inference. Version 0.2.0 implements
the four restoration tools with bundled graphs; the remaining rows describe quality,
device, format and scale gates that still prevent calling it the finished
professional restoration product.

| Capability | Current behavior | Concrete local implementation and acceptance gate |
|---|---|---|
| Professional super-resolution | Bundled ESPCN performs actual 3× luminance inference; 2×/4×/8× outputs resample that result. Chroma uses local interpolation. | Add a quality RGB restoration model through `ModelManager`, with explicit **weight** redistribution rights, SHA-256, tensor contract and a licensed fixture benchmark. Real-ESRGAN's source BSD license alone is insufficient evidence of a separate release-weight license. Verify native target scales, ringing, textures and portrait similarity on three hardware classes before enabling a quality variant. |
| Learned denoise/deblur | Functional edge-aware conventional denoise and restrained local detail recovery. The UI identifies these as filters. | Bundle separately verified learned denoise/deblur graphs with licensed weights, mobile tensor contracts and tiled residual-strength blending. Compare noisy/blurry fixtures against clean targets with texture/edge checks and mobile memory measurements. |
| Face detection/restoration | Bundled MIT YuNet detects and aligns faces. Apache-2.0 SwinIR adds general real-image neural detail to face regions, blends conservatively, skips overlap with previously reconstructed regions and processes at most 16 faces per pass. | SwinIR is not GFPGAN, reference fusion or an identity-aware face generator. Run independently licensed portrait, geometry, expression, false-detail and similarity review on the physical device matrix. Generated detail is not claimed to be historically accurate. |
| Old-photo damage, scratches and dust | A conservative local line heuristic selects likely scratches and feeds those masks to bundled FP32 LaMa. If no likely scratch is found, repair is skipped. | Validate scratches and dust separately from legitimate edges, fine texture and facial features on licensed real photographs and low/mid/high physical devices. The heuristic is not a general learned damage classifier. |
| Missing-area reconstruction | The editor provides normalized brush, erase and clear controls on the transformed preview. FP32 LaMa fills selected regions; unselected pixels remain exact and source alpha is preserved. Generated-region metadata survives re-editing and protects overlap from later face processing. | Complete real-photo seam, repeated-texture and identity-loss review across the physical device matrix. Full-resolution disk-backed region processing remains separate work. |
| Black-and-white colorization | Bundled DDColor512 predicts Lab chroma. The adapter retains source lightness and alpha, resizes chroma to original geometry and exposes strength blending. Existing recipes keep colorization off. | Complete portrait, landscape, skin-tone and historically ambiguous color review on licensed photographs and physical devices. Predicted colors are plausible outputs, not historical evidence. |
| Identity-aware multi-reference fusion | Users explicitly choose a main photo and store related references privately. Neural reference fusion is unavailable. | Package a reference encoder/fusion model; align related subjects, validate correspondence and feed reference tensors through a separately defined model module. Test mismatched subjects, conflicting lighting and identity preservation. Ordinary batches never become references automatically. |
| TIFF | Explicit decoder dependency. JPG/PNG/WebP/HEIC/BMP use Android local decoders. | Bundle libtiff through the NDK, including its license; implement strip/tile decoding to an owned raster source. Verify little/big endian, LZW/Deflate/JPEG/PackBits, grayscale/RGB/CMYK, multipage selection and malformed files without global bitmap allocations. |
| Full-resolution 48/108 MP processing | Bounded decoding, overlapping inference, memory checks, tile-size retry and honest sampling/scale notices. Output assembly currently requires one safe bitmap. | Add a disk-backed raster interface (`readRegion`, `writeRegion`, `readRows`) and streaming PNG/JPEG/WebP encoders. Use region decoding or tiled TIFF sources, persist per-tile completion and assemble rows without a full output bitmap. Keep source resolution; test 48/108 MP at 1×/2×/4×/8× under constrained heaps and low disk space. |
| Pixel inspection of very large files | Synchronized zoom/pan and 100% **preview** inspection. Comparison previews are capped at four million pixels. | Add a region/tile image viewer using the same normalized transform for both images. Load visible full-resolution regions, evict invisible tiles and expose actual pixel scale with accessible controls. |
| Vendor GPU/NPU paths | Actual CPU/XNNPACK/NNAPI probes, numerical validation and CPU fallback. No universal acceleration promise. | Add QNN/vendor providers only for supported chipsets and distributable native SDKs; benchmark each model/provider on actual devices, test driver failures and memory/thermal pressure. Persist provider compatibility with OS/model/driver identifiers. |
| Quality/Balanced/Fast variants | All three profiles exist and alter work/tile policy. The same task-specific graphs are used by each profile. | Benchmark and package alternate quality/balanced/fallback weights only when measured quality and resource results justify them. Selection must remain local and never trigger a runtime download. |
| Storage, memory and cancellation during model initialization | Every model or model part is bundled, length/hash checked and assembled privately. Advanced CPU sessions are serialized. DDColor and LaMa require roughly 1.2 GiB of available memory and fail safely below their declared headroom. | Exercise low-storage, cancellation, OS pressure and thermal interruption during every large extraction, probe and inference boundary on physical devices. Consider progress/checkpoints for long first initialization. |
| Device-class and quality release qualification | Twenty core tests, final debug/test assemblies, both lint checks (zero errors, 30 warnings), model/hash/license/no-network audits and 42 offline API 35 x86_64 Android tests pass. The optimized signed release passed v3 signature, 16 KiB alignment, same-key v0.1.0 upgrade, installed-hash/non-debuggable checks, all-five-model native probes and an all-four-tools signed-app workflow. The public v0.2.0 preview and its checksums are independently retrievable and match the verified artifact. | Run the PRD §46–47 licensed photo suite on physical ARM low/mid/high devices: airplane mode, screen off, minimize, OS kill, service timeout, full queue, thermal events, low RAM/disk, accelerator paths, huge inputs and identity checks. Review actual before/after images; successful x86_64 graph execution is insufficient. |

## Evidence and sources

- `app/src/main/assets/models/manifest.json` pins ESPCN, YuNet, SwinIR,
  DDColor512 and FP32 LaMa with complete and per-part SHA-256 hashes, byte sizes,
  tensor contracts, upstream revisions and archived license paths. The source
  artifact gate validates these records and currently passes.
- Full license and provenance files are bundled under
  `app/src/main/assets/licenses/`; the in-app license view reads the catalog and
  archive rather than naming only ESPCN.
- [ONNX Model Zoo model card](https://huggingface.co/onnxmodelzoo/super-resolution-10)
  documents the Y-channel preprocessing and trained 3× output.
- [ONNX Runtime XNNPACK](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
  and [NNAPI](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)
  document provider support and device-dependent behavior.
- [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
  and [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
  govern the background service. Resume is explicitly initiated from the foreground.

Model downloads are permitted during development to build the installed package.
There is no network or download code in the installed application.

Current v0.2.0 execution and release evidence is tracked in
[RESTORATION_VALIDATION.md](RESTORATION_VALIDATION.md). The complete 42-test Android
run is archived in
[android-tests.txt](evidence/restoration-v0.2.0/android-tests.txt). The v0.1.0
evidence archive remains in `VALIDATION.md` and `docs/evidence/`.

The verified signed artifact is `dist/LocalPhotoEnhancer-v0.2.0.apk`, 497,183,170
bytes, SHA-256
`356beabcefeda72f6b39690120b444cd15200091188cde400836b8a30285409e`.
It retains certificate SHA-256
`921853fe2ce9460e7d4304d8f4e146ad045c81b8144e111fc30c50a22da337d0`,
uses APK Signature Scheme v3 and passes 16 KiB zip-alignment verification. The signed
package passed the same five-model/full-part hash, license and no-network-permission
audit as source.

The public preview is [GitHub release v0.2.0](https://github.com/rynnaqq/ImageEnhancer/releases/tag/v0.2.0),
release ID `401728614`, published `2026-10-02T10:40:15Z`. The unauthenticated public
API reports the verified SHA-256, public `HEAD` reports the exact 497,183,170-byte
content length, and the downloaded 96-byte `SHA256SUMS.txt` matches the expected
content byte-for-byte. The remote annotated tag peels to code commit
`d8f86cbcbcd38438d782fd5fe39135865a0258c1`. These checks are archived in
[`public-release.json`](evidence/restoration-v0.2.0/public-release.json). The earlier
v0.1.0 release (ID `401487463`) and both original assets, sizes and hashes were
preserved.

## Bundled graph contract

The archived upstream card includes a generic statement about dynamic image sizes.
The actual checksummed graph bundled here accepts **[1, 1, 224, 224]** and produces
**[1, 1, 672, 672]**. The adapter centers smaller tiles using reflection padding,
then crops the corresponding 3× output region. It does not resize input tiles.
Reducing tile cores reduces staging buffers and work between cancellation checks;
it does not reduce the graph's fixed activation memory. The current memory policy
reserves 32 MiB for inference/workspace and safely reports insufficient memory
instead of claiming that arbitrarily small tiles can solve every allocation failure.

The restoration bank uses explicit CPU sessions and opens advanced graphs one at a
time. YuNet accepts a padded BGR tensor and provides boxes plus five landmarks;
SwinIR enhances aligned 96×96 face inputs at 4× before inverse-warp blending.
DDColor512 predicts Lab `ab` channels while the adapter retains original `L` and
alpha. FP32 LaMa receives a 512×512 context image and mask; its output is composited
only inside the selected source mask with original alpha. The manifest's declared
memory gates are about 1.17 GiB for DDColor and 1.12 GiB for LaMa, so inclusion in
the APK does not imply that every Android 10+ device can run every module.
