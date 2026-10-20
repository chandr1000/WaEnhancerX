package com.waenhancer.ui.screens.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.root.RootCommandRegistry
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexNavController

@Composable
fun RootCommandDetailScreen(commandId: String) {
    val nav = LocalWaexNavController.current
    val spec = RootCommandRegistry.spec(commandId) ?: run {
        nav.popBack()
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
                title = stringResource(R.string.rootop_detail_command),
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = "Category: ${spec.category.name} · Risk: ${spec.risk.name} · Timeout: ${spec.timeoutMs}ms · Toggleable: ${spec.toggleable}",
                style = WaexTheme.typography.bodySm,
                color = WaexTheme.colors.onSurfaceVariant
            )
        }
    }
}
