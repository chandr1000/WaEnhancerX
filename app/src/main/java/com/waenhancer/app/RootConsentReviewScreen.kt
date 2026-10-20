package com.waenhancer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.waenhancer.root.RiskLevel
import com.waenhancer.ui.components.WaexSectionHeader
import com.waenhancer.ui.designsystem.WaexTheme

/**
 * Pre-flight consent review shown before the first `su` probe.
 * Discloses the registered-operation inventory, the 45-package scope, the
 * `pm install` usage, and the find-sweep reach in plain language, then
 * hands off to the actual superuser request.
 */
@Composable
fun RootConsentReviewScreen(onContinue: () -> Unit) {
    BackHandler { /* swallowing back: do nothing */ }
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing
    val specs = RootCommandRegistry.all().filter { it.id != "debug.echo" }
    val categories = specs.groupBy { it.category }
    val count = specs.size

    Column(
        modifier = Modifier
            .fillMaxSize()
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
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.rootop_consent_body, count),
            style = typography.bodyMd,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        categories.forEach { (category, categorySpecs) ->
            WaexSectionHeader(
                title = categoryTitleLocalized(category),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            categorySpecs.forEach { spec ->
                Text(
                    text = if (spec.titleRes != 0) stringResource(spec.titleRes) else spec.id,
                    style = typography.bodyMd,
                    color = colors.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                if (spec.descriptionRes != 0) {
                    Text(
                        text = stringResource(spec.descriptionRes),
                        style = typography.bodySm,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            )
        ) {
            Text(
                text = stringResource(R.string.rootop_consent_continue),
                style = typography.bodyLg,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.rootop_consent_boundary),
            style = typography.bodySm,
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
