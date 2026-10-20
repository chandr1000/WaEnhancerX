package com.waenhancer.ui.screens.root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrivilegedOperationsScreen() {
    val nav = LocalWaexNavController.current
    val context = LocalContext.current
    val auditEntries by RootAuditLog.entries.collectAsState()
    val specs = remember { RootCommandRegistry.all() }
    val categories = remember { specs.groupBy { it.category } }
    // Tick every 5 seconds so age text ("Last run 5s ago") recomposes as time passes.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            value = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            WaexTopBar(
                title = stringResource(R.string.rootop_screen_title),
                onBackClick = { nav.popBack() },
                actions = {
                    IconButton(onClick = { nav.navigateTo(Screen.RootAuditLog) }) {
                        Icon(WaexIcons.Security, contentDescription = stringResource(R.string.rootop_log_title))
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
                        lastRunSummary = lastRunSummaryFor(spec.id, auditEntries, nowMs)
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
    val scope = rememberCoroutineScope()
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
                scope.launch(Dispatchers.IO) {
                    val result = RegisteredRootRunner.run(
                        "debug.echo",
                        mapOf("text" to b64),
                        "debug"
                    )
                    val text = when (result) {
                        is RootResult.Completed -> "Completed: ${result.output}"
                        is RootResult.Success -> "Success (empty output)"
                        is RootResult.Blocked -> "Blocked: ${result.reason}"
                        is RootResult.Failed -> "Failed: ${result.kind}"
                    }
                    withContext(Dispatchers.Main) { resultText = text }
                }
            },
            enabled = RootGate.isEnabledState("debug.echo"),
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
    // Read toggle state reactively via the Compose-observable helper.
    val enabled = if (!spec.toggleable) true else RootGate.isEnabledState(spec.id)
    var checked by remember(spec.id, enabled) { mutableStateOf(enabled) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { nav.navigateTo(Screen.RootCommandDetail(spec.id)) }
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
            if (lastRunSummary.isNotEmpty()) {
                Text(
                    text = lastRunSummary,
                    style = WaexTheme.typography.bodySm,
                    color = WaexTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (!spec.toggleable) {
                // Required-entry text placed inside the weighted Column so the row's
                // title/description/lastRunSummary get enough width to render horizontally.
                Text(
                    text = stringResource(R.string.rootop_detail_required_entry),
                    style = WaexTheme.typography.bodySm,
                    color = WaexTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        if (spec.toggleable) {
            StitchSwitch(
                checked = checked,
                onCheckedChange = { value ->
                    checked = value
                    RootGate.setEnabled(context, spec.id, value)
                }
            )
        }
    }
}

@Composable
private fun RiskChip(risk: RiskLevel) {
    val (text, bgColor, textColor) = when (risk) {
        RiskLevel.INFO -> Triple(stringResource(R.string.rootop_risk_info), WaexTheme.colors.outlineVariant, WaexTheme.colors.onSurface)
        RiskLevel.LOW -> Triple(stringResource(R.string.rootop_risk_low), WaexTheme.colors.primary.copy(alpha = 0.2f), WaexTheme.colors.onSurface)
        RiskLevel.HIGH -> Triple(stringResource(R.string.rootop_risk_high), WaexTheme.colors.error.copy(alpha = 0.5f), WaexTheme.colors.error)
    }
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = WaexTheme.typography.bodySm,
            color = textColor,
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

@Composable
private fun lastRunSummaryFor(
    commandId: String,
    entries: List<RootAuditLog.Entry>,
    nowMs: Long
): String {
    if (entries.isEmpty()) {
        return stringResource(R.string.rootop_never_run)
    }
    val matching = entries.filter { it.commandId == commandId }
    if (matching.isEmpty()) {
        return stringResource(R.string.rootop_never_run)
    }
    val count = matching.size
    val mostRecent = matching.last()
    val ageMs = nowMs - mostRecent.epochMs
    val ageText = when {
        ageMs < 60_000 -> "${ageMs / 1000}s"
        ageMs < 3_600_000 -> "${ageMs / 60_000}min"
        else -> "${ageMs / 3_600_000}h"
    }
    val countText = androidx.compose.ui.res.pluralStringResource(R.plurals.rootop_run_count, count, count)
    val lastText = stringResource(R.string.rootop_last_run_summary, ageText)
    return "$countText · $lastText"
}
