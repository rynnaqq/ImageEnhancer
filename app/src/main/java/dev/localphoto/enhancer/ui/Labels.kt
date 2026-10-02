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
    Stage.REPAIR -> R.string.stage_repair; Stage.COLORIZE -> R.string.stage_colorize
    Stage.FACE_RESTORE -> R.string.stage_face_restore
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
fun messageLabel(code: String): Int = when {
    code == "repair_mask_large" -> R.string.error_repair_mask_large
    code == "module_memory" -> R.string.error_module_memory
    code == "module_model" || code.startsWith("module_model_") -> R.string.error_module_model
    code == "decode" -> R.string.error_decode
    code == "storage" -> R.string.error_storage
    code == "memory" -> R.string.error_memory
    code == "resume_memory" -> R.string.error_resume_memory
    code == "resume_regions" -> R.string.error_resume_regions
    code == "model_init" || code == "model_shape" -> R.string.error_model
    code == "model_integrity" -> R.string.error_integrity
    code == "export" -> R.string.error_export
    code == "interrupted" -> R.string.error_interrupted
    code == "service" -> R.string.error_service
    code == "tiff_dependency" -> R.string.tiff_dependency
    code == "import_failed" -> R.string.import_failed
    code == "project_saved" -> R.string.project_saved
    code == "saved" -> R.string.saved_automatically
    code == "source_sampled" -> R.string.notice_sampled
    code == "scale_reduced" -> R.string.notice_scale
    code == "sampled_and_scale" -> R.string.notice_sampled_scale
    code == "gallery_save_failed" -> R.string.notice_gallery_failed
    code == "face_no_faces" -> R.string.notice_face_no_faces
    code == "face_protected_regions" -> R.string.notice_face_protected_regions
    code == "face_detail_preserved" -> R.string.notice_face_detail_preserved
    code == "face_limit" -> R.string.notice_face_limit
    code == "repair_no_mask" -> R.string.notice_repair_no_mask
    code == "repair_no_scratches" -> R.string.notice_repair_no_scratches
    code == "reconstructed_regions" -> R.string.notice_reconstructed_regions
    else -> R.string.error_inference
}
