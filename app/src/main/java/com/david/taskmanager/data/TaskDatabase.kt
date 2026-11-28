package com.david.taskmanager.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, DependencyEntity::class, AppStateEntity::class, GoogleTaskLinkEntity::class],
    version = 2,
    exportSchema = true,
    // v2 only adds the google_task_links table.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        fun create(context: Context): TaskDatabase =
            Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "tasks.db").build()
    }
}
