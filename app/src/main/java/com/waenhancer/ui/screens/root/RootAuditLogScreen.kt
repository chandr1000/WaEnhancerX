package com.waenhancer.ui.screens.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.root.BlockedReason
import com.waenhancer.root.FailureKind
import com.waenhancer.root.RootAuditLog
import com.waenhancer.root.RootGate
import com.waenhancer.root.RootResult
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RootAuditLogScreen() {
    val nav = LocalWaexNavController.current
    val context = LocalContext.current
    val entries by RootAuditLog.entries.collectAsState()
    // Read the Compose-observable state directly so the switch recomposes on toggle.
    val auditEnabled = RootGate.auditLogEnabled.value

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.rootop_log_title),
                onBackClick = { nav.popBack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rootop_log_cleared_on_exit),
                        style = WaexTheme.typography.bodySm,
                        color = WaexTheme.colors.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.rootop_log_toggle_desc),
                        style = WaexTheme.typography.bodySm,
                        color = WaexTheme.colors.onSurfaceVariant
                    )
                }
                StitchSwitch(
                    checked = auditEnabled,
                    onCheckedChange = { value ->
                        RootGate.setAuditLogEnabled(context, value)
                    }
                )
            }
            if (entries.isEmpty()) {
                WaexEmptyState(
                    title = stringResource(R.string.rootop_log_empty),
                    description = stringResource(R.string.rootop_log_cleared_on_exit),
                    icon = WaexIcons.Security,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(entries.asReversed()) { entry ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(entry.epochMs))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "$time · ${entry.commandId}",
                                style = WaexTheme.typography.bodyMd,
                                color = WaexTheme.colors.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = outcomeLabel(entry.outcome) + " · ${entry.callerTag} · ${entry.durationMs}ms",
                                style = WaexTheme.typography.bodySm,
                                color = WaexTheme.colors.onSurfaceVariant
                            )
                            entry.outputPreview?.let { preview ->
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.rootop_log_output_preview) + ": " + preview,
                                    style = WaexTheme.typography.bodySm,
                                    color = WaexTheme.colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun outcomeLabel(outcome: RootResult): String = when (outcome) {
    is RootResult.Success -> "OK"
    is RootResult.Completed -> stringResource(R.string.rootop_log_output_preview)
    is RootResult.Blocked -> when (outcome.reason) {
        BlockedReason.DISABLED_BY_USER -> stringResource(R.string.rootop_log_disabled_reason)
        else -> stringResource(R.string.rootop_log_blocked)
    }
    is RootResult.Failed -> when (outcome.kind) {
        FailureKind.TIMEOUT -> "Timed out"
        FailureKind.NONZERO_EXIT -> "Failed"
        FailureKind.SPAWN_ERROR -> "Spawn error"
    }
}
