package com.david.taskmanager.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Builds a database exactly as version 1 created it (from the exported schema `app/schemas/.../1.json`), then
 * lets Room open it at the current version. Room validates every table after migrating and throws if the result
 * does not match the current entities, so this proves the migration and that existing data survives.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun createVersion1() {
        val schema = JSONObject(File("schemas/com.david.taskmanager.data.TaskDatabase/1.json").readText())
            .getJSONObject("database")
        assertEquals(1, schema.getInt("version"))
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO tasks (id, title, notes, status, due_date, available_from, position, created_at, " +
                    "updated_at, completed_at) VALUES ('00000000-0000-0000-0000-000000000001', 'Kept', 'n', 'OPEN', " +
                    "20000, NULL, 3, 1000, 2000, NULL)",
            )
            db.execSQL("INSERT INTO app_state (`key`, value) VALUES ('last_evaluated_date', '2026-11-27')")
            db.version = 1
        } finally {
            db.close()
        }
    }

    @Test
    fun `version 1 databases open at the current version with their data`() = runTest {
        createVersion1()

        val db = Room.databaseBuilder(context, TaskDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val dao = db.taskDao()
            val tasks = dao.getAll()
            assertEquals(listOf("Kept"), tasks.map { it.task.title })
            assertEquals("2026-11-27", dao.getState(TaskRepository.KEY_LAST_EVALUATED_DATE))
            // The new table exists and works.
            dao.insertGoogleLinks(listOf(GoogleTaskLinkEntity(tasks.single().task.id, "g1", 5L)))
            assertEquals("g1", dao.getGoogleLinks().single().googleTaskId)
            assertEquals(2, db.openHelper.readableDatabase.version)
            assertEquals(null, TaskRepository(db, clockOn(TODAY)).googleConnection.first())
        } finally {
            db.close()
        }
    }
}
