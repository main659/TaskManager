package com.david.taskmanager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Transaction
    @Query("SELECT * FROM tasks ORDER BY position")
    fun observeAll(): Flow<List<TaskWithDependencies>>

    @Transaction
    @Query("SELECT * FROM tasks ORDER BY position")
    suspend fun getAll(): List<TaskWithDependencies>

    /** Insert-or-update in place. Unlike REPLACE, this never deletes the row, so no cascade fires. */
    @Upsert
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteTasks(ids: List<String>)

    @Insert
    suspend fun insertDependencies(dependencies: List<DependencyEntity>)

    @Query("DELETE FROM task_dependencies WHERE task_id IN (:taskIds)")
    suspend fun deleteDependenciesOf(taskIds: List<String>)

    @Query("SELECT value FROM app_state WHERE `key` = :key")
    suspend fun getState(key: String): String?

    @Upsert
    suspend fun putState(state: AppStateEntity)

    @Query("SELECT * FROM app_state WHERE `key` IN (:keys)")
    fun observeStates(keys: List<String>): Flow<List<AppStateEntity>>

    @Query("DELETE FROM app_state WHERE `key` IN (:keys)")
    suspend fun deleteStates(keys: List<String>)

    @Query("SELECT * FROM google_task_links")
    suspend fun getGoogleLinks(): List<GoogleTaskLinkEntity>

    @Insert
    suspend fun insertGoogleLinks(links: List<GoogleTaskLinkEntity>)

    @Query("DELETE FROM google_task_links")
    suspend fun deleteAllGoogleLinks()
}
