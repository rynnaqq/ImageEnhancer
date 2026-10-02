# PRD implementation coverage

The full professional-quality acceptance list is **not yet complete**. PRD §50
allows explicit technical dependencies; their plans and release gates are in
`TECHNICAL_DEPENDENCIES.md`. Status below describes source implementation; executed
checks and limits are recorded separately in `VALIDATION.md`.

| PRD sections | Implementation | Remaining gate |
|---|---|---|
| 1–2, 19, 39: native/offline/privacy | Kotlin/Compose, minSdk 29, no network permission, no account or telemetry. Private originals and projects; backups excluded. | Physical-device offline matrix. |
| 3–4: import/formats | System photo and multi-file picker, private original copies, individual import failure handling, explicit batch/reference choice. Standard Android decoders handle JPG/PNG/WebP/HEIC/BMP. | TIFF decoder; HEIC/vendor format fixtures. |
| 5: non-destructive edits | Normalized crop, 90° rotate, both flips, straighten; source bytes never overwritten. | Interactive crop handles are optional polish; current accessible sliders work. |
| 6–7, 35, 37: auto pipeline | Bounded image statistics, conditional denoise/deblur/tone/color/refine/SR planning, strength guardrails. UI shows detected conditions and planned stages. | Learned old-photo/face/damage/compression/motion/defocus analyzers and broad quality guardrails. |
| 8: manual | Resolution, blur/noise/detail, sharpening, all tone/color sliders, profiles and export options are functional. | Specialized learned restoration, face, colorization and inpainting controls remain unavailable with explanations. |
| 9, 36: reconstruction/identity | No face synthesis, fabricated reconstruction or unrelated-reference assumption. | Verified face/inpainting models, masks and identity qualification. |
| 10: comparison | Before/after divider, accessible slider and buttons, synchronized zoom/pan, fit and 100% preview. | Full-resolution region inspection for huge images. |
| 11–14: queue/background | Room states, single serialized worker, current/all cancellation, failed-item continuation, retry, atomic checkpoints, safe resume, foreground notification and API 35 timeout handling. | Physical lifecycle/screen-off/vendor battery matrix. |
| 15–18, 28–30, 34: AI/resources | Bundled checksummed/attributed ESPCN graph, steady real provider probes, local inference fallback, overlapping tiles, safe dimension policy and explicit sampling. No runtime model downloads. | Quality variants, QNN/vendor paths, streamed full-resolution assembly. |
| 20–25, 44: originals/output/projects | Immutable sources, private lossless output revisions, JPEG/PNG/WebP gallery exports, quality controls, metadata stripping, history, persisted settings, local share, continue from a previous output. | Output revision browser and external-output ownership across reinstalls are future extensions. |
| 26–27, 31–32: experience/errors | Restrained Material 3 UI, large photo previews, home/import/editor/queue/history/projects/settings, useful failure/retry/resume states. No made-up time estimates. | Real quality/profile benchmarks across device classes. |
| 33, 42–43: architecture/storage | Separate pure core, presentation, Room/DataStore, image I/O, AI and service modules. Originals/results/temporary/models separated. | Future decoder/streaming/model implementations. |
| 38, 40–41: metadata/accessibility/localization | English Android string resources, font-scaling layouts, icons/slider descriptions, standard touch controls, JPEG date/camera opt-in, no GPS copying. | Full TalkBack/large-font/RTL/localization audit; region viewer accessibility. |
| 45–49: acceptance/QA/done | Real installable application foundation and local enhancement workflow; tests target actual processing and data safety. | Do not claim professional-quality PRD completion until model dependencies and physical quality matrix pass. |
| 50: agent instructions | No mock AI processing, cloud substitutions, original overwrites or silent placeholder restoration controls. Every major outstanding model/format/resource capability has a concrete plan and visible status. | Continue release gates in the dependency document. |
