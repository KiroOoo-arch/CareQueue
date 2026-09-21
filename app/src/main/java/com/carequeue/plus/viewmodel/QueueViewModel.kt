package com.carequeue.plus.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carequeue.plus.data.firebase.FirebaseConfig
import com.carequeue.plus.data.model.Business
import com.carequeue.plus.data.model.Queue
import com.carequeue.plus.data.model.QueueEntry
import com.carequeue.plus.data.repository.QueueRepository
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

    private val _userEntry = MutableStateFlow<QueueEntry?>(null)
    val userEntry: StateFlow<QueueEntry?> = _userEntry

    private val _waitingEntries = MutableStateFlow<List<QueueEntry>>(emptyList())
    val waitingEntries: StateFlow<List<QueueEntry>> = _waitingEntries

    private val _smartReturn = MutableStateFlow<SmartReturnResult?>(null)
    val smartReturn: StateFlow<SmartReturnResult?> = _smartReturn

    private val _userHistory = MutableStateFlow<List<QueueEntry>>(emptyList())
    val userHistory: StateFlow<List<QueueEntry>> = _userHistory

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

    fun loadAllQueues() {
        viewModelScope.launch {
            queueRepository.getAllQueues().fold(
                onSuccess = { _queues.value = it },
                onFailure = { e ->
                    Log.e("QueueViewModel", "Failed to load all queues: ${e.message}", e)
                    _errorMessage.value = "Failed to load queues: ${e.message}"
                }
            )
        }
    }

    fun loadQueue(queueId: String) {
        viewModelScope.launch {
            val queue = queueRepository.getQueue(queueId)
            _currentQueue.value = queue
        }
    }

    fun observeQueue(queueId: String) {
        viewModelScope.launch {
            queueRepository.observeQueue(queueId).collect { queue ->
                _currentQueue.value = queue
                if (_userEntry.value != null && queue != null) {
                    updateSmartReturn(queue, _userEntry.value!!)
                }
            }
        }
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
            queueRepository.markServed(entryId)
        }
    }

    fun skipEntry(entryId: String) {
        viewModelScope.launch {
            queueRepository.skipEntry(entryId)
        }
    }

    fun cancelEntry(entryId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            queueRepository.cancelEntry(entryId).fold(
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
            val newStatus = if (currentIsOpen) {
                Queue.STATUS_CLOSED
            } else {
                Queue.STATUS_OPEN
            }
            try {
                FirebaseConfig.db
                    .collection(FirebaseConfig.QUEUES)
                    .document(queueId)
                    .update("status", newStatus)
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
