package net.kigawa.kivra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * パンくず「作業 › order › <id> › cycles」。現在地(route が null の要素)は文字のみ、他は押すと遷移する。
 * 幅が足りないときは折り返す(横スクロールにすると、末尾の id やタイトルが隠れて読めなかった)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Breadcrumb(crumbs: List<Crumb>, onNavigate: (Route) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) SelectableText(" › ", style = MaterialTheme.typography.bodyLarge)
            val target = crumb.route
            if (target == null) {
                SelectableText(crumb.label, style = MaterialTheme.typography.titleMedium)
            } else {
                TextButton(onClick = { onNavigate(target) }) {
                    SelectableText(crumb.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
