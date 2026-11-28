package com.david.taskmanager.ui.google

import android.app.PendingIntent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.david.taskmanager.core.sync.RemoteTaskList
import com.david.taskmanager.data.ImportResult
import com.david.taskmanager.data.TaskRepository
import com.david.taskmanager.google.ConsentRequiredException
import com.david.taskmanager.google.GoogleApiException
import com.david.taskmanager.google.GoogleTasksGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

enum class GoogleError { NETWORK, CONSENT_DENIED, OTHER }

sealed interface GoogleTasksUiState {
    data object Loading : GoogleTasksUiState

    data class NotConnected(val working: Boolean = false, val error: GoogleError? = null) : GoogleTasksUiState

    /** Signed in; pick the list to import from (and, from slice 6, to mirror available tasks to). */
    data class ChooseList(
        val lists: List<RemoteTaskList>,
        val importingListId: String? = null,
        val error: GoogleError? = null,
    ) : GoogleTasksUiState

    /** [result] is only set right after the import, on this screen. */
    data class Connected(val listTitle: String, val result: ImportResult? = null) : GoogleTasksUiState
}

class GoogleTasksViewModel(
    private val repository: TaskRepository,
    private val gateway: GoogleTasksGateway,
    /** The import runs here, so leaving the screen never cancels it halfway (it is one transaction anyway). */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private sealed interface Step {
        data object Idle : Step
        data object Working : Step
        data class Failed(val error: GoogleError) : Step
        data class Choosing(val lists: List<RemoteTaskList>, val importingListId: String? = null, val error: GoogleError? = null) : Step
        data class Imported(val listTitle: String, val result: ImportResult) : Step
    }

    private val step = MutableStateFlow<Step>(Step.Idle)

    private val _consentRequest = MutableStateFlow<PendingIntent?>(null)

    /** When set, the screen must launch it (account picker / consent) and report back via [onConsentResult]. */
    val consentRequest: StateFlow<PendingIntent?> = _consentRequest.asStateFlow()

    val state: StateFlow<GoogleTasksUiState> = combine(repository.googleConnection, step) { connection, step ->
        when {
            step is Step.Imported -> GoogleTasksUiState.Connected(step.listTitle, step.result)
            connection != null -> GoogleTasksUiState.Connected(connection.listTitle)
            step is Step.Choosing -> GoogleTasksUiState.ChooseList(step.lists, step.importingListId, step.error)
            step is Step.Working -> GoogleTasksUiState.NotConnected(working = true)
            step is Step.Failed -> GoogleTasksUiState.NotConnected(error = step.error)
            else -> GoogleTasksUiState.NotConnected()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoogleTasksUiState.Loading)

    /** Signs in if needed, then loads the lists to choose from. */
    fun connect(): Job = viewModelScope.launch {
        step.value = Step.Working
        step.value = try {
            Step.Choosing(gateway.taskLists())
        } catch (e: ConsentRequiredException) {
            _consentRequest.value = e.pendingIntent
            Step.Working
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Step.Failed(errorOf(e))
        }
    }

    fun onConsentResult(granted: Boolean) {
        _consentRequest.value = null
        if (granted) connect() else step.value = Step.Failed(GoogleError.CONSENT_DENIED)
    }

    /** Imports [list]'s open tasks and connects to it. */
    fun chooseList(list: RemoteTaskList): Job {
        val choosing = step.value as? Step.Choosing ?: return Job().apply { complete() }
        if (choosing.importingListId != null) return Job().apply { complete() }
        step.value = choosing.copy(importingListId = list.id, error = null)
        return appScope.launch {
            step.value = try {
                val result = repository.importFromGoogle(list, gateway.openTasks(list.id))
                Step.Imported(list.title, result)
            } catch (e: ConsentRequiredException) {
                _consentRequest.value = e.pendingIntent
                Step.Working
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                choosing.copy(importingListId = null, error = errorOf(e))
            }
        }
    }

    /** Forgets the list and the links; tasks stay. Access itself is revoked in the Google account settings. */
    fun disconnect(): Job = appScope.launch {
        repository.disconnectGoogle()
        step.value = Step.Idle
    }

    private fun errorOf(e: Exception): GoogleError {
        Log.w("GoogleTasks", "Google Tasks request failed", e)
        return if (e is IOException && e !is GoogleApiException) GoogleError.NETWORK else GoogleError.OTHER
    }
}
