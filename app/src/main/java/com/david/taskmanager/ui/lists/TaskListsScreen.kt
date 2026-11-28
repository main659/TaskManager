package com.david.taskmanager.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.david.taskmanager.R
import com.david.taskmanager.core.LockedTask
import com.david.taskmanager.core.MoveDirection
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.ui.common.formatDate
import com.david.taskmanager.ui.common.lockReasonText
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun TaskListsRoute(
    viewModel: TaskListsViewModel,
    onOpenTask: (TaskId) -> Unit,
    onOpenGoogle: () -> Unit = {},
    startWithQuickAdd: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaskListsScreen(
        state = state,
        onAdd = { viewModel.add(it) },
        onComplete = { viewModel.complete(it) },
        onUncomplete = { viewModel.uncomplete(it) },
        onMove = { id, direction -> viewModel.move(id, direction) },
        onOpenTask = onOpenTask,
        onOpenGoogle = onOpenGoogle,
        startWithQuickAdd = startWithQuickAdd,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListsScreen(
    state: ListsUiState?,
    onAdd: (String) -> Unit,
    onComplete: (TaskId) -> Unit,
    onUncomplete: (TaskId) -> Unit,
    onMove: (TaskId, MoveDirection) -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    onOpenGoogle: () -> Unit = {},
    startWithQuickAdd: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_AVAILABLE) }
    var showQuickAdd by rememberSaveable { mutableStateOf(startWithQuickAdd) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val completedMessage = stringResource(R.string.task_completed)
    val undoLabel = stringResource(R.string.undo)

    val completeWithUndo: (TaskId) -> Unit = { id ->
        onComplete(id)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(completedMessage, undoLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onUncomplete(id)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { AppMenu(onOpenGoogle) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (tab == TAB_AVAILABLE) {
                FloatingActionButton(onClick = { showQuickAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_task))
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val lists = state?.lists
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(
                    R.string.tab_available to lists?.available?.size,
                    R.string.tab_locked to lists?.locked?.size,
                    R.string.tab_done to lists?.done?.size,
                ).forEachIndexed { index, (label, count) ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = {
                            Text(
                                if (count == null) stringResource(label)
                                else stringResource(R.string.tab_with_count, stringResource(label), count),
                            )
                        },
                    )
                }
            }
            if (state != null) {
                when (tab) {
                    TAB_AVAILABLE -> AvailableList(state.lists.available, state.today, completeWithUndo, onMove, onOpenTask)
                    TAB_LOCKED -> LockedList(state.lists.locked, state.today, onOpenTask)
                    else -> DoneList(state.lists.done, onUncomplete, onOpenTask)
                }
            }
        }
    }

    if (showQuickAdd) {
        ModalBottomSheet(onDismissRequest = { showQuickAdd = false }) {
            QuickAddField(onAdd = onAdd, modifier = Modifier.padding(16.dp))
        }
    }
}

/** Title field for quick add. Enter adds the task and clears the field for the next one. */
@Composable
fun QuickAddField(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.new_task_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(onClick = submit, enabled = text.isNotBlank()) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_task))
            }
        },
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun AvailableList(
    tasks: List<Task>,
    today: LocalDate,
    onComplete: (TaskId) -> Unit,
    onMove: (TaskId, MoveDirection) -> Unit,
    onOpenTask: (TaskId) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tasks, key = { _, task -> task.id.toString() }) { index, task ->
            AvailableRow(
                task = task,
                today = today,
                canMoveUp = index > 0,
                canMoveDown = index < tasks.lastIndex,
                onComplete = { onComplete(task.id) },
                onMove = { onMove(task.id, it) },
                onOpen = { onOpenTask(task.id) },
            )
        }
    }
}

@Composable
private fun AvailableRow(
    task: Task,
    today: LocalDate,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onComplete: () -> Unit,
    onMove: (MoveDirection) -> Unit,
    onOpen: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val markComplete = stringResource(R.string.mark_complete)
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            Checkbox(
                checked = false,
                onCheckedChange = { onComplete() },
                modifier = Modifier.semantics { contentDescription = markComplete },
            )
        },
        headlineContent = { Text(task.title) },
        supportingContent = task.dueDate?.let { due -> { Text(stringResource(R.string.due_on, formatDate(due, today))) } },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        enabled = canMoveUp,
                        onClick = { menuOpen = false; onMove(MoveDirection.UP) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        enabled = canMoveDown,
                        onClick = { menuOpen = false; onMove(MoveDirection.DOWN) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = { menuOpen = false; onOpen() },
                    )
                }
            }
        },
    )
}

@Composable
private fun LockedList(tasks: List<LockedTask>, today: LocalDate, onOpenTask: (TaskId) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(tasks, key = { it.task.id.toString() }) { locked ->
            ListItem(
                modifier = Modifier.clickable { onOpenTask(locked.task.id) },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.locked)) },
                headlineContent = { Text(locked.task.title) },
                supportingContent = {
                    Column {
                        locked.reasons.forEach { Text(lockReasonText(it, today)) }
                        locked.unlockDate?.let {
                            Text(
                                stringResource(R.string.unlocks_on, formatDate(it, today)),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DoneList(tasks: List<Task>, onUncomplete: (TaskId) -> Unit, onOpenTask: (TaskId) -> Unit) {
    val markNotComplete = stringResource(R.string.mark_not_complete)
    LazyColumn(Modifier.fillMaxSize()) {
        items(tasks, key = { it.id.toString() }) { task ->
            ListItem(
                modifier = Modifier.clickable { onOpenTask(task.id) },
                leadingContent = {
                    Checkbox(
                        checked = true,
                        onCheckedChange = { onUncomplete(task.id) },
                        modifier = Modifier.semantics { contentDescription = markNotComplete },
                    )
                },
                headlineContent = { Text(task.title, textDecoration = TextDecoration.LineThrough) },
            )
        }
    }
}

const val TAB_AVAILABLE = 0
const val TAB_LOCKED = 1
const val TAB_DONE = 2

/** The top bar's ⋮ menu (app-wide entries, unlike the per-task menu on each row). */
@Composable
private fun AppMenu(onOpenGoogle: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.app_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.google_tasks)) },
                onClick = {
                    open = false
                    onOpenGoogle()
                },
            )
        }
    }
}
