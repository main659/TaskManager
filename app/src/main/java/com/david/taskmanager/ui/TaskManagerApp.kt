package com.david.taskmanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.david.taskmanager.AppContainer
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.launch.LaunchState
import com.david.taskmanager.ui.detail.TaskDetailRoute
import com.david.taskmanager.ui.detail.TaskDetailViewModel
import com.david.taskmanager.ui.google.GoogleTasksRoute
import com.david.taskmanager.ui.google.GoogleTasksViewModel
import com.david.taskmanager.ui.lists.TaskListsRoute
import com.david.taskmanager.ui.lists.TaskListsViewModel
import java.util.UUID

/** Back stack entries. The stack is a plain list we own; Navigation 3 renders its last entry. */
sealed interface Screen {
    data object Main : Screen
    data class Detail(val taskId: TaskId) : Screen
    data object Google : Screen
}

private const val MAIN = "main"
private const val DETAIL_PREFIX = "detail:"
private const val GOOGLE = "google"

/** Saves the back stack as strings, so it survives configuration changes and process death. */
private val BackStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Screen.Main -> MAIN
                is Screen.Detail -> DETAIL_PREFIX + it.taskId
                Screen.Google -> GOOGLE
            }
        }
    },
    restore = { saved ->
        saved.map {
            when (it) {
                MAIN -> Screen.Main
                GOOGLE -> Screen.Google
                else -> Screen.Detail(TaskId(UUID.fromString(it.removePrefix(DETAIL_PREFIX))))
            }
        }.toMutableStateList()
    },
)

@Composable
fun TaskManagerApp(container: AppContainer, launch: LaunchState = LaunchState(listOf(Screen.Main))) {
    // The launch state only seeds the back stack; after a configuration change the saved stack wins.
    val backStack = rememberSaveable(saver = BackStackSaver) { launch.backStack.toMutableStateList() }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            // Gives each entry its own ViewModelStore, cleared when the entry leaves the back stack.
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Screen.Main> {
                val viewModel = viewModel {
                    TaskListsViewModel(container.repository, container.clock, container.today, container.appScope)
                }
                TaskListsRoute(
                    viewModel,
                    onOpenTask = { backStack.add(Screen.Detail(it)) },
                    onOpenGoogle = { if (backStack.last() != Screen.Google) backStack.add(Screen.Google) },
                    startWithQuickAdd = launch.quickAdd,
                )
            }
            entry<Screen.Detail> { key ->
                val viewModel = viewModel {
                    TaskDetailViewModel(key.taskId, container.repository, container.clock, container.today, container.appScope)
                }
                // Removing this exact entry (not "the last one") makes closing idempotent.
                TaskDetailRoute(viewModel, onClose = { backStack.remove(key) })
            }
            entry<Screen.Google> { key ->
                val viewModel = viewModel {
                    GoogleTasksViewModel(container.repository, container.googleTasks, container.appScope)
                }
                GoogleTasksRoute(viewModel, onBack = { backStack.remove(key) })
            }
        },
    )
}
