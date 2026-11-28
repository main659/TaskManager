package com.david.taskmanager.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Dates are stored as epoch days, instants as epoch milliseconds (see Mappers.kt). */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    val status: String,
    @ColumnInfo(name = "due_date") val dueDate: Long?,
    @ColumnInfo(name = "available_from") val availableFrom: Long?,
    val position: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
)

/** "[taskId] waits on [dependsOnId]". [sortOrder] keeps the order of `Task.dependencies`. */
@Entity(
    tableName = "task_dependencies",
    primaryKeys = ["task_id", "depends_on_id"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["depends_on_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("depends_on_id")],
)
data class DependencyEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "depends_on_id") val dependsOnId: String,
    @ColumnInfo(name = "delay_days") val delayDays: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/** Small key/value store for app-level state, e.g. the last date availability was evaluated. */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

data class TaskWithDependencies(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "task_id")
    val dependencies: List<DependencyEntity>,
)

/**
 * Links a local task to its copy in Google Tasks. There is deliberately no foreign key to `tasks`: when a
 * linked task is deleted locally, the link stays so that sync knows to delete the Google copy too.
 * [googleUpdatedAt] is Google's `updated` time when the link was last refreshed (epoch milliseconds).
 */
@Entity(
    tableName = "google_task_links",
    indices = [Index(value = ["google_task_id"], unique = true)],
)
data class GoogleTaskLinkEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "google_task_id") val googleTaskId: String,
    @ColumnInfo(name = "google_updated_at") val googleUpdatedAt: Long,
)
