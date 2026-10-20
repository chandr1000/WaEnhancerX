package com.waenhancer.ui.screens.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.root.RootAuditLog
import com.waenhancer.ui.components.WaexEmptyState
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun RootAuditLogScreen() {
    val nav = LocalWaexNavController.current
    val entries by RootAuditLog.entries.collectAsState()

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.rootop_log_title),
                onBack = { nav.popBackStack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = stringResource(R.string.rootop_log_cleared_on_exit),
                style = WaexTheme.typography.bodySm,
                color = WaexTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            if (entries.isEmpty()) {
                WaexEmptyState(
                    title = stringResource(R.string.rootop_log_empty),
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    items(entries.asReversed()) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "${entry.commandId} — ${entry.outcome}",
                                style = WaexTheme.typography.bodyMd,
                                color = WaexTheme.colors.onSurface
                            )
                            Text(
                                text = "${entry.callerTag} · ${entry.durationMs}ms",
                                style = WaexTheme.typography.bodySm,
                                color = WaexTheme.colors.onSurfaceVariant
                            )
                            entry.outputPreview?.let { preview ->
                                Text(
                                    text = preview,
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
