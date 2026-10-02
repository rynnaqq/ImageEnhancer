---
license: apache-2.0
pipeline_tag: image-to-image
tags:
  - onnx
  - onnxruntime-web
  - image-colorization
  - ddcolor
---

# DDColor-tiny — fp16 ONNX (512×512)

An ONNX export of **DDColor-tiny** ([piddnad/DDColor](https://github.com/piddnad/DDColor),
ICCV 2023) for black-and-white / grayscale **image colorization**, exported for
in-browser inference with [onnxruntime-web](https://onnxruntime.ai/).

This is the model behind the **[Edge Tools](https://app.edgetools.io) Image
Colorizer** ([`/colorize-image`](https://app.edgetools.io/colorize-image)), which
runs it fully client-side — images are never uploaded anywhere.

## Files

| File | Size | What |
| --- | --- | --- |
| `ddcolor-tiny-fp16.onnx` | ~130 MB | DDColor-tiny, float16 **weights**, float32 I/O, fixed 512×512 input |

## Tensor contract

| | Name | Shape | dtype |
| --- | --- | --- | --- |
| Input | (first input) | `(1, 3, 512, 512)` | `float32` |
| Output | (first output) | `(1, 2, 512, 512)` | `float32` |

The weights are float16 but the **I/O boundary is plain float32**
(`keep_io_types=True`), so no fp16 tensor plumbing is needed and op support on the
ORT wasm execution provider stays maximal.

## Pre / post-processing

DDColor predicts **chroma only**; luminance comes from the source image. Using
CIE Lab in OpenCV's float convention (`L∈[0,100]`, `a,b∈~[-127,127]`, D65 white,
sRGB gamma — DDColor is trained against `cv2.cvtColor` float Lab):

1. Normalise input to `[0,1]` and compute the **original-resolution L** channel.
2. Resize to 512×512, rebuild a grayscale RGB from L (`a=b=0`, Lab→RGB) and feed
   it as `(1,3,512,512)` float32. **No ImageNet normalisation** (`do_normalize=False`).
3. The model returns **ab** as `(1,2,512,512)`.
4. Resize ab back to the original size, concatenate with the **original-resolution
   L**, and convert Lab→RGB. Only chroma is low-res; full-res luminance is preserved.

A reference JavaScript implementation of exactly this pipeline lives in the
`ddcolor.mjs` factory of the Edge Tools build (see Provenance).

## Provenance

Reproducibly built in CI from the upstream PyTorch checkpoint — nothing here is
hand-massaged:

- **Architecture code**: [`piddnad/DDColor`](https://github.com/piddnad/DDColor)
  pinned at `2adb63f2656ac41cbdf7b894cddd94121a3faf13`
  (`basicsr.archs.ddcolor_arch`, `encoder_name="convnext-t"`,
  `decoder_name="MultiScaleColorDecoder"`, `num_output_channels=2`,
  `last_norm="Spectral"`, `num_queries=100`, `num_scales=3`, `dec_layers=9`).
- **Weights**: [`piddnad/ddcolor_paper_tiny`](https://huggingface.co/piddnad/ddcolor_paper_tiny)
  `pytorch_model.bin`, SHA-256-verified
  (`8a1277bc90a1bfbb6d2d83933a9a6bc821931879ca93e26e4fcec12165d41fce`).
- **Export**: `torch.onnx.export`, **opset 17**, fixed 512×512 input, then weights
  cast to float16 via `onnxconverter_common.float16.convert_float_to_float16(
  ..., keep_io_types=True)`. `onnxsim` / symbolic-shape-infer are skipped — the
  input is fixed-size, so shapes are already static.
- **Built & published by**: the `edge-tools/wasm/ddcolor` CI project, which also
  runs a colorization test against this exact artifact before publishing it.

## License

**Apache-2.0**, inherited from the upstream DDColor project
([LICENSE](https://github.com/piddnad/DDColor/blob/master/LICENSE)).

## Citation

```bibtex
@inproceedings{kang2023ddcolor,
  title={DDColor: Towards Photo-Realistic Image Colorization via Dual Decoders},
  author={Kang, Xiaoyang and Yang, Tao and Ouyang, Wenqi and Ren, Peiran and Li, Lingzhi and Xie, Xuansong},
  booktitle={Proceedings of the IEEE/CVF International Conference on Computer Vision (ICCV)},
  year={2023}
}
```
