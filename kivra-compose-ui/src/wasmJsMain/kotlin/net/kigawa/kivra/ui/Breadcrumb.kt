package net.kigawa.kivra.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * パンくず「作業 › order › <id> › cycles」。現在地(route が null の要素)は文字のみ、他は押すと遷移する。
 */
@Composable
fun Breadcrumb(crumbs: List<Crumb>, onNavigate: (Route) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Text(" › ", style = MaterialTheme.typography.bodyLarge)
            val target = crumb.route
            if (target == null) {
                Text(crumb.label, style = MaterialTheme.typography.titleMedium)
            } else {
                TextButton(onClick = { onNavigate(target) }) {
                    Text(crumb.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
