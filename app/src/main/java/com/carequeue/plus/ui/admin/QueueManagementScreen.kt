package com.carequeue.plus.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.carequeue.plus.ui.components.InfoCard
import com.carequeue.plus.ui.components.SectionHeader
import com.carequeue.plus.ui.components.StatusBadge
import com.carequeue.plus.util.DateUtils
import com.carequeue.plus.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueManagementScreen(
    queueId: String,
    onNavigateBack: () -> Unit,
    queueViewModel: QueueViewModel = viewModel()
) {
    val currentQueue by queueViewModel.currentQueue.collectAsState()
    val waitingEntries by queueViewModel.waitingEntries.collectAsState()
    val uiState by queueViewModel.uiState.collectAsState()

    LaunchedEffect(queueId) {
        queueViewModel.loadQueue(queueId)
        queueViewModel.observeQueue(queueId)
        queueViewModel.observeWaitingEntries(queueId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentQueue?.name ?: "Queue Management") },
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
                .padding(16.dp)
        ) {
            // The last number actually called, not the last number issued.
            val nowServing = waitingEntries
                .filter { it.status == QueueEntry.STATUS_CALLED }
                .maxOfOrNull { it.queueNumber }

            currentQueue?.let { queue ->
                // Queue info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoCard(
                        title = "Now Serving",
                        value = nowServing?.let { "#$it" } ?: "—",
                        icon = Icons.Default.Person
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Admin controls
                SectionHeader(title = "Queue Controls")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Call Next button
                    Button(
                        onClick = { queueViewModel.callNext(queue.queueId) },
                        enabled = !uiState.isLoading && waitingEntries.any { it.status == QueueEntry.STATUS_WAITING }
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Call Next")
                    }

                    // Toggle queue status
                    Button(
                        onClick = {
                            queueViewModel.toggleQueueStatus(queue.queueId, queue.isOpen)
                        }
                    ) {
                        Icon(
                            if (queue.isOpen) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (queue.isOpen) "Close Queue" else "Open Queue")
                    }
                }

                uiState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Waiting entries list
                SectionHeader(title = "Waiting Customers (${waitingEntries.size})")

                if (waitingEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No customers waiting",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn {
                        items(waitingEntries) { entry ->
                            EntryManagementCard(
                                entry = entry,
                                onCallNext = {
                                    if (entry.status == QueueEntry.STATUS_WAITING) {
                                        queueViewModel.callNext(queue.queueId)
                                    }
                                },
                                onMarkServed = {
                                    queueViewModel.markServed(entry.entryId)
                                },
                                onSkip = {
                                    queueViewModel.skipEntry(entry.entryId)
                                }
                            )
                        }
                    }
                }
            } ?: run {
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

@Composable
fun EntryManagementCard(
    entry: QueueEntry,
    onCallNext: () -> Unit,
    onMarkServed: () -> Unit,
    onSkip: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${entry.queueNumber}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(50.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "User: ${entry.userId.take(8)}...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Joined: ${DateUtils.timeAgo(entry.joinedAt)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(status = entry.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (entry.status == QueueEntry.STATUS_CALLED) {
                    TextButton(onClick = onMarkServed) {
                        Text("Mark Served")
                    }
                    TextButton(onClick = onSkip) {
                        Text("Skip")
                    }
                }
            }
        }
    }
}
