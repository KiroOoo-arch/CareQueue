package com.carequeue.plus.ui.customer

import androidx.compose.foundation.clickable
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
import com.carequeue.plus.data.model.Business
import com.carequeue.plus.ui.components.SectionHeader
import com.carequeue.plus.viewmodel.AuthViewModel
import com.carequeue.plus.viewmodel.QueueViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerHomeScreen(
    onNavigateToServiceDetails: (String, String) -> Unit,
    onNavigateToHistory: (String) -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onLogout: () -> Unit,
    authViewModel: AuthViewModel = viewModel(),
    queueViewModel: QueueViewModel = viewModel()
) {
    val businesses by queueViewModel.businesses.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState()
    val errorMessage by queueViewModel.errorMessage.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    fun loadData() {
        coroutineScope.launch {
            isRefreshing = true
            queueViewModel.loadBusinesses()
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CareQueue+") },
                actions = {
                    IconButton(onClick = { currentUser?.let { onNavigateToSettings(it.uid) } }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = {
                        authViewModel.logout()
                        onLogout()
                    }) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
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
            // Welcome message
            Text(
                text = "Hello, ${currentUser?.name ?: "Guest"}!",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select a service to join the queue",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Error: " + errorMessage,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            }

            Button(
                onClick = { loadData() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                enabled = !isRefreshing
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isRefreshing) "Loading..." else "Refresh")
            }

            SectionHeader(title = "Available Services")

            if (businesses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Store,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No services available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn {
                    items(businesses) { business ->
                        BusinessCard(
                            business = business,
                            onClick = {
                                // Fetch this business's queues and navigate to the first one.
                                // One-shot fetch: never read the shared state flow, which may
                                // still hold the previous business's queues.
                                coroutineScope.launch {
                                    val queues = queueViewModel.fetchQueuesForBusiness(business.businessId)
                                    val firstQueue = queues.firstOrNull()
                                    if (firstQueue != null) {
                                        onNavigateToServiceDetails(business.businessId, firstQueue.queueId)
                                    } else {
                                        queueViewModel.reportError("No queues available for ${business.name}")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessCard(
    business: Business,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (business.category.lowercase()) {
                    "clinic", "medical" -> Icons.Default.LocalHospital
                    "salon", "beauty" -> Icons.Default.ContentCut
                    "restaurant", "food" -> Icons.Default.Restaurant
                    "repair" -> Icons.Default.Build
                    else -> Icons.Default.Store
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = business.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = business.category,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
