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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexPreferenceItem
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
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val auditEntries by RootAuditLog.entries.collectAsState()
    val specs = remember { RootCommandRegistry.all() }
    val categories = remember { specs.groupBy { it.category } }
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
                        Icon(
                            WaexIcons.Security,
                            contentDescription = stringResource(R.string.rootop_log_title),
                            tint = colors.onSurface
                        )
                    }
                }
            )
        },
        containerColor = colors.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.pageMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.stackLg)
        ) {
            item {
                Text(
                    text = stringResource(R.string.rootop_screen_summary),
                    style = WaexTheme.typography.bodyMd,
                    color = colors.onSurfaceVariant
                )
            }
            for ((category, categorySpecs) in categories) {
                item(key = "header_${category.name}") {
                    WaexSectionHeader(title = categoryTitle(category))
                }
                item(key = "card_${category.name}") {
                    WaexCard {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            categorySpecs.forEachIndexed { index, spec ->
                                RootOperationItem(
                                    spec = spec,
                                    lastRunSummary = lastRunSummaryFor(spec.id, auditEntries, nowMs),
                                    showDivider = index < categorySpecs.lastIndex
                                )
                            }
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                item(key = "debug_section") { DebugEchoPanel() }
            }
        }
    }
}

@Composable
private fun RootOperationItem(
    spec: RootCommandSpec,
    lastRunSummary: String,
    showDivider: Boolean
) {
    val context = LocalContext.current
    val nav = LocalWaexNavController.current
    val enabled = if (!spec.toggleable) true else RootGate.isEnabledState(spec.id)
    var checked by remember(spec.id, enabled) { mutableStateOf(enabled) }
    val title = if (spec.titleRes != 0) stringResource(spec.titleRes) else spec.id
    val description = buildString {
        if (spec.descriptionRes != 0) append(stringResource(spec.descriptionRes))
        if (lastRunSummary.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(lastRunSummary)
        }
    }

    if (spec.toggleable) {
        WaexPreferenceItem(
            title = title,
            description = description.ifBlank { null },
            onClick = { nav.navigateTo(Screen.RootCommandDetail(spec.id)) },
            showDivider = showDivider,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    riskChip(spec.risk)
                    Spacer(modifier = Modifier.width(WaexTheme.spacing.stackSm))
                    StitchSwitch(
                        checked = checked,
                        onCheckedChange = { value ->
                            checked = value
                            RootGate.setEnabled(context, spec.id, value)
                        }
                    )
                }
            }
        )
    } else {
        WaexPreferenceItem(
            title = title,
            description = stringResource(R.string.rootop_detail_required_entry),
            onClick = { nav.navigateTo(Screen.RootCommandDetail(spec.id)) },
            showDivider = showDivider,
            trailing = { riskChip(spec.risk) }
        )
    }
}

@Composable
private fun riskChip(risk: RiskLevel) {
    val colors = WaexTheme.colors
    val (text, color) = when (risk) {
        RiskLevel.INFO -> stringResource(R.string.rootop_risk_info) to colors.onSurfaceVariant
        RiskLevel.LOW -> stringResource(R.string.rootop_risk_low) to colors.primary
        RiskLevel.HIGH -> stringResource(R.string.rootop_risk_high) to colors.error
    }
    Text(
        text = text,
        style = WaexTheme.typography.labelSm,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun DebugEchoPanel() {
    var input by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val colors = WaexTheme.colors
    val spacing = WaexTheme.spacing
    val radius = WaexTheme.radius
    Column {
        WaexSectionHeader(title = stringResource(R.string.rootop_debug_section_title))
        Spacer(modifier = Modifier.height(spacing.stackSm))
        WaexCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.rootop_debug_input_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(spacing.stackMd))
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
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.rootop_debug_run))
                }
                if (resultText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(spacing.stackSm))
                    Text(
                        text = stringResource(R.string.rootop_debug_result) + ": " + resultText,
                        style = WaexTheme.typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
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
