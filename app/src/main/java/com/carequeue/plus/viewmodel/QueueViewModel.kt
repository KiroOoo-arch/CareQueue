package com.carequeue.plus.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carequeue.plus.data.firebase.FirebaseConfig
import com.carequeue.plus.data.model.Business
import com.carequeue.plus.data.model.Queue
import com.carequeue.plus.data.model.QueueEntry
import com.carequeue.plus.data.repository.QueueRepository
import com.carequeue.plus.domain.analytics.ServiceSummary
import com.carequeue.plus.domain.smartereturn.SmartReturn
import com.carequeue.plus.domain.smartereturn.SmartReturnResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class QueueViewModel : ViewModel() {
    private val queueRepository = QueueRepository()

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState

    private val _businesses = MutableStateFlow<List<Business>>(emptyList())
    val businesses: StateFlow<List<Business>> = _businesses

    private val _queues = MutableStateFlow<List<Queue>>(emptyList())
    val queues: StateFlow<List<Queue>> = _queues

    private val _currentQueue = MutableStateFlow<Queue?>(null)
    val currentQueue: StateFlow<Queue?> = _currentQueue

    /** Non-null when the queue could not be loaded, so screens can offer a retry. */
    private val _queueError = MutableStateFlow<String?>(null)
    val queueError: StateFlow<String?> = _queueError

    private val _userEntry = MutableStateFlow<QueueEntry?>(null)
    val userEntry: StateFlow<QueueEntry?> = _userEntry

    private val _waitingEntries = MutableStateFlow<List<QueueEntry>>(emptyList())
    val waitingEntries: StateFlow<List<QueueEntry>> = _waitingEntries

    private val _smartReturn = MutableStateFlow<SmartReturnResult?>(null)
    val smartReturn: StateFlow<SmartReturnResult?> = _smartReturn

    private val _userHistory = MutableStateFlow<List<QueueEntry>>(emptyList())
    val userHistory: StateFlow<List<QueueEntry>> = _userHistory

    private val _analytics = MutableStateFlow<List<QueueAnalyticsRow>>(emptyList())
    val analytics: StateFlow<List<QueueAnalyticsRow>> = _analytics

    private val _isLoadingAnalytics = MutableStateFlow(false)
    val isLoadingAnalytics: StateFlow<Boolean> = _isLoadingAnalytics

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    suspend fun loadBusinesses() {
        val result = queueRepository.getActiveBusinesses()
        result.fold(
            onSuccess = { list ->
                Log.d("QueueViewModel", "Loaded ${list.size} businesses")
                _businesses.value = list
                _errorMessage.value = null
            },
            onFailure = { e ->
                Log.e("QueueViewModel", "Failed to load businesses: ${e.message}", e)
                _errorMessage.value = "Failed to load businesses: ${e.message}"
                _businesses.value = emptyList()
            }
        )
    }

    /** One-shot fetch used for navigation so stale shared state can never be read. */
    suspend fun fetchQueuesForBusiness(businessId: String): List<Queue> {
        val result = queueRepository.getQueuesForBusiness(businessId)
        return result.fold(
            onSuccess = { it },
            onFailure = { e ->
                Log.e("QueueViewModel", "Failed to load queues: ${e.message}", e)
                _errorMessage.value = "Failed to load queues: ${e.message}"
                emptyList()
            }
        )
    }

    /** Queues owned by this admin's businesses. */
    fun loadQueuesForAdmin(adminId: String) {
        viewModelScope.launch {
            queueRepository.getQueuesForAdmin(adminId).fold(
                onSuccess = { _queues.value = it },
                onFailure = { e ->
                    Log.e("QueueViewModel", "Failed to load admin queues: ${e.message}", e)
                    _errorMessage.value = "Failed to load queues: ${e.message}"
                }
            )
        }
    }

    /** Statistics for each of this admin's queues, computed from real entry history. */
    fun loadAnalytics(adminId: String) {
        viewModelScope.launch {
            _isLoadingAnalytics.value = true
            val queues = queueRepository.getQueuesForAdmin(adminId).getOrElse { emptyList() }
            _analytics.value = queues.map { queue ->
                QueueAnalyticsRow(
                    queue = queue,
                    summary = queueRepository.getQueueStats(queue.queueId)
                        .getOrElse { ServiceSummary() }
                )
            }
            _isLoadingAnalytics.value = false
        }
    }

    fun loadQueue(queueId: String) {
        viewModelScope.launch {
            val queue = queueRepository.getQueue(queueId)
            _currentQueue.value = queue
            if (queue == null) {
                _queueError.value = "This service is unavailable. It may have been removed."
            } else {
                _queueError.value = null
            }
        }
    }

    fun observeQueue(queueId: String) {
        viewModelScope.launch {
            queueRepository.observeQueue(queueId).collect { result ->
                result.fold(
                    onSuccess = { queue ->
                        _currentQueue.value = queue
                        // A missing document is a real, reportable state — not
                        // "still loading" — otherwise the screen spins forever.
                        _queueError.value =
                            if (queue == null) "This service is unavailable. It may have been removed."
                            else null
                        if (queue != null && _userEntry.value != null) {
                            updateSmartReturn(queue, _userEntry.value!!)
                        }
                    },
                    onFailure = { e ->
                        Log.e("QueueViewModel", "Queue listener failed: ${e.message}", e)
                        _queueError.value = "Lost connection to this queue. Check your network."
                    }
                )
            }
        }
    }

    /** Re-attempts loading after a listener or fetch failure. */
    fun retryQueue(queueId: String) {
        _queueError.value = null
        loadQueue(queueId)
        observeQueue(queueId)
    }

    fun observeUserEntry(queueId: String, userId: String) {
        viewModelScope.launch {
            queueRepository.observeUserActiveEntry(queueId, userId).collect { entry ->
                _userEntry.value = entry
                if (entry != null && _currentQueue.value != null) {
                    updateSmartReturn(_currentQueue.value!!, entry)
                }
            }
        }
    }

    fun observeWaitingEntries(queueId: String) {
        viewModelScope.launch {
            queueRepository.observeWaitingEntries(queueId).collect { entries ->
                _waitingEntries.value = entries
                val entry = _userEntry.value
                if (entry != null && _currentQueue.value != null) {
                    val peopleAhead = entries.count { it.queueNumber < entry.queueNumber }
                    val smartReturnResult = SmartReturn.calculateSmartReturn(peopleAhead, _currentQueue.value!!)
                    _smartReturn.value = smartReturnResult
                }
            }
        }
    }

    private fun updateSmartReturn(queue: Queue, entry: QueueEntry) {
        viewModelScope.launch {
            val peopleAhead = queueRepository.getPeopleAhead(entry.queueId, entry.queueNumber)
            val smartReturnResult = SmartReturn.calculateSmartReturn(peopleAhead, queue)
            _smartReturn.value = smartReturnResult
        }
    }

    fun joinQueue(queueId: String, userId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = queueRepository.joinQueue(queueId, userId)
            result.fold(
                onSuccess = { entry ->
                    _userEntry.value = entry
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    observeUserEntry(queueId, userId)
                    observeQueue(queueId)
                    observeWaitingEntries(queueId)
                    onResult(true)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                    onResult(false)
                }
            )
        }
    }

    fun callNext(queueId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            queueRepository.callNext(queueId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                },
                onFailure = { e ->
                    Log.e("QueueViewModel", "Failed to call next: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to call next"
                    )
                }
            )
        }
    }

    fun markServed(entryId: String) {
        viewModelScope.launch {
            queueRepository.markServed(entryId).onFailure { e ->
                Log.e("QueueViewModel", "Failed to mark served: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to mark customer served"
                )
            }
        }
    }

    fun skipEntry(entryId: String) {
        viewModelScope.launch {
            queueRepository.skipEntry(entryId).onFailure { e ->
                Log.e("QueueViewModel", "Failed to skip entry: ${e.message}", e)
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to skip customer")
            }
        }
    }

    fun cancelEntry(entryId: String, userId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            queueRepository.cancelEntry(entryId, userId).fold(
                onSuccess = {
                    _userEntry.value = null
                    _smartReturn.value = null
                    onResult(true)
                },
                onFailure = { e ->
                    Log.e("QueueViewModel", "Failed to cancel: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to cancel")
                    onResult(false)
                }
            )
        }
    }

    fun loadUserHistory(userId: String) {
        viewModelScope.launch {
            val history = queueRepository.getUserHistory(userId)
            _userHistory.value = history
        }
    }

    fun toggleQueueStatus(queueId: String, currentIsOpen: Boolean) {
        viewModelScope.launch {
            val isOpening = !currentIsOpen
            val newStatus = if (currentIsOpen) Queue.STATUS_CLOSED else Queue.STATUS_OPEN
            try {
                val updates = mutableMapOf<String, Any>("status" to newStatus)
                // Reopening starts a fresh serving session, so the persisted
                // "Now Serving" from the previous session must not linger.
                if (isOpening) updates["nowServing"] = 0
                FirebaseConfig.db
                    .collection(FirebaseConfig.QUEUES)
                    .document(queueId)
                    .update(updates)
                    .await()
            } catch (e: Exception) {
                Log.e("QueueViewModel", "Failed to update queue status: ${e.message}", e)
                _errorMessage.value = "Failed to update queue status: ${e.message}"
            }
        }
    }

    fun reportError(message: String) {
        _errorMessage.value = message
    }

    fun clearError() {
        _errorMessage.value = null
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class QueueUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)

data class QueueAnalyticsRow(
    val queue: Queue,
    val summary: ServiceSummary
)
