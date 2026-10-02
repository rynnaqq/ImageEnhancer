package dev.localphoto.enhancer.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import dev.localphoto.core.ProjectStatus

@Entity(tableName = "projects")
data class PhotoProject(
    @PrimaryKey val id: String,
    val name: String,
    val sourcePath: String,
    val width: Int,
    val height: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: String = ProjectStatus.DRAFT.name,
    val settingsJson: String = SettingsCodec.encode(dev.localphoto.core.EnhanceSettings()),
    val referencePathsJson: String = "[]",
    val basePath: String? = null,
    @ColumnInfo(defaultValue = "0") val baseWidth: Int = 0,
    @ColumnInfo(defaultValue = "0") val baseHeight: Int = 0,
    val batchId: String? = null,
    val outputPath: String? = null,
    val galleryUri: String? = null,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val revision: Int = 0,
    val stage: String = "",
    val progress: Float = 0f,
    val checkpointPath: String? = null,
    val checkpointStage: Int = -1,
    val planJson: String = "",
    val errorCode: String? = null,
    val noticeCode: String? = null,
    val isReference: Boolean = false,
) {
    val settings get() = SettingsCodec.decode(settingsJson)
    val state get() = runCatching { ProjectStatus.valueOf(status) }.getOrDefault(ProjectStatus.DRAFT)
    val editable get() = state !in setOf(ProjectStatus.PROCESSING, ProjectStatus.QUEUED)
    val inputWidth get() = if (basePath != null && baseWidth > 0) baseWidth else width
    val inputHeight get() = if (basePath != null && baseHeight > 0) baseHeight else height
}
