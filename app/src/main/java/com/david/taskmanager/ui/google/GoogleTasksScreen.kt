package com.david.taskmanager.ui.google

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.david.taskmanager.R
import com.david.taskmanager.core.sync.RemoteTaskList

private const val GOOGLE_PERMISSIONS_URL = "https://myaccount.google.com/permissions"

@Composable
fun GoogleTasksRoute(viewModel: GoogleTasksViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val consentRequest by viewModel.consentRequest.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onConsentResult(granted = it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consentRequest) {
        consentRequest?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    GoogleTasksScreen(
        state = state,
        onBack = onBack,
        onConnect = { viewModel.connect() },
        onChooseList = { viewModel.chooseList(it) },
        onDisconnect = { viewModel.disconnect() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleTasksScreen(
    state: GoogleTasksUiState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onChooseList: (RemoteTaskList) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.google_tasks)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val content = Modifier.padding(padding).fillMaxSize().padding(16.dp)
        when (state) {
            GoogleTasksUiState.Loading -> Unit
            is GoogleTasksUiState.NotConnected -> NotConnected(state, onConnect, content)
            is GoogleTasksUiState.ChooseList -> ChooseList(state, onChooseList, content)
            is GoogleTasksUiState.Connected -> Connected(state, onDisconnect, content)
        }
    }
}

@Composable
private fun NotConnected(state: GoogleTasksUiState.NotConnected, onConnect: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.google_intro))
        state.error?.let { ErrorText(it) }
        if (state.working) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onConnect) { Text(stringResource(R.string.google_connect)) }
        }
    }
}

@Composable
private fun ChooseList(
    state: GoogleTasksUiState.ChooseList,
    onChooseList: (RemoteTaskList) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.google_choose_list))
        state.error?.let { ErrorText(it) }
        if (state.lists.isEmpty()) Text(stringResource(R.string.google_no_lists))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.lists, key = { it.id }) { list ->
                val importing = state.importingListId == list.id
                ListItem(
                    headlineContent = { Text(list.title) },
                    supportingContent = if (importing) {
                        { Text(stringResource(R.string.google_importing)) }
                    } else {
                        null
                    },
                    trailingContent = if (importing) {
                        { CircularProgressIndicator(Modifier.size(24.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable(enabled = state.importingListId == null) { onChooseList(list) },
                )
            }
        }
    }
}

@Composable
private fun Connected(state: GoogleTasksUiState.Connected, onDisconnect: () -> Unit, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.google_connected_to, state.listTitle), style = MaterialTheme.typography.titleMedium)
        state.result?.let { result ->
            Text(pluralStringResource(R.plurals.google_imported, result.imported, result.imported))
            if (result.matched > 0) {
                Text(pluralStringResource(R.plurals.google_matched, result.matched, result.matched))
            }
        }
        Text(stringResource(R.string.google_sync_later), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.google_disconnect)) }
        Text(stringResource(R.string.google_disconnect_explained), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uriHandler.openUri(GOOGLE_PERMISSIONS_URL) }) {
            Text(stringResource(R.string.google_manage_access))
        }
    }
}

@Composable
private fun ErrorText(error: GoogleError) {
    Text(
        stringResource(
            when (error) {
                GoogleError.NETWORK -> R.string.google_error_network
                GoogleError.CONSENT_DENIED -> R.string.google_error_consent
                GoogleError.OTHER -> R.string.google_error_other
            },
        ),
        color = MaterialTheme.colorScheme.error,
    )
}
