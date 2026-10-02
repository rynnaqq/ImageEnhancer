package dev.localphoto.enhancer.ui

import dev.localphoto.core.*
import dev.localphoto.enhancer.R

fun statusLabel(status: ProjectStatus): Int = when (status) {
    ProjectStatus.DRAFT -> R.string.status_draft
    ProjectStatus.QUEUED -> R.string.status_queued
    ProjectStatus.PROCESSING -> R.string.status_processing
    ProjectStatus.COMPLETED -> R.string.status_completed
    ProjectStatus.CANCELLED -> R.string.status_cancelled
    ProjectStatus.FAILED -> R.string.status_failed
    ProjectStatus.INTERRUPTED -> R.string.status_interrupted
}
fun stageLabel(stage: Stage): Int = when (stage) {
    Stage.DECODE -> R.string.stage_decode; Stage.ANALYZE -> R.string.stage_analyze
    Stage.DENOISE -> R.string.stage_denoise; Stage.DEBLUR -> R.string.stage_deblur
    Stage.LIGHTING -> R.string.stage_lighting; Stage.COLOR -> R.string.stage_color
    Stage.SUPER_RESOLUTION -> R.string.stage_super_resolution
    Stage.REFINE -> R.string.stage_refine; Stage.EXPORT -> R.string.stage_export
}
fun defectLabel(defect: Defect): Int = when (defect) {
    Defect.LOW_RESOLUTION -> R.string.detected_low_resolution; Defect.NOISE -> R.string.detected_noise
    Defect.BLUR -> R.string.detected_blur; Defect.UNDEREXPOSURE -> R.string.detected_underexposure
    Defect.OVEREXPOSURE -> R.string.detected_overexposure; Defect.LOW_CONTRAST -> R.string.detected_low_contrast
    Defect.COLOR_CAST -> R.string.detected_color_cast; Defect.FADED_COLOR -> R.string.detected_faded_color
    Defect.MONOCHROME -> R.string.detected_monochrome
}
fun profileLabel(profile: Profile): Int = when (profile) {
    Profile.QUALITY -> R.string.quality; Profile.BALANCED -> R.string.balanced; Profile.FAST -> R.string.fast
}
fun messageLabel(code: String): Int = when (code) {
    "decode" -> R.string.error_decode; "storage" -> R.string.error_storage; "memory" -> R.string.error_memory
    "resume_memory" -> R.string.error_resume_memory
    "model_init", "model_shape" -> R.string.error_model; "model_integrity" -> R.string.error_integrity
    "export" -> R.string.error_export; "interrupted" -> R.string.error_interrupted; "service" -> R.string.error_service
    "tiff_dependency" -> R.string.tiff_dependency; "import_failed" -> R.string.import_failed
    "project_saved" -> R.string.project_saved; "saved" -> R.string.saved_automatically
    "source_sampled" -> R.string.notice_sampled; "scale_reduced" -> R.string.notice_scale
    "sampled_and_scale" -> R.string.notice_sampled_scale; "gallery_save_failed" -> R.string.notice_gallery_failed
    else -> R.string.error_inference
}
