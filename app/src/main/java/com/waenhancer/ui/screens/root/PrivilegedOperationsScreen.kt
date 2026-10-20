package com.waenhancer.ui.screens.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.BuildConfig
import com.waenhancer.app.R
import com.waenhancer.root.Category
import com.waenhancer.root.RegisteredRootRunner
import com.waenhancer.root.RootAuditLog
import com.waenhancer.root.RootCommandRegistry
import com.waenhancer.root.RootCommandSpec
import com.waenhancer.root.RootGate
import com.waenhancer.root.RootResult
import com.waenhancer.root.RiskLevel
import com.waenhancer.ui.components.StitchSwitch
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController
import com.waenhancer.ui.navigation.Screen

@Composable
fun PrivilegedOperationsScreen() {
    val nav = LocalWaexNavController.current
    val context = LocalContext.current
    val auditEntries by RootAuditLog.entries.collectAsState()
    val specs = remember { RootCommandRegistry.all() }
    val categories = remember { specs.groupBy { it.category } }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.rootop_screen_title),
                onBackClick = { nav.popBack() },
                actions = {
                    IconButton(onClick = { nav.navigateTo(Screen.RootAuditLog) }) {
                        Icon(WaexIcons.Search, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Text(
                    text = stringResource(R.string.rootop_screen_summary),
                    style = WaexTheme.typography.bodyMd,
                    color = WaexTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            for ((category, categorySpecs) in categories) {
                item(key = "header_${category.name}") {
                    WaexSectionHeader(
                        title = categoryTitle(category),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(categorySpecs, key = { it.id }) { spec ->
                    RootOperationRow(
                        spec = spec,
                        lastRunSummary = lastRunSummaryFor(spec.id, auditEntries.size)
                    )
                }
            }
            if (BuildConfig.DEBUG) {
                item(key = "debug_section") { DebugEchoPanel() }
            }
        }
    }
}

@Composable
private fun DebugEchoPanel() {
    var input by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        WaexSectionHeader(title = stringResource(R.string.rootop_debug_section_title))
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.rootop_debug_input_hint)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val b64 = android.util.Base64.encodeToString(
                    input.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                val result = RegisteredRootRunner.run(
                    "debug.echo",
                    mapOf("text" to b64),
                    "debug"
                )
                resultText = when (result) {
                    is RootResult.Completed -> "Completed: ${result.output}"
                    is RootResult.Success -> "Success (empty output)"
                    is RootResult.Blocked -> "Blocked: ${result.reason}"
                    is RootResult.Failed -> "Failed: ${result.kind}"
                }
            },
            enabled = RootGate.isEnabled("debug.echo"),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.rootop_debug_run))
        }
        if (resultText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.rootop_debug_result) + ": " + resultText,
                style = WaexTheme.typography.bodySm,
                color = WaexTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RootOperationRow(spec: RootCommandSpec, lastRunSummary: String) {
    val context = LocalContext.current
    val nav = LocalWaexNavController.current
    val enabled = remember(spec.id) {
        if (!spec.toggleable) true else RootGate.isEnabled(spec.id)
    }
    var checked by remember(spec.id) { mutableStateOf(enabled) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (spec.titleRes != 0) stringResource(spec.titleRes) else spec.id,
                    style = WaexTheme.typography.bodyLg,
                    color = WaexTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.size(8.dp))
                RiskChip(risk = spec.risk)
            }
            if (spec.descriptionRes != 0) {
                Text(
                    text = stringResource(spec.descriptionRes),
                    style = WaexTheme.typography.bodyMd,
                    color = WaexTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = lastRunSummary,
                style = WaexTheme.typography.bodySm,
                color = WaexTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (spec.toggleable) {
            StitchSwitch(
                checked = checked,
                onCheckedChange = { value ->
                    checked = value
                    RootGate.setEnabled(context, spec.id, value)
                }
            )
        } else {
            Text(
                text = stringResource(R.string.rootop_detail_required_entry),
                style = WaexTheme.typography.bodySm,
                color = WaexTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun RiskChip(risk: RiskLevel) {
    val (text, color) = when (risk) {
        RiskLevel.INFO -> stringResource(R.string.rootop_risk_info) to WaexTheme.colors.outlineVariant
        RiskLevel.LOW -> stringResource(R.string.rootop_risk_low) to WaexTheme.colors.primary.copy(alpha = 0.2f)
        RiskLevel.HIGH -> stringResource(R.string.rootop_risk_high) to WaexTheme.colors.error.copy(alpha = 0.2f)
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = WaexTheme.typography.bodySm,
            color = WaexTheme.colors.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun categoryTitle(category: Category): String = when (category) {
    Category.RESTART -> stringResource(R.string.rootop_category_restart)
    Category.UPDATE -> stringResource(R.string.rootop_category_update)
    Category.DIAGNOSTICS -> stringResource(R.string.rootop_category_diagnostics)
    Category.HMA_REPAIR -> stringResource(R.string.rootop_category_hma)
}

private fun lastRunSummaryFor(commandId: String, totalEntries: Int): String {
    if (totalEntries == 0) return ""
    // Lightweight: just count entries for this command in the current buffer.
    return "• $commandId"
}
