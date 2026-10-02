package dev.localphoto.enhancer

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.core.EnhanceSettings
import dev.localphoto.core.ProjectStatus
import dev.localphoto.enhancer.data.ProjectDatabase
import dev.localphoto.enhancer.data.SettingsCodec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    @Test fun versionOneProjectAndReeditDimensionsSurviveUpgrade() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("dev.localphoto.enhancer.data.ProjectDatabase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val entity = schema.getJSONArray("entities").getJSONObject(0)
        val settings = EnhanceSettings()
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { legacy ->
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "projects"))
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) legacy.execSQL(setup.getString(index))
            val values = ContentValues()
            val fields = entity.getJSONArray("fields")
            for (index in 0 until fields.length()) {
                val field = fields.getJSONObject(index)
                if (field.getBoolean("notNull")) {
                    when (field.getString("affinity")) {
                        "TEXT" -> values.put(field.getString("columnName"), "")
                        "INTEGER" -> values.put(field.getString("columnName"), 0L)
                        "REAL" -> values.put(field.getString("columnName"), 0.0)
                    }
                }
            }
            values.apply {
                put("id", "legacy"); put("name", "Existing project")
                put("sourcePath", "/private/original.png"); put("basePath", "/private/results/result-1.png")
                put("width", 800); put("height", 600); put("outputWidth", 1600); put("outputHeight", 1200)
                put("settingsJson", SettingsCodec.encode(settings)); put("referencePathsJson", "[]")
                put("status", ProjectStatus.DRAFT.name); put("revision", 1)
            }
            assertTrue(legacy.insertOrThrow("projects", null, values) > 0)
            legacy.version = 1
        }
        val upgraded = Room.databaseBuilder(context, ProjectDatabase::class.java, name)
            .addMigrations(ProjectDatabase.MIGRATION_1_2).build()
        try {
            val project = upgraded.projects().find("legacy")!!
            assertEquals("Existing project", project.name); assertEquals(settings, project.settings)
            assertEquals("/private/original.png", project.sourcePath)
            assertEquals(1600, project.inputWidth); assertEquals(1200, project.inputHeight)
            assertEquals(1, project.revision); assertNull(project.batchId)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
