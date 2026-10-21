package com.waenhancer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.root.Category
import com.waenhancer.root.RootCommandRegistry
import com.waenhancer.ui.components.WaexCard
import com.waenhancer.ui.components.WaexPreferenceItem
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.designsystem.WaexTheme

@Composable
fun RootConsentReviewScreen(onContinue: () -> Unit) {
    BackHandler { }
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing
    val radius = WaexTheme.radius
    val specs = RootCommandRegistry.all().filter { it.id != "debug.echo" }
    val categories = specs.groupBy { it.category }
    val count = specs.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageMargin, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.rootop_consent_title),
            style = typography.displayLgMobile,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.stackLg))
        Text(
            text = stringResource(R.string.rootop_consent_body, count),
            style = typography.bodyMd,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.stackLg))

        categories.forEach { (category, categorySpecs) ->
            WaexSectionHeader(title = categoryTitleLocalized(category))
            Spacer(modifier = Modifier.height(spacing.stackSm))
            WaexCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    categorySpecs.forEachIndexed { index, spec ->
                        WaexPreferenceItem(
                            title = if (spec.titleRes != 0) stringResource(spec.titleRes) else spec.id,
                            description = if (spec.descriptionRes != 0) stringResource(spec.descriptionRes) else null,
                            showDivider = index < categorySpecs.lastIndex
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.stackLg))
        }

        WaexCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                WaexPreferenceItem(
                    title = stringResource(R.string.rootop_consent_scope_title),
                    description = stringResource(R.string.rootop_consent_scope_restart),
                    showDivider = true
                )
                WaexPreferenceItem(
                    title = "",
                    description = stringResource(R.string.rootop_consent_scope_install),
                    showDivider = true
                )
                WaexPreferenceItem(
                    title = "",
                    description = stringResource(R.string.rootop_consent_scope_find),
                    showDivider = false
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.stackLg))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            shape = radius.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            )
        ) {
            Text(
                text = stringResource(R.string.rootop_consent_continue),
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(spacing.stackMd))
        Text(
            text = stringResource(R.string.rootop_consent_boundary),
            style = typography.labelSm,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun categoryTitleLocalized(category: Category): String = when (category) {
    Category.RESTART -> stringResource(R.string.rootop_category_restart)
    Category.UPDATE -> stringResource(R.string.rootop_category_update)
    Category.DIAGNOSTICS -> stringResource(R.string.rootop_category_diagnostics)
    Category.HMA_REPAIR -> stringResource(R.string.rootop_category_hma)
}
