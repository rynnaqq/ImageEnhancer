package dev.localphoto.enhancer.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE isReference = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PhotoProject>>
    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun find(id: String): PhotoProject?
    @Query("SELECT * FROM projects WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): PhotoProject?
    @Query("SELECT * FROM projects WHERE status IN ('QUEUED', 'PROCESSING', 'INTERRUPTED') ORDER BY createdAt ASC")
    suspend fun active(): List<PhotoProject>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: PhotoProject)
    @Update
    suspend fun update(project: PhotoProject)
    // Recheck editability in the same write; a delayed autosave must not undo a queue claim.
    @Query("""UPDATE projects SET settingsJson = :settingsJson, updatedAt = :updatedAt,
        checkpointPath = CASE WHEN status = 'INTERRUPTED' THEN NULL ELSE checkpointPath END,
        checkpointStage = CASE WHEN status = 'INTERRUPTED' THEN -1 ELSE checkpointStage END,
        planJson = CASE WHEN status = 'INTERRUPTED' THEN '' ELSE planJson END,
        stage = CASE WHEN status = 'INTERRUPTED' THEN '' ELSE stage END,
        progress = CASE WHEN status = 'INTERRUPTED' THEN 0 ELSE progress END,
        errorCode = CASE WHEN status = 'INTERRUPTED' THEN NULL ELSE errorCode END,
        status = CASE WHEN status = 'INTERRUPTED' THEN 'DRAFT' ELSE status END
        WHERE id = :id AND status NOT IN ('QUEUED', 'PROCESSING') AND settingsJson != :settingsJson""")
    suspend fun saveEditableSettings(id: String, settingsJson: String, updatedAt: Long): Int
    @Query("""UPDATE projects SET batchId = :batchId, updatedAt = :updatedAt, errorCode = NULL,
        outputPath = CASE WHEN status = 'INTERRUPTED' THEN outputPath ELSE NULL END,
        galleryUri = NULL,
        progress = CASE WHEN status = 'INTERRUPTED' THEN progress ELSE 0 END,
        checkpointPath = CASE WHEN status = 'INTERRUPTED' THEN checkpointPath ELSE NULL END,
        checkpointStage = CASE WHEN status = 'INTERRUPTED' THEN checkpointStage ELSE -1 END,
        planJson = CASE WHEN status = 'INTERRUPTED' THEN planJson ELSE '' END,
        stage = CASE WHEN status = 'INTERRUPTED' THEN stage ELSE '' END,
        status = 'QUEUED'
        WHERE id = :id AND isReference = 0 AND status NOT IN ('QUEUED', 'PROCESSING')""")
    suspend fun enqueueEditable(id: String, batchId: String, updatedAt: Long): Int
    @Query("UPDATE projects SET status = 'PROCESSING' WHERE id = :id AND status = 'QUEUED'")
    suspend fun claimQueued(id: String): Int
    @Query("""UPDATE projects SET referencePathsJson = :pathsJson, updatedAt = :updatedAt
        WHERE id = :id AND status NOT IN ('QUEUED', 'PROCESSING')""")
    suspend fun setEditableReferences(id: String, pathsJson: String, updatedAt: Long): Int
    // Export may finish after the user has queued or re-edited this revision.
    @Query("""UPDATE projects SET galleryUri = :uri,
        noticeCode = CASE WHEN noticeCode = 'gallery_save_failed' THEN NULL ELSE noticeCode END
        WHERE id = :id AND revision = :revision AND outputPath = :outputPath AND status = 'COMPLETED'""")
    suspend fun recordGalleryExport(id: String, revision: Int, outputPath: String, uri: String): Int
    @Query("""UPDATE projects SET noticeCode = 'gallery_save_failed'
        WHERE id = :id AND revision = :revision AND outputPath = :outputPath AND status = 'COMPLETED'""")
    suspend fun recordGalleryFailure(id: String, revision: Int, outputPath: String): Int
    @Query("""UPDATE projects SET galleryUri = NULL
        WHERE id = :id AND revision = :revision AND outputPath = :outputPath
        AND galleryUri = :uri AND status = 'COMPLETED'""")
    suspend fun clearGalleryExport(id: String, revision: Int, outputPath: String, uri: String): Int
    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
    @Query("UPDATE projects SET status = 'INTERRUPTED', errorCode = 'interrupted' WHERE status = 'PROCESSING'")
    suspend fun recoverInterrupted()
    @Query("UPDATE projects SET status = 'CANCELLED', errorCode = NULL WHERE status = 'QUEUED'")
    suspend fun cancelQueued()
}

@Database(entities = [PhotoProject::class], version = 2, exportSchema = true)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN baseWidth INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN baseHeight INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN batchId TEXT")
                db.execSQL("UPDATE projects SET baseWidth = outputWidth, baseHeight = outputHeight WHERE basePath IS NOT NULL")
            }
        }
        fun open(context: Context): ProjectDatabase = Room.databaseBuilder(
            context.applicationContext, ProjectDatabase::class.java, "projects.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
