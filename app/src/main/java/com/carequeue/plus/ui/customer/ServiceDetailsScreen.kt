package com.carequeue.plus.ui.customer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carequeue.plus.data.model.QueueEntry
import com.carequeue.plus.ui.components.ActionButton
import com.carequeue.plus.ui.components.ErrorState
import com.carequeue.plus.ui.components.InfoCard
import com.carequeue.plus.ui.components.QueueNumberDisplay
import com.carequeue.plus.ui.components.SectionHeader
import com.carequeue.plus.viewmodel.AuthViewModel
import com.carequeue.plus.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceDetailsScreen(
    businessId: String,
    queueId: String,
    onNavigateBack: () -> Unit,
    onNavigateToMyQueue: (String, String) -> Unit,
    authViewModel: AuthViewModel = viewModel(),
    queueViewModel: QueueViewModel = viewModel()
) {
    val currentQueue by queueViewModel.currentQueue.collectAsState()
    val queueError by queueViewModel.queueError.collectAsState()
    val userEntry by queueViewModel.userEntry.collectAsState()
    val smartReturn by queueViewModel.smartReturn.collectAsState()
    val uiState by queueViewModel.uiState.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState()
    val waitingEntries by queueViewModel.waitingEntries.collectAsState()

    // Also keyed on the signed-in user so entry observation starts even when
    // auth resolves after first composition.
    LaunchedEffect(queueId, currentUser?.uid) {
        queueViewModel.loadQueue(queueId)
        queueViewModel.observeQueue(queueId)
        queueViewModel.observeWaitingEntries(queueId)
        currentUser?.let { user ->
            queueViewModel.observeUserEntry(queueId, user.uid)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentQueue?.name ?: "Service Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            currentQueue?.let { queue ->
                // The number actually being served. Falls back to the value persisted
                // on the queue so it does not blank out the moment that customer is
                // marked served and leaves the live waiting list.
                val nowServing = waitingEntries
                    .filter { it.status == QueueEntry.STATUS_CALLED }
                    .maxOfOrNull { it.queueNumber }
                    ?: queue.nowServing.takeIf { it > 0 }

                // Queue status
                Text(
                    text = if (queue.isOpen) "Queue Open" else "Queue Closed",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (queue.isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Info cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoCard(
                        title = "Now Serving",
                        value = nowServing?.let { "#$it" } ?: "—",
                        icon = Icons.Default.Person,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // User's entry if they have one
                userEntry?.let { entry ->
                    SectionHeader(title = "Your Queue Entry")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            QueueNumberDisplay(queueNumber = entry.queueNumber)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Your Number",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Status: ${entry.status}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Holding an entry previously left this screen a dead end: the
                    // join button is hidden, so there was no way through to the live
                    // position, SmartReturn estimate or Cancel action.
                    ActionButton(
                        text = "View My Queue",
                        onClick = {
                            currentUser?.let { user ->
                                onNavigateToMyQueue(queue.queueId, user.uid)
                            }
                        }
                    )
                }

                // Join queue button (if no active entry)
                if (userEntry == null && queue.isOpen) {
                    Spacer(modifier = Modifier.height(24.dp))
                    ActionButton(
                        text = if (uiState.isLoading) "Joining..." else "Join Queue",
                        onClick = {
                            currentUser?.let { user ->
                                queueViewModel.joinQueue(queue.queueId, user.uid) { success ->
                                    // Navigate to My Queue only when the join succeeded,
                                    // so failures stay visible on this screen.
                                    if (success) onNavigateToMyQueue(queue.queueId, user.uid)
                                }
                            }
                        },
                        enabled = !uiState.isLoading
                    )
                }

                // Error message
                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }
            } ?: run {
                // Loading, or a failure that needs to be actionable rather than a
                // spinner that never resolves.
                if (queueError != null) {
                    ErrorState(
                        message = queueError!!,
                        onRetry = { queueViewModel.retryQueue(queueId) }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

