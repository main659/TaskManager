package com.david.taskmanager.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.david.taskmanager.R
import com.david.taskmanager.core.TaskState
import com.david.taskmanager.core.model.Task
import com.david.taskmanager.core.model.TaskId
import com.david.taskmanager.ui.common.formatDate
import com.david.taskmanager.ui.common.lockReasonText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun TaskDetailRoute(viewModel: TaskDetailViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    when (val current = state) {
        DetailState.Loading -> Unit
        DetailState.NotFound -> LaunchedEffect(Unit) { onClose() }
        is DetailState.Loaded -> {
            val fields = text ?: return
            TaskDetailScreen(
                detail = current.detail,
                text = fields,
                error = error,
                onTitleChange = viewModel::onTitleChange,
                onNotesChange = viewModel::onNotesChange,
                onDueDateChange = { viewModel.setDueDate(it) },
                onAvailableFromChange = { viewModel.setAvailableFrom(it) },
                onAddDependency = { viewModel.addDependency(it) },
                onRemoveDependency = { viewModel.removeDependency(it) },
                onSetDelay = { id, days -> viewModel.setDelay(id, days) },
                onDelete = {
                    viewModel.delete()
                    onClose()
                },
                onBack = {
                    viewModel.saveText()
                    onClose()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    detail: TaskDetail,
    text: TextFields,
    error: String?,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDueDateChange: (LocalDate?) -> Unit,
    onAvailableFromChange: (LocalDate?) -> Unit,
    onAddDependency: (TaskId) -> Unit,
    onRemoveDependency: (TaskId) -> Unit,
    onSetDelay: (TaskId, Int) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pickingDependency by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.title)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = text.notes,
                onValueChange = onNotesChange,
                label = { Text(stringResource(R.string.notes)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            StatusLines(detail.state, detail.today)
            DateRow(stringResource(R.string.due_date), detail.task.dueDate, detail.today, onDueDateChange)
            DateRow(
                stringResource(R.string.available_from_label),
                detail.task.availableFrom,
                detail.today,
                onAvailableFromChange,
            )

            Text(
                stringResource(R.string.dependencies),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (detail.dependencies.isEmpty()) Text(stringResource(R.string.no_dependencies))
            detail.dependencies.forEach { item ->
                DependencyRow(
                    item = item,
                    onDecrease = { onSetDelay(item.task.id, item.delayDays - 1) },
                    onIncrease = { onSetDelay(item.task.id, item.delayDays + 1) },
                    onRemove = { onRemoveDependency(item.task.id) },
                )
            }
            TextButton(onClick = { pickingDependency = true }) { Text(stringResource(R.string.add_dependency)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (pickingDependency) {
        DependencyPicker(
            candidates = detail.candidates,
            onPick = {
                pickingDependency = false
                onAddDependency(it)
            },
            onDismiss = { pickingDependency = false },
        )
    }
    if (confirmDelete) {
        DeleteDialog(
            dependentCount = detail.dependents.size,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun StatusLines(state: TaskState, today: LocalDate) {
    when (state) {
        TaskState.Available -> Unit
        TaskState.Done -> Text(stringResource(R.string.status_done), color = MaterialTheme.colorScheme.primary)
        is TaskState.Locked -> Column {
            state.reasons.forEach { Text(lockReasonText(it, today), color = MaterialTheme.colorScheme.secondary) }
            state.unlockDate?.let {
                Text(stringResource(R.string.unlocks_on, formatDate(it, today)), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(label: String, date: LocalDate?, today: LocalDate, onChange: (LocalDate?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { picking = true },
        leadingContent = { Icon(Icons.Default.DateRange, contentDescription = null) },
        overlineContent = { Text(label) },
        headlineContent = { Text(date?.let { formatDate(it, today) } ?: stringResource(R.string.not_set)) },
        trailingContent = date?.let {
            {
                IconButton(onClick = { onChange(null) }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                }
            }
        },
    )
    if (picking) {
        // DatePicker works in UTC milliseconds at the start of the selected day.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
private fun DependencyRow(item: DependencyItem, onDecrease: () -> Unit, onIncrease: () -> Unit, onRemove: () -> Unit) {
    val fewer = stringResource(R.string.decrease_delay)
    val more = stringResource(R.string.increase_delay)
    ListItem(
        headlineContent = { Text(item.task.title) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onDecrease,
                    enabled = item.delayDays > 0,
                    modifier = Modifier.semantics { contentDescription = fewer },
                ) { Text("−") }
                Text(pluralStringResource(R.plurals.delay_days, item.delayDays, item.delayDays))
                TextButton(
                    onClick = onIncrease,
                    enabled = item.delayDays < TaskDetailViewModel.MAX_DELAY_DAYS,
                    modifier = Modifier.semantics { contentDescription = more },
                ) { Text("+") }
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_dependency))
            }
        },
    )
}

@Composable
private fun DependencyPicker(candidates: List<Task>, onPick: (TaskId) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_dependency)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.no_candidates))
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(candidates, key = { it.id.toString() }) { task ->
                        ListItem(
                            headlineContent = { Text(task.title) },
                            modifier = Modifier.clickable { onPick(task.id) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DeleteDialog(dependentCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Text(
                if (dependentCount == 0) stringResource(R.string.delete_message)
                else pluralStringResource(R.plurals.delete_message_with_dependents, dependentCount, dependentCount),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
