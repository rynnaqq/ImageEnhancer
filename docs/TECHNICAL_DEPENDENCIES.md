# Technical dependencies and release gates

This file applies the explicit dependency provision in PRD §50. The application
contains real local processing, not simulated inference. These outstanding items
prevent describing this build as the finished professional restoration product.
Unavailable restoration tools have explanatory status rows in the editor/settings.

| Capability | Current behavior | Concrete local implementation and acceptance gate |
|---|---|---|
| Professional super-resolution | Bundled ESPCN performs actual 3× luminance inference; 2×/4×/8× outputs resample that result. Chroma uses local interpolation. | Add a quality RGB restoration model through `ModelManager`, with explicit **weight** redistribution rights, SHA-256, tensor contract and a licensed fixture benchmark. Real-ESRGAN's source BSD license alone is insufficient evidence of a separate release-weight license. Verify native target scales, ringing, textures and portrait similarity on three hardware classes before enabling a quality variant. |
| Learned denoise/deblur | Functional edge-aware conventional denoise and restrained local detail recovery. The UI identifies these as filters. | Bundle verified NAFNet/Restormer/SwinIR candidates after license and ONNX operation checks; implement separate denoise/deblur model contracts and tiled residual-strength blending. Compare noisy/blurry fixtures against clean targets with texture/edge checks and mobile memory measurements. |
| Face detection/restoration | No face replacement or synthetic face generation. Restoration is unavailable. | First add YuNet detection with its directory MIT license, landmark alignment and face masks. Add explicitly licensed identity-preserving restoration weights, blend restored faces into the source, and test geometry, expression, false detail and similarity with independently licensed portraits. Require references to refer to the same person. |
| Old-photo damage, scratches and dust | Tone, faded-color and clarity filters work. Dedicated scratch/dust repair is unavailable. | Bundle a damage detector and restoration graph; add local editable damage masks, tile overlap and texture-preserving masked blending. Validate scratch/dust fixtures separately from legitimate edges and facial features. |
| Missing-area reconstruction | Unavailable; no unverified pixels are presented as reconstructed results. | Add a locally bundled, licensed LaMa-equivalent inpainting graph; implement mask painting and a persisted generated-region mask in each output revision. Do not repeatedly restore already generated face regions. Validate seams, repeated textures and identity loss. |
| Black-and-white colorization | Monochrome detection works. Colorization is unavailable. | Add a licensed local colorization model with known Lab/RGB preprocessing, strength mixing, chroma-preservation controls and portrait/natural-color fixtures. Offer optional auto colorization only after validation. |
| Identity-aware multi-reference fusion | Users explicitly choose a main photo and store related references privately. Neural reference fusion is unavailable. | Package a reference encoder/fusion model; align related subjects, validate correspondence and feed reference tensors through a separately defined model module. Test mismatched subjects, conflicting lighting and identity preservation. Ordinary batches never become references automatically. |
| TIFF | Explicit decoder dependency. JPG/PNG/WebP/HEIC/BMP use Android local decoders. | Bundle libtiff through the NDK, including its license; implement strip/tile decoding to an owned raster source. Verify little/big endian, LZW/Deflate/JPEG/PackBits, grayscale/RGB/CMYK, multipage selection and malformed files without global bitmap allocations. |
| Full-resolution 48/108 MP processing | Bounded decoding, overlapping inference, memory checks, tile-size retry and honest sampling/scale notices. Output assembly currently requires one safe bitmap. | Add a disk-backed raster interface (`readRegion`, `writeRegion`, `readRows`) and streaming PNG/JPEG/WebP encoders. Use region decoding or tiled TIFF sources, persist per-tile completion and assemble rows without a full output bitmap. Keep source resolution; test 48/108 MP at 1×/2×/4×/8× under constrained heaps and low disk space. |
| Pixel inspection of very large files | Synchronized zoom/pan and 100% **preview** inspection. Comparison previews are capped at four million pixels. | Add a region/tile image viewer using the same normalized transform for both images. Load visible full-resolution regions, evict invisible tiles and expose actual pixel scale with accessible controls. |
| Vendor GPU/NPU paths | Actual CPU/XNNPACK/NNAPI probes, numerical validation and CPU fallback. No universal acceleration promise. | Add QNN/vendor providers only for supported chipsets and distributable native SDKs; benchmark each model/provider on actual devices, test driver failures and memory/thermal pressure. Persist provider compatibility with OS/model/driver identifiers. |
| Quality/Balanced/Fast variants | All three profiles exist and alter work/tile policy. One trained baseline is bundled. | Package verified quality/balanced/fallback weights, with separate metadata/checksums and first-run storage accounting. Select based on measured quality, resources and backend support, never a runtime download. |
| Storage and cancellation during model initialization | Bundled extraction is checked and native probe runs are cancellable. | Extend extraction to all eventual large assets with per-file progress/checkpoints and low-storage tests; exercise cancellation during every probe and extraction boundary on physical devices. |
| Device-class and quality release qualification | Core synthetic tests plus available Android emulator tests. | Run the PRD §46–47 licensed photo suite on physical low/mid/high devices: airplane mode, screen off, minimize, OS kill, service timeout, full queue, thermal events, low RAM/disk, accelerator paths and identity checks. Review actual before/after images; successful graph execution is insufficient. |

## Evidence and sources

- Bundled model license is explicitly Apache-2.0 in the archived model card
  `app/src/main/assets/licenses/ESPCN-MODEL-CARD.md` and included full license.
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

## Bundled graph contract

The archived upstream card includes a generic statement about dynamic image sizes.
The actual checksummed graph bundled here accepts **[1, 1, 224, 224]** and produces
**[1, 1, 672, 672]**. The adapter centers smaller tiles using reflection padding,
then crops the corresponding 3× output region. It does not resize input tiles.
Reducing tile cores reduces staging buffers and work between cancellation checks;
it does not reduce the graph's fixed activation memory. The current memory policy
reserves 32 MiB for inference/workspace and safely reports insufficient memory
instead of claiming that arbitrarily small tiles can solve every allocation failure.
