# PRD — Local Photo Enhancer

## 1. Product Overview

**Product name:** Local Photo Enhancer  
**Platform:** Android  
**Minimum Android version:** Android 10 (API 29)  
**Primary principle:** 100% on-device photo enhancement with no cloud processing  
**Connectivity requirement:** None after installation; the application must work completely offline  
**Product positioning:** Professional-quality AI photo enhancement and restoration that runs directly on the user's Android phone.

### Product concept

Local Photo Enhancer is an Android application that uses locally bundled AI models to improve photographs without uploading the user's images anywhere.

The application supports two interaction styles:

**Auto Enhance**  
The user selects one or more photos and presses one button. The application analyzes each image and automatically determines which enhancement operations are appropriate.

**Manual Enhance**  
The user can control every major enhancement operation individually.

The application supports all common types of photography rather than being limited to portraits or old family photographs.

Primary enhancement capabilities:

- Quality enhancement and upscaling
- Blur reduction / deblurring
- Noise reduction
- Lighting correction
- Color correction
- Sharpening
- Face restoration
- Old photograph restoration
- Scratch and dust removal
- Faded-color restoration
- Black-and-white photo colorization
- Reconstruction of severely damaged or missing areas
- Multi-photo reference processing
- Batch processing

The product prioritizes **maximum achievable image quality** over processing speed. Processing time is expected to vary according to the capabilities of the user's phone.

---

# 2. Goals

## Primary goals

1. Produce visibly improved photographs using local AI models.
2. Keep all image processing on the Android device.
3. Provide a one-click automatic workflow while retaining professional manual controls.
4. Preserve the identity and important visual characteristics of the original image.
5. Support very large input photographs subject to available device storage and memory.
6. Support single-image, batch, and multi-reference workflows.
7. Continue long-running processing while the user temporarily leaves the application, subject to Android OS restrictions.
8. Never overwrite the original photograph.
9. Provide transparent Before/After comparison.
10. Make the application simple enough for a normal user despite the complexity of the underlying AI pipeline.

## Non-goals

The first version is not intended to be:

- A social network
- A cloud photo backup service
- A web-based AI image generator
- A photo-sharing platform
- A subscription-dependent cloud AI service
- An application that requires an account or login

---

# 3. Core Product Requirements

## 3.1 Supported input formats

The application must support:

- JPG / JPEG
- PNG
- WebP
- HEIC / HEIF
- BMP
- TIFF

The implementation must preserve the highest practical source quality during decoding and processing.

For formats that Android's standard image decoder does not fully support, use a bundled local decoder rather than requiring an online service.

---

# 4. Photo Import

The user must be able to import:

### Single photo

`Add Photo → Select Photo → Edit`

### Multiple photos

`Add Photos → Multi-select → Processing Mode`

After multi-selection, the user chooses:

**Batch Mode**  
Each image is processed independently.

**Reference Mode**  
Multiple images are supplied together as references for a single restoration/enhancement operation.

**Both modes must be supported.**

Examples of Reference Mode:

- Several photographs of the same person can provide additional visual information for face restoration.
- Several images of the same subject can be used to improve consistency.
- Multiple related photographs can help the restoration pipeline distinguish real details from compression artifacts.

The system must not automatically treat unrelated photographs as references.

---

# 5. Editing Before AI Processing

Before enhancement begins, the user can optionally:

- Crop
- Rotate
- Flip
- Straighten

The original image must remain untouched.

Edits performed before AI enhancement become part of a non-destructive project state rather than replacing the original file.

---

# 6. Auto Enhance

## 6.1 User experience

The primary workflow should be extremely simple:

`Add Photo → Auto Enhance → Processing → Before/After → Save`

The user should not be required to understand AI models.

## 6.2 Automatic analysis

When Auto Enhance begins, the application analyzes the image for:

- Resolution
- Compression artifacts
- Blur
- Motion blur
- Defocus blur
- Noise
- Exposure
- Underexposure
- Overexposure
- Contrast
- Color cast
- Saturation
- Faded colors
- Scratches
- Dust
- Tears / damaged regions
- Missing image areas
- Black-and-white photography
- Faces
- Face quality
- Old-photo characteristics

The analyzer produces an internal enhancement plan.

Example:

```text
Input:
Low-resolution + blurry + noisy + dark portrait

Automatic plan:
1. Decode and normalize
2. Denoise
3. Deblur
4. Lighting correction
5. Face restoration
6. Fine detail reconstruction
7. 4× super-resolution
8. Final color/contrast correction
```

The pipeline must be conditional.

Do not blindly execute every model on every image.

---

# 7. Auto Enhance Pipeline

The AI orchestrator should dynamically determine the appropriate processing sequence.

Preferred conceptual pipeline:

```text
INPUT
  ↓
Image Analysis
  ↓
Preprocessing
  ↓
Noise Detection ─────→ Denoise if required
  ↓
Blur Detection ──────→ Deblur if required
  ↓
Exposure Analysis ───→ Lighting correction if required
  ↓
Color Analysis ──────→ Color correction if required
  ↓
Old Photo Analysis ──→ Restoration if required
  ↓
Damage Analysis ─────→ Scratch / repair / reconstruction if required
  ↓
Face Detection ──────→ Face restoration if applicable
  ↓
Colorization ────────→ If photograph is monochrome and auto-colorization is enabled
  ↓
Super Resolution
  ↓
Final Refinement
  ↓
OUTPUT
```

The orchestrator must prevent destructive processing chains.

For example, the system should avoid:

```text
Extreme sharpening
      +
Heavy denoise
      +
Multiple aggressive restoration passes
```

when that combination would create artificial-looking details.

---

# 8. Manual Enhancement

The user must be able to manually control all major enhancement functions.

## 8.1 Resolution

Upscale options:

- 1×
- 2×
- 4×
- 8×

The application should automatically prevent output dimensions from exceeding device-safe memory limits.

If 8× processing is technically possible but would exceed memory, the application must explain the limitation and provide the highest safe option rather than crashing.

## 8.2 Deblur

Controls:

- Deblur strength: 0–100
- Blur type / automatic detection where supported
- Detail preservation

## 8.3 Denoise

Controls:

- Denoise strength: 0–100
- Fine-detail preservation

## 8.4 Sharpen

Controls:

- Sharpen strength: 0–100
- Edge preservation

The implementation must avoid obvious halos and over-sharpening.

## 8.5 Lighting

Controls:

- Exposure
- Brightness
- Contrast
- Highlights
- Shadows
- White point
- Black point
- Gamma

## 8.6 Color

Controls:

- Temperature
- Tint
- Saturation
- Vibrance
- Hue
- Color balance
- Faded-color restoration

## 8.7 Restoration

Controls:

- Scratch removal
- Dust removal
- Damage restoration
- Missing-area reconstruction
- Texture recovery
- Old-photo enhancement
- Faded image restoration

## 8.8 Face enhancement

Controls:

- Face restoration
- Face detail strength
- Skin/detail preservation
- Identity preservation

Face restoration must prioritize similarity to the original face over cosmetic transformation.

The system must not intentionally turn a heavily damaged face into an unrelated synthetic face.

## 8.9 Colorization

For monochrome photographs:

- Colorize
- Colorization strength
- Natural color preservation

Auto Enhance must be capable of detecting black-and-white photographs and applying colorization according to the application's automatic enhancement logic.

---

# 9. Damaged Photo Reconstruction

The restoration system may reconstruct missing or heavily damaged regions.

Examples:

```text
Old photo
↓
Large scratch across face
↓
Damage detection
↓
Local reconstruction
↓
Face restoration
↓
Final enhancement
```

However, reconstructed regions must be visually identifiable internally as AI-generated/restored regions so the pipeline can avoid repeatedly processing them in destructive ways.

For faces, identity preservation takes priority over aesthetic perfection.

---

# 10. Before / After Viewer

The editor must provide an interactive Before/After comparison.

Preferred UX:

```text
┌───────────────────────────────┐
│                               │
│          IMAGE                │
│                               │
│              │                │
│       BEFORE  │  AFTER        │
│              │                │
└───────────────────────────────┘
```

The divider can be dragged horizontally.

Required behaviors:

- Drag comparison divider
- Tap to show original
- Tap to show result
- Zoom
- Pan
- Fit-to-screen
- 100% pixel inspection

The comparison must be synchronized when zooming and panning.

---

# 11. Batch Processing

Users can select many photographs and process them together.

Example:

```text
50 photos
↓
Auto Enhance
↓
Queue
↓
Photo 1  ██████████ 100%
Photo 2  ███████░░░  70%
Photo 3  ██░░░░░░░░  20%
...
```

The queue must support:

- Start
- Cancel current item
- Cancel entire queue
- Continue remaining items after one item fails
- Retry failed item
- Save completed items immediately
- Show overall progress
- Show current image progress

One failed photo must not terminate the entire batch.

---

# 12. Processing Interface

The processing screen should display both overall and stage-specific progress.

Example:

```text
Enhancing photo 7 of 20

Detecting image condition       ✓
Denoising                       ✓
Deblurring                      █████████░
Face restoration                pending
Upscaling                       pending

Overall                         54%
```

The user should see:

- Current photo
- Current stage
- Overall progress
- Current item number
- Queue size
- Estimated remaining time when reliable
- Cancel button

The system must not display fake or meaningless progress values.

If exact progress cannot be measured, use stage-based progress instead.

---

# 13. Processing Cancellation

The processing interface must provide **Cancel**.

Cancellation must:

1. Stop inference safely.
2. Free model memory.
3. Release image buffers.
4. Remove temporary processing files.
5. Keep already completed batch items.
6. Keep the original image.
7. Ask whether to discard or retain an incomplete project state when appropriate.

Partial image output must not be presented as a completed final result.

---

# 14. Background Processing

The application must continue processing when the user minimizes the app or temporarily uses another application.

This should be implemented using Android's OS-supported long-running processing architecture rather than a custom hidden background loop.

The processing operation should start from a user-visible action while the application is in the foreground and then continue using a properly declared foreground processing service. Android 12+ restricts arbitrary foreground-service launches from the background, and Android 14+ requires appropriate foreground-service types. Android provides a `mediaProcessing` foreground-service type for time-consuming media operations. See: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

The implementation must account for modern Android service limits. On Android 15+, `mediaProcessing` foreground services have a cumulative six-hour limit within a 24-hour period while operating under the relevant background conditions. The application therefore needs persistent queue state and resumable processing rather than assuming a single service can run indefinitely. See: https://developer.android.com/develop/background-work/services/fgs/timeout

The queue must survive:

- App minimization
- Screen-off where the OS allows
- Temporary lifecycle changes
- Activity recreation

The app should persist checkpoints so that an interrupted operation can resume from a safe boundary.

Do not promise uninterrupted processing under every Android manufacturer battery-management policy. Instead, implement the most OS-compliant and resilient architecture possible.

---

# 15. Resource Management

The application must be designed for a very wide range of Android hardware.

Processing speed is allowed to vary substantially.

The application must automatically detect available hardware and use an appropriate inference backend.

Preferred acceleration strategy:

```text
Best available supported accelerator
        ↓
GPU / NPU / vendor accelerator where supported
        ↓
NNAPI where beneficial
        ↓
XNNPACK CPU acceleration
        ↓
Standard CPU fallback
```

ONNX Runtime supports Android execution through CPU, XNNPACK, and NNAPI, while Qualcomm devices can use the QNN Execution Provider when supported. Hardware acceleration is device- and model-dependent, so the application must benchmark or probe supported execution paths rather than assuming one backend is always fastest. See: https://onnxruntime.ai/docs/execution-providers/

For CPU execution, XNNPACK should be considered as the primary optimized CPU path where appropriate. See: https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html

## Resource adaptation

When memory is limited, the processing engine must automatically adapt:

- Tile size
- Tile overlap
- Batch size
- Internal precision
- Number of parallel operations
- Temporary buffer size

The system should process very large images in tiles rather than loading excessive intermediate tensors into memory simultaneously.

---

# 16. Thermal Management

The application must monitor device conditions when possible.

When the device becomes thermally constrained:

```text
Normal
↓
Thermal warning
↓
Reduce parallelism
↓
Reduce memory pressure
↓
Continue processing
```

The application should favor **slower processing over crashes**.

If continued processing becomes unsafe or the OS requests termination, the queue must save progress and allow later resume.

---

# 17. Model Architecture

The AI layer must be modular.

Do not create one monolithic model dependency for all features.

Recommended conceptual model modules:

```text
Model Manager
│
├── Image Analyzer
├── Denoising Model
├── Deblurring Model
├── Super Resolution Model
├── Face Detection Model
├── Face Restoration Model
├── Old Photo Restoration Model
├── Scratch/Damage Detection Model
├── Inpainting/Reconstruction Model
├── Colorization Model
└── Optional Final Refinement Model
```

The coding agent may choose exact model architectures based on:

- Current Android support
- Mobile inference compatibility
- Model quality
- Model size
- Memory usage
- Speed
- Licensing
- Hardware acceleration compatibility

Candidate model families may include Real-ESRGAN, SwinIR, Restormer, NAFNet, CodeFormer, GFPGAN, RestoreFormer-family models, and other legally redistributable alternatives, but the agent must **benchmark and verify licensing before selecting any model**.

Do not blindly bundle models simply because they are popular.

Every bundled model must have:

- License information
- Source attribution where required
- Version information
- SHA-256/checksum
- Expected input/output dimensions
- Supported inference backend
- Memory estimate

---

# 18. Model Distribution

The application must follow the requirement that the AI models are included directly with the installed application.

There must be:

**No runtime model download.**

The application must not depend on:

- Cloud model repositories
- Hugging Face downloads
- Remote APIs
- CDN-hosted model files
- Google Drive
- Firebase storage
- Any external model server

The first launch may extract bundled model files from the installed application package into app-private storage for faster access.

The application must show a first-run storage check:

```text
AI models require approximately X GB of storage.
Available space: Y GB.
```

If insufficient storage exists, give the user a clear explanation before initialization.

Because the product prioritizes maximum quality, large model packages are acceptable. Do not aggressively shrink or quantize a model if the resulting quality loss is significant.

Where appropriate, provide multiple locally bundled variants of the same model:

```text
Quality model
Balanced model
Fast fallback model
```

The user does not need to download them. They are already included.

---

# 19. Privacy and Security

The application must be **strictly local**.

Hard requirements:

```text
No INTERNET permission
No network API calls
No cloud processing
No image upload
No remote inference
No login
No account
No telemetry
No advertising SDK
No analytics service
No crash-reporting service that transmits user data
```

Image contents must never leave the device.

Application logs must never contain:

- Image pixel data
- Full image contents
- Sensitive EXIF metadata
- User-generated image data

Where possible, diagnostic logs should contain technical metadata only.

The app must remain functional in:

- Airplane mode
- No SIM card
- No Wi-Fi
- No mobile data
- Network-disabled environments

Offline testing must explicitly verify this requirement.

---

# 20. Original Image Protection

The original file must never be overwritten.

Example:

```text
Original:
IMG_2026_001.jpg

Generated:
IMG_2026_001_enhanced.jpg
```

The user can delete generated results, but the application must not silently replace the source image.

Projects should store a reference to:

- Original image
- Editing parameters
- AI pipeline configuration
- Generated outputs
- Processing metadata

---

# 21. Output

Supported output formats:

- JPG/JPEG
- PNG
- WebP

Output controls:

### JPEG

Quality:

`1–100`

Default:

`95–100`

### PNG

Lossless.

### WebP

Quality slider:

`1–100`

The user must also be able to select output resolution:

- Original / 1×
- 2×
- 4×
- 8×

The application should calculate the resulting dimensions before processing.

Example:

```text
Original:
4032 × 3024

2×:
8064 × 6048

4×:
16128 × 12096

8×:
32256 × 24192
```

If an output size is unsafe for the device, the application must block or adapt it before inference begins.

---

# 22. Automatic Saving

Completed results should automatically be saved to the device gallery.

Preferred folder:

```text
Pictures/LocalPhotoEnhancer/
```

The application must use Android's modern media/storage APIs so saved files remain accessible from the normal gallery/file manager.

Generated files should use deterministic but unique names.

Example:

```text
IMG_001_enhanced.jpg
IMG_001_enhanced_2.jpg
IMG_001_restored.jpg
```

---

# 23. History

The application must provide a History screen.

Each item should show:

- Thumbnail
- Original filename
- Date processed
- Enhancement type
- Output resolution
- Processing status

History states:

```text
Completed
Cancelled
Failed
In Progress
```

Failed projects should show enough information to retry without exposing technical stack traces to normal users.

---

# 24. Projects

The user must be able to save projects.

A project contains:

```text
Project
├── Original reference
├── Crop/rotate state
├── Manual adjustments
├── Auto Enhance configuration
├── Selected models/pipeline
├── Reference photos
├── Generated outputs
└── Processing state
```

The project must allow the user to reopen and continue editing.

---

# 25. Manual Re-Enhancement

After an AI result is generated, the user must be able to continue editing it without returning to the original image.

Example:

```text
Original
  ↓
AI Enhancement
  ↓
Manual color adjustment
  ↓
Additional sharpening
  ↓
Re-enhance
  ↓
Save
```

Every stage must remain as non-destructive as practical.

---

# 26. UI / UX Requirements

The UI style should be:

**Simple, clean, modern, and natural for Android.**

Avoid:

- Crowded dashboards
- Excessive gradients
- Complicated technical terminology
- Unnecessary animations
- Gaming-style AI visuals
- Cluttered toolbars

Use:

- Clear typography
- Large image previews
- Obvious primary actions
- Familiar Android interaction patterns
- Minimal navigation

The design should feel like a premium photo application rather than a developer tool.

---

# 27. Main Screens

## Home

Primary elements:

```text
Local Photo Enhancer

[ Add Photos ]

[ Auto Enhance ]

Recent Results

Projects

History
```

The main action should be **Add Photos**.

---

## Import Screen

Display:

- Device photo picker
- File picker
- Multi-selection
- Selected image count
- Thumbnail grid

After selection:

```text
[ Batch Process ]

[ Use as References ]
```

---

## Editor

Structure:

```text
┌─────────────────────────────┐
│ Back       Save       ⋮     │
├─────────────────────────────┤
│                             │
│       IMAGE PREVIEW         │
│                             │
├─────────────────────────────┤
│ Before / After              │
├─────────────────────────────┤
│ Auto | Manual               │
├─────────────────────────────┤
│ Enhancement Controls        │
└─────────────────────────────┘
```

---

## Auto Enhance Panel

Show detected operations:

```text
Detected

✓ Low resolution
✓ Moderate blur
✓ Noise
✓ Low exposure
✓ Face detected

Planned:
Denoise
Deblur
Lighting correction
Face restoration
4× Upscale
```

The user can start processing immediately.

---

## Manual Panel

Organize controls into collapsible categories:

```text
Resolution
Deblur
Denoise
Sharpen
Lighting
Color
Restoration
Face
Colorization
Reconstruction
```

Controls should expose sensible defaults and reset buttons.

---

## Processing Screen

Show:

- Current image
- Processing stage
- Overall percentage
- Batch position
- Cancel

When minimized, the notification should show processing status.

---

## Result Screen

Actions:

```text
Before / After

[ Save ]

[ Edit Again ]

[ Share ]

[ Create Project ]
```

The Share action should use Android's local sharing mechanisms and must not require a cloud upload.

---

## History Screen

Grid/list of previous results.

Tapping an item opens:

```text
Result
Before / After
Settings
Re-edit
Save As
Delete
```

---

# 28. Performance Modes

The application should support three processing profiles even though the primary philosophy is maximum quality.

### Quality

Maximum quality.

Use the highest-quality suitable local models and processing parameters.

### Balanced

Reduce processing cost while retaining strong quality.

### Fast

Use lighter models and lower computational complexity.

The default should be:

**Quality**

The product explicitly prioritizes maximum quality.

The application should automatically detect when Quality mode cannot safely execute and fall back to a compatible local model or lower-memory strategy.

---

# 29. Device Capability Detection

On first startup, the application should inspect:

- CPU architecture
- CPU cores
- Available memory
- GPU capabilities where detectable
- Neural acceleration capabilities
- Android version
- Available storage
- Current thermal/resource state

The application should select an appropriate inference configuration automatically.

Example:

```text
High-end phone
→ Accelerated execution
→ Larger tiles
→ Highest-quality model

Mid-range phone
→ Accelerated execution where supported
→ Medium tiles
→ Highest model that fits safely

Low-end phone
→ CPU fallback / optimized model
→ Small tiles
→ Lower concurrency
```

The app must not refuse to operate simply because the phone is slow.

---

# 30. Large Image Handling

The application must support photographs much larger than typical screen resolution.

Required strategy:

- Streaming decode where possible
- Tile-based inference
- Tile overlap to eliminate seams
- Intermediate buffer reuse
- Memory-aware allocation
- Progressive processing
- Safe output allocation

A 48 MP or 108 MP image should not automatically cause an out-of-memory crash.

When the full-resolution result cannot safely fit into memory:

```text
Decode tile
→ Process tile
→ Write tile
→ Release tile
→ Continue
```

The final result should be assembled without requiring the entire processing pipeline to hold all intermediate tensors simultaneously.

---

# 31. Thermal and Battery UX

When long processing is happening, the notification and processing screen should communicate that:

> “Processing may take longer on this device.”

Do not make false time guarantees.

The app may show an estimated completion time only when enough processing history exists for a reliable estimate.

---

# 32. Error Handling

The application must gracefully handle:

### Unsupported/corrupt image

Show:

> “This photo could not be decoded.”

Do not crash.

### Insufficient storage

Show:

> “Not enough storage to create this output.”

### Insufficient memory

Attempt automatic tile-size reduction first.

If still impossible:

> “This photo is too large for the current device at 8×. Try 4× or a lower processing profile.”

### Model initialization failure

Retry using the next compatible local backend/model.

### AI inference failure

Recover the queue and allow retry.

### Application interruption

Persist processing state and resume where possible.

### User cancellation

Clean up temporary resources without affecting original files.

---

# 33. Architecture

The preferred implementation should use a modular Android architecture.

Suggested stack:

```text
Kotlin
Jetpack Compose
ViewModel
StateFlow
Room
DataStore
Android MediaStore / Storage Access Framework
Android Foreground Service
ONNX Runtime Mobile / equivalent local inference runtime
NDK/C++ only where required for performance
```

The coding agent is free to replace individual technologies when a demonstrably better current Android-native solution exists.

### Suggested layers

```text
Presentation
│
├── Compose UI
├── ViewModels
└── UI State

Domain
│
├── EnhancePhotoUseCase
├── AutoEnhanceUseCase
├── BatchProcessingUseCase
├── RestorePhotoUseCase
└── ProjectManagementUseCase

AI
│
├── ModelManager
├── InferenceEngine
├── DeviceCapabilityDetector
├── PipelineOrchestrator
├── ImageAnalyzer
└── ModelBackends

Data
│
├── Room
├── ProjectRepository
├── HistoryRepository
└── MediaRepository

Processing
│
├── ProcessingQueue
├── CheckpointManager
├── ForegroundProcessingService
└── ResourceManager
```

---

# 34. Model Manager

The Model Manager should:

- Load bundled models
- Verify model checksum
- Check model compatibility
- Select an inference backend
- Cache loaded models where memory allows
- Unload unused models
- Report memory usage
- Provide fallback models

It must prevent loading every large model into memory simultaneously.

Example:

```text
Image Analyzer loaded
↓
Analyzer finished
↓
Analyzer unloaded
↓
Deblur model loaded
↓
Deblur finished
↓
Deblur unloaded
↓
Face model loaded
```

The exact strategy may use model reuse when performance benefits outweigh memory costs.

---

# 35. AI Pipeline Orchestrator

The orchestrator is the central intelligence of the application.

Inputs:

```text
Image
User settings
Device capabilities
Detected defects
Reference images
Selected processing profile
```

Outputs:

```text
Processing graph
Model selection
Parameters
Execution order
Fallback strategy
```

Example internal decision:

```json
{
  "resolution": 4,
  "denoise": 0.62,
  "deblur": 0.78,
  "face_restoration": true,
  "color_correction": 0.41,
  "colorization": false,
  "scratch_removal": true
}
```

The final implementation can use another representation, but the pipeline must be data-driven instead of hardcoded inside UI code.

---

# 36. Identity Preservation

This is a high-priority quality rule.

For human faces:

```text
Original identity
        ↓
Restoration
        ↓
Enhanced identity
```

not:

```text
Original identity
        ↓
AI hallucination
        ↓
Different person
```

Face restoration should optimize for:

- Facial geometry preservation
- Eye position preservation
- Face-shape preservation
- Skin/detail realism
- Hair preservation
- Original expression preservation

Reference photos should be used where they improve identity consistency.

---

# 37. Quality Guardrails

The final output should be evaluated for common enhancement failures:

- Oversharpening
- Halos
- Plastic skin
- Excessive noise removal
- False facial details
- Incorrect colors
- Excessive saturation
- Visible tile seams
- Repeating AI textures
- Unnatural reconstruction
- Lost fine detail

If a pipeline stage would reduce quality, the orchestrator should reduce that stage's strength or skip it.

---

# 38. EXIF and Metadata

Preserve practical metadata where legally and technically feasible.

At minimum:

- Orientation
- Date metadata when appropriate
- Camera information when appropriate

However, do not accidentally expose sensitive metadata when generating a shareable image.

Provide an option to strip metadata from exported results.

---

# 39. No Internet Dependency

The manifest should not request the `INTERNET` permission.

The build should be audited for accidental network dependencies.

Testing must verify that the application still works with:

```text
Wi-Fi OFF
Mobile data OFF
Airplane mode ON
Network unavailable
```

The app must not display errors caused solely by network unavailability because network access is not part of the product architecture.

---

# 40. Accessibility

The UI should support:

- Android system font scaling
- Screen readers where practical
- Adequate touch target sizes
- Sufficient contrast
- Descriptive controls
- Meaningful content descriptions

The photo comparison interaction must remain usable without relying exclusively on tiny gestures.

---

# 41. Localization

The first version should be architected for localization.

Initial language:

**English**

The implementation should make Indonesian support easy to add later.

Do not hardcode user-visible strings throughout the application.

---

# 42. Data Storage

Store application-owned project data in app-private storage.

Maintain clear separation between:

```text
Original photos
Generated photos
Temporary processing files
Project database
AI model files
```

Temporary files must be cleaned up automatically after successful completion.

Do not delete user files outside the application's generated-output scope.

---

# 43. Battery Optimization

Because AI image restoration can require substantial computation, the application should expose processing state and respect the Android OS's resource-management rules.

The app should avoid unnecessary background execution.

Processing should be triggered by an explicit user action.

Long-running processing should use the appropriate Android-supported mechanism rather than attempting to evade Android lifecycle or battery protections.

For long-running local ML workloads, Android's WorkManager can support long-running workers, but current Android versions impose additional quota and foreground-service considerations; the implementation should therefore select the mechanism based on the processing behavior and target Android version rather than blindly using a generic background worker. See: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running

---

# 44. Project Lifecycle

Example:

```text
New Project
    ↓
Import Photos
    ↓
Crop / Rotate
    ↓
Auto Enhance or Manual
    ↓
Processing
    ↓
Review
    ↓
Manual Refinement
    ↓
Export
```

A project should remain editable after export.

---

# 45. Acceptance Criteria

The application is considered successful when all of the following are true.

## Offline

- The application works without internet access.
- No photo is uploaded.
- No remote AI inference exists.
- No login is required.
- No telemetry is required.
- `INTERNET` permission is not required.

## Enhancement

A user can:

- Improve low-resolution images.
- Upscale 2×, 4×, or 8×.
- Reduce blur.
- Reduce noise.
- Improve lighting.
- Improve colors.
- Restore old photographs.
- Remove scratches.
- Remove dust.
- Restore faded colors.
- Colorize black-and-white photographs.
- Reconstruct damaged/missing regions.
- Enhance faces.

## Auto Enhance

A user can select an image and obtain a meaningful automatic enhancement without manually selecting every processing step.

The automatic pipeline must adapt to the image rather than blindly applying every effect.

## Manual

Every requested category is available through user controls.

## Comparison

The user can compare the original and result using an interactive slider and zoom.

## Batch

The user can process multiple images in one queue.

A single failed image must not terminate the entire queue.

## Multi-reference

The user can provide multiple images as references for a single processing operation.

## Background

Processing can continue after the app is minimized, within Android's applicable operating-system restrictions.

## Large images

The app uses memory-aware/tiled processing and should not crash simply because an input image is high resolution.

## Original preservation

The original file is never overwritten.

## Output

Results can be exported as:

- JPG
- PNG
- WebP

with configurable quality where applicable.

## History/projects

Completed work can be revisited and edited later.

---

# 46. Quality Validation

The coding agent must not judge image quality solely by whether the model runs successfully.

Build a local test suite containing:

### Low-resolution images

Expected:

- More visible detail
- No excessive ringing
- No obvious artificial textures

### Blurry images

Expected:

- Increased edge/detail clarity
- Reduced blur
- Limited hallucination

### Noisy images

Expected:

- Reduced noise
- Preserved legitimate texture

### Dark images

Expected:

- Better visibility
- Natural contrast
- No extreme clipping

### Old photographs

Expected:

- Reduced scratches
- Reduced dust
- Improved contrast
- Improved faded color
- Face preservation
- Optional colorization

### Damaged photos

Expected:

- Reconstructed regions blend naturally
- No obvious seams

### Portraits

Expected:

- Identity preserved
- Facial structure preserved
- No artificial face replacement

---

# 47. Testing Matrix

Test the application across at least three classes of Android hardware:

```text
Low-end
Mid-range
High-end
```

Test:

- CPU-only
- Accelerated backend
- Large image
- Small image
- Portrait
- Landscape
- Old photograph
- Black-and-white photograph
- Batch processing
- Multi-reference processing
- Background processing
- App minimization
- Screen-off behavior
- Cancellation
- Resume
- Low storage
- Low memory
- Thermal throttling
- Offline mode

The application should degrade gracefully rather than failing outright on slower devices.

---

# 48. Development Phases

## Phase 1 — Android application foundation

Build:

- Project structure
- Compose UI
- Navigation
- Gallery import
- File import
- Project storage
- History
- Output storage

## Phase 2 — Image editor

Build:

- Preview
- Zoom/pan
- Crop
- Rotate
- Before/After comparison
- Manual controls

## Phase 3 — Local AI engine

Implement:

- Model manager
- Image analyzer
- Local inference runtime
- Hardware/backend selection
- Tiled processing

## Phase 4 — Enhancement pipelines

Implement:

- Super resolution
- Deblur
- Denoise
- Lighting correction
- Color correction
- Face restoration
- Old photo restoration
- Scratch removal
- Reconstruction
- Colorization

## Phase 5 — Auto Enhance

Build the AI pipeline orchestrator and automatic defect detection.

## Phase 6 — Batch and references

Implement:

- Processing queue
- Batch mode
- Multi-reference mode
- Retry
- Cancel
- Resume

## Phase 7 — Background processing

Implement:

- Foreground processing service
- Persistent queue
- Notification
- Checkpoint/resume
- Android lifecycle handling

## Phase 8 — Optimization

Optimize:

- Model loading
- Memory
- Tile processing
- CPU/GPU/NPU execution
- Thermal behavior
- Large-image processing

## Phase 9 — QA

Perform:

- Offline tests
- Device tests
- Stress tests
- Crash testing
- Quality testing
- Storage testing
- Background processing testing

---

# 49. Definition of Done

The application is finished when a fresh Android 10+ device can:

```text
Install app
↓
Open app
↓
Import photo
↓
Select Auto Enhance
↓
Process entirely offline
↓
Receive high-quality enhanced result
↓
Compare Before/After
↓
Save result to gallery
↓
Close/minimize app during a long job
↓
Return later and see processing state
↓
Open History
↓
Re-edit the project
```

No server is required at any stage.

---

# 50. Instructions to the AI Coding Agent

Build the application as a **real, production-oriented Android application**, not a prototype UI.

Do not create placeholder AI buttons that do nothing.

Every major feature in this PRD must either be implemented or explicitly marked as a technical dependency with a concrete local implementation plan.

Do not replace local AI with:

- API calls
- Cloud inference
- Browser-based inference
- Remote image-generation services
- Mock processing

All inference must occur on the Android device.

Do not add unnecessary internet-dependent libraries or SDKs.

Do not sacrifice original-image preservation.

Do not hardcode assumptions about a particular Android phone.

Use automatic device capability detection and memory-aware processing.

Prioritize image quality, but make the pipeline resilient enough to run on weaker devices through tiling, fallback models, reduced concurrency, and other safe resource adaptations.

Do not let the application crash because a photograph is unusually large.

Do not allow a failed batch item to destroy the entire queue.

Do not overwrite originals.

Do not expose internal model names or technical errors to normal users unless useful.

Provide useful technical logging in debug builds, while keeping user image data out of logs.

Every bundled AI model must have verified redistribution rights and appropriate license/attribution information.

The implementation should be modular so individual AI models can be upgraded later without rewriting the entire application.

The final product should feel like a **simple Android photo enhancer on the surface, with a sophisticated local AI processing engine underneath.**

---

# Reference Documentation

- Android foreground-service background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android foreground-service timeout behavior: https://developer.android.com/develop/background-work/services/fgs/timeout
- Android long-running background work: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- ONNX Runtime Execution Providers: https://onnxruntime.ai/docs/execution-providers/
- ONNX Runtime XNNPACK Execution Provider: https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html
