# Bundled restoration models

The user reports that every restoration tool displays "model not available in this build" and requests all tools to work. The existing release ships only ESPCN; the four restoration cards never call the engine.

Implement independently selectable face restoration, learned colorization, automatic scratch repair, and brush-selected reconstruction. Bundle every model and license inside the APK. Model extraction and inference run offline, with checked hashes, CPU fallback, cancellation, and independent readiness/errors. Preserve the existing normal enhancement pipeline, signing identity, private originals, queue/checkpoints, and gallery export.

Restoration is opt-in. Existing version-1 recipes decode with these options disabled. Persist the options and normalized repair strokes in version-2 recipes, including saved plans and history. Run repair, colorization and face restoration before super-resolution. Colorization preserves original lightness and alpha. Repair changes only selected pixels, with a conservative automatic scratch detector and a brush/erase/clear editor. Store generated-region metadata alongside private results and carry it into re-editing; avoid applying face restoration over reconstructed regions again.

Use published, redistributable ONNX artifacts with archived licenses and pinned hashes. Candidates are OpenCV YuNet detection, OpenCV/Carve LaMa reconstruction, DDColor-tiny colorization, and a verified face restoration network. Host and Android inference determine the final artifacts; do not infer readiness from a file alone. Do not claim recovered colors or generated facial details are historically accurate. Keep reference-photo fusion and TIFF limitations separate from these enabled tools.

Release an update with an increased version/code, the existing private signing key, and GitHub APK/checksum assets once regression tests and actual offline inference pass. Preserve the public v0.1.0 release.
