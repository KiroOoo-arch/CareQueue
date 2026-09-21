package com.carequeue.plus.ui.customer

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
import com.carequeue.plus.ui.components.SectionHeader
import com.carequeue.plus.ui.components.StatusBadge
import com.carequeue.plus.util.DateUtils
import com.carequeue.plus.viewmodel.AuthViewModel
import com.carequeue.plus.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueHistoryScreen(
    userId: String,
    onNavigateBack: () -> Unit,
    authViewModel: AuthViewModel = viewModel(),
    queueViewModel: QueueViewModel = viewModel()
) {
    val userHistory by queueViewModel.userHistory.collectAsState()

    LaunchedEffect(userId) {
        queueViewModel.loadUserHistory(userId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue History") },
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
            SectionHeader(title = "Visit History")

            if (userHistory.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No queue history yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn {
                    items(userHistory) { entry ->
                        HistoryEntryCard(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryEntryCard(entry: QueueEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Queue #${entry.queueNumber}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                val (timestampLabel, timestamp) = when (entry.status) {
                    QueueEntry.STATUS_SERVED -> "Served on" to entry.servedAt
                    QueueEntry.STATUS_SKIPPED -> "Skipped · joined on" to entry.joinedAt
                    QueueEntry.STATUS_CANCELLED -> "Cancelled · joined on" to entry.joinedAt
                    else -> "Joined on" to entry.joinedAt
                }
                timestamp?.let {
                    Text(
                        text = "$timestampLabel ${DateUtils.formatDateTime(it)}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusBadge(status = entry.status)
        }
    }
}
