package com.waenhancer.ui.screens.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.root.Category
import com.waenhancer.root.RiskLevel
import com.waenhancer.root.RootCommandRegistry
import com.waenhancer.root.RootCommandSpec
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun RootCommandDetailScreen(commandId: String) {
    val nav = LocalWaexNavController.current
    val spec = RootCommandRegistry.spec(commandId)
    if (spec == null) {
        Scaffold(
            topBar = {
                WaexTopBar(
                    title = commandId,
                    onBackClick = { nav.popBack() }
                )
            }
        ) { padding ->
            com.waenhancer.ui.components.WaexEmptyState(
                title = "This operation is no longer registered",
                description = "The command \"$commandId\" is not in the current registry.",
                icon = com.waenhancer.ui.designsystem.WaexIcons.Warning,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
        return
    }
    Scaffold(
        topBar = {
            WaexTopBar(
                title = if (spec.titleRes != 0) stringResource(spec.titleRes) else spec.id,
                onBackClick = { nav.popBack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (spec.descriptionRes != 0) {
                Text(
                    text = stringResource(spec.descriptionRes),
                    style = WaexTheme.typography.bodyLg,
                    color = WaexTheme.colors.onSurface
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            DetailMetaRow(spec)

            if (spec.detailRes != 0) {
                WaexSectionHeader(
                    title = stringResource(R.string.rootop_detail_when_it_runs),
                    modifier = Modifier.padding(top = 24.dp)
                )
                Text(
                    text = stringResource(spec.detailRes),
                    style = WaexTheme.typography.bodyMd,
                    color = WaexTheme.colors.onSurfaceVariant
                )
            }

            WaexSectionHeader(
                title = stringResource(R.string.rootop_detail_when_off),
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = fallbackDescriptionFor(spec),
                style = WaexTheme.typography.bodyMd,
                color = WaexTheme.colors.onSurfaceVariant
            )

            WaexSectionHeader(
                title = stringResource(R.string.rootop_detail_parameters),
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = parameterRulesFor(spec),
                style = WaexTheme.typography.bodyMd,
                color = WaexTheme.colors.onSurfaceVariant
            )

            WaexSectionHeader(
                title = stringResource(R.string.rootop_detail_command),
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = renderTemplateLiteral(spec),
                style = WaexTheme.typography.bodySm,
                color = WaexTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailMetaRow(spec: RootCommandSpec) {
    val category = when (spec.category) {
        Category.RESTART -> stringResource(R.string.rootop_category_restart)
        Category.UPDATE -> stringResource(R.string.rootop_category_update)
        Category.DIAGNOSTICS -> stringResource(R.string.rootop_category_diagnostics)
        Category.HMA_REPAIR -> stringResource(R.string.rootop_category_hma)
    }
    val risk = when (spec.risk) {
        RiskLevel.INFO -> stringResource(R.string.rootop_risk_info)
        RiskLevel.LOW -> stringResource(R.string.rootop_risk_low)
        RiskLevel.HIGH -> stringResource(R.string.rootop_risk_high)
    }
    val timeoutText = when {
        spec.timeoutMs >= 60_000 -> "${spec.timeoutMs / 60_000}min"
        spec.timeoutMs >= 1_000 -> "${spec.timeoutMs / 1_000}s"
        else -> "${spec.timeoutMs}ms"
    }
    Text(
        text = "$category · $risk · $timeoutText",
        style = WaexTheme.typography.bodySm,
        color = WaexTheme.colors.onSurfaceVariant,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun fallbackDescriptionFor(spec: RootCommandSpec): String {
    if (!spec.toggleable) return stringResource(R.string.rootop_detail_fallback_required)
    return when (spec.category) {
        Category.RESTART -> stringResource(R.string.rootop_detail_fallback_restart)
        Category.UPDATE -> stringResource(R.string.rootop_detail_fallback_update)
        Category.DIAGNOSTICS -> stringResource(R.string.rootop_detail_fallback_diagnostics)
        Category.HMA_REPAIR -> stringResource(R.string.rootop_detail_fallback_hma)
    }
}

@Composable
private fun parameterRulesFor(spec: RootCommandSpec): String {
    if (spec.params.isEmpty()) return stringResource(R.string.rootop_detail_no_params)
    return spec.params.joinToString("\n") { p ->
        val kindDesc = when (p.kind) {
            com.waenhancer.root.ParamKind.PACKAGE_NAME ->
                stringResource(R.string.rootop_detail_param_package)
            com.waenhancer.root.ParamKind.PATH_STRICT ->
                stringResource(R.string.rootop_detail_param_path_strict)
            com.waenhancer.root.ParamKind.BASE64_TEXT ->
                stringResource(R.string.rootop_detail_param_base64)
            com.waenhancer.root.ParamKind.FILE_PATH ->
                stringResource(R.string.rootop_detail_param_file_path)
            com.waenhancer.root.ParamKind.DERIVED_OUTPUT ->
                stringResource(R.string.rootop_detail_param_derived)
            com.waenhancer.root.ParamKind.CONSTANT -> "baked into the template, not caller-supplied"
        }
        "${p.name}: $kindDesc"
    }
}

private fun renderTemplateLiteral(spec: RootCommandSpec): String {
    val sampleParams = spec.params.associate { p ->
        p.name to when (p.kind) {
            com.waenhancer.root.ParamKind.PACKAGE_NAME -> "<package>"
            com.waenhancer.root.ParamKind.PATH_STRICT -> "<path>"
            com.waenhancer.root.ParamKind.BASE64_TEXT -> "<base64>"
            com.waenhancer.root.ParamKind.FILE_PATH -> "<file>"
            com.waenhancer.root.ParamKind.DERIVED_OUTPUT -> "<derived>"
            com.waenhancer.root.ParamKind.CONSTANT -> ""
        }
    }
    return try {
        spec.template(sampleParams)
    } catch (_: Exception) {
        "<template requires valid params>"
    }
}
