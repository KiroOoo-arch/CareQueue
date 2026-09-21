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
import com.carequeue.plus.domain.analytics.QueueAnalytics
import com.carequeue.plus.ui.components.InfoCard
import com.carequeue.plus.ui.components.SectionHeader
import com.carequeue.plus.viewmodel.QueueAnalyticsRow
import com.carequeue.plus.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    adminId: String,
    onNavigateBack: () -> Unit,
    queueViewModel: QueueViewModel = viewModel()
) {
    val rows by queueViewModel.analytics.collectAsState()
    val isLoading by queueViewModel.isLoadingAnalytics.collectAsState()

    LaunchedEffect(adminId) {
        queueViewModel.loadAnalytics(adminId)
    }

    // Totals across the admin's queues. Averages are weighted by how many customers
    // each queue actually served, so a quiet queue cannot skew the number.
    val totalServed = rows.sumOf { it.summary.served }
    val totalSkipped = rows.sumOf { it.summary.skipped }
    val totalCancelled = rows.sumOf { it.summary.cancelled }
    val avgService = weightedAverage(rows) { it.summary.averageServiceMinutes to it.summary.served }
    val avgWait = weightedAverage(rows) { it.summary.averageWaitMinutes to it.summary.served }
    val hasAnyData = rows.any { it.summary.hasData }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics") },
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
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                rows.isEmpty() -> EmptyAnalytics(
                    title = "No queues assigned to you",
                    detail = "Analytics appear once a queue belongs to one of your businesses."
                )

                !hasAnyData -> EmptyAnalytics(
                    title = "No completed visits yet",
                    detail = "Statistics are built from served, skipped and cancelled visits. " +
                        "They will appear here once customers have been through the queue."
                )

                else -> {
                    SectionHeader(title = "Queue Statistics")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Each card needs a weight: InfoCard fills its own width
                        // internally, so two unweighted cards squeeze each other
                        // off-screen.
                        InfoCard(
                            title = "Customers Served",
                            value = "$totalServed",
                            icon = Icons.Default.People,
                            modifier = Modifier.weight(1f)
                        )
                        InfoCard(
                            title = "Avg Service Time",
                            value = if (totalServed > 0) QueueAnalytics.formatMinutes(avgService) else "—",
                            icon = Icons.Default.Timer,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InfoCard(
                            title = "Avg Wait Time",
                            value = if (totalServed > 0) QueueAnalytics.formatMinutes(avgWait) else "—",
                            icon = Icons.Default.Schedule,
                            modifier = Modifier.weight(1f)
                        )
                        InfoCard(
                            title = "Skipped / Cancelled",
                            value = "$totalSkipped / $totalCancelled",
                            icon = Icons.Default.TrendingUp,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    SectionHeader(title = "By Queue")

                    LazyColumn {
                        items(rows) { row -> QueueStatsCard(row) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyAnalytics(title: String, detail: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = detail,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QueueStatsCard(row: QueueAnalyticsRow) {
    val summary = row.summary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = row.queue.name.ifBlank { row.queue.queueId },
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Status: ${row.queue.status}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (!summary.hasData) {
                Text(
                    text = "No completed visits yet",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            StatLine("Served", "${summary.served}")
            StatLine("Skipped", "${summary.skipped}")
            StatLine("Cancelled", "${summary.cancelled}")
            StatLine("Avg service time", QueueAnalytics.formatMinutes(summary.averageServiceMinutes))
            StatLine("Avg wait time", QueueAnalytics.formatMinutes(summary.averageWaitMinutes))
            summary.peakHour?.let {
                StatLine("Peak period", QueueAnalytics.formatHour(it))
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Mean of [pick] weighted by its sample size, so queues that served nobody do not
 * drag the figure toward zero.
 */
private fun weightedAverage(
    rows: List<QueueAnalyticsRow>,
    pick: (QueueAnalyticsRow) -> Pair<Double, Int>
): Double {
    var weightedSum = 0.0
    var totalWeight = 0
    rows.forEach { row ->
        val (value, weight) = pick(row)
        if (weight > 0) {
            weightedSum += value * weight
            totalWeight += weight
        }
    }
    return if (totalWeight == 0) 0.0 else weightedSum / totalWeight
}
