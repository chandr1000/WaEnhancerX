package com.waenhancer.ui.screens.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexSwitchPreference
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
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val entries by RootAuditLog.entries.collectAsState()
    val auditEnabled = RootGate.auditLogEnabled.value

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.rootop_log_title),
                onBackClick = { nav.popBack() }
            )
        },
        containerColor = colors.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = spacing.pageMargin)
        ) {
            WaexCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    WaexSwitchPreference(
                        title = stringResource(R.string.rootop_log_title),
                        checked = auditEnabled,
                        onCheckedChange = { value ->
                            RootGate.setAuditLogEnabled(context, value)
                        },
                        description = stringResource(R.string.rootop_log_toggle_desc),
                        showDivider = false
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.stackSm))
            Text(
                text = stringResource(R.string.rootop_log_cleared_on_exit),
                style = WaexTheme.typography.labelSm,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.gutter)
            )
            if (entries.isEmpty()) {
                WaexEmptyState(
                    title = stringResource(R.string.rootop_log_empty),
                    description = stringResource(R.string.rootop_log_cleared_on_exit),
                    icon = WaexIcons.Security
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(spacing.stackMd),
                    contentPadding = PaddingValues(vertical = spacing.stackSm)
                ) {
                    items(entries.asReversed(), key = { "${it.epochMs}_${it.commandId}_${it.callerTag}" }) { entry ->
                        AuditLogEntryCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditLogEntryCard(entry: RootAuditLog.Entry) {
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.epochMs))
    val outcomeText = outcomeLabel(entry.outcome)

    WaexCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$time · ${entry.commandId}",
                style = WaexTheme.typography.bodyLg,
                color = colors.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$outcomeText · ${entry.callerTag} · ${entry.durationMs}ms",
                style = WaexTheme.typography.bodyMd,
                color = colors.onSurfaceVariant
            )
            entry.outputPreview?.let { preview ->
                Spacer(modifier = Modifier.height(spacing.stackSm))
                Text(
                    text = stringResource(R.string.rootop_log_output_preview) + ": " + preview,
                    style = WaexTheme.typography.labelSm,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun outcomeLabel(outcome: RootResult): String = when (outcome) {
    is RootResult.Success -> stringResource(R.string.rootop_log_outcome_success)
    is RootResult.Completed -> stringResource(R.string.rootop_log_outcome_completed)
    is RootResult.Blocked -> when (outcome.reason) {
        BlockedReason.DISABLED_BY_USER -> stringResource(R.string.rootop_log_disabled_reason)
        else -> stringResource(R.string.rootop_log_blocked)
    }
    is RootResult.Failed -> when (outcome.kind) {
        FailureKind.TIMEOUT -> stringResource(R.string.rootop_log_outcome_timeout)
        FailureKind.NONZERO_EXIT -> stringResource(R.string.rootop_log_outcome_failed)
        FailureKind.SPAWN_ERROR -> stringResource(R.string.rootop_log_outcome_spawn_error)
    }
}
