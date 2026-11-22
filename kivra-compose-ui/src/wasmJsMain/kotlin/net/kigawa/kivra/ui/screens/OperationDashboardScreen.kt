package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.NodeKind
import net.kigawa.kivra.ui.NodeRef
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.SelectableText
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiOperation

@Composable
fun OperationDashboardScreen(operationId: String, parent: NodeRef? = null, onNavigate: (Route) -> Unit) {
    val loaded = rememberLoad<ApiOperation>(operationId) { ApiClient.getOperation(operationId) }
    val children = rememberLoad<List<ApiOperation>>(operationId) { ApiClient.getOperations(operationId) }

    DetailPage {
        SelectableText("Operation ダッシュボード", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LoadedView(loaded) { operation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableText(operation.name, style = MaterialTheme.typography.titleLarge)
                    StatusBadgeInfo(operation.status)
                    parent?.let { OpDetailRow("親 ${it.kind.label}", it.id) }
                    OpDetailRow("ID", operation.id)
                    OpDetailRow("名前", operation.name)
                    OpDetailRow("説明", operation.description)
                    OpDetailRow("作成日時", operation.createdAt)
                    OpDetailRow("更新日時", operation.updatedAt)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SelectableText("子 Operation", style = MaterialTheme.typography.titleLarge)
        LoadedList(children, emptyText = "子 Operation がありません。") { operations ->
            operations.forEach { operation ->
                OperationCard(operation, NodeRef(operationId, NodeKind.OPERATION), onNavigate)
            }
        }
    }
}

private data class BadgeInfo(val background: Color, val foreground: Color)

/** 状態名も表示し、色だけに依存せず判別できるバッジ。 */
@Composable
internal fun StatusBadgeInfo(status: String) {
    val badge = when (status) {
        "running" -> BadgeInfo(Color(0xFFE3F2FD), Color(0xFF1565C0))
        "completed" -> BadgeInfo(Color(0xFFE8F5E9), Color(0xFF2E7D32))
        "failed" -> BadgeInfo(Color(0xFFFFEBEE), Color(0xFFC62828))
        else -> BadgeInfo(Color(0xFFEEEEEE), Color(0xFF424242))
    }
    Surface(color = badge.background, contentColor = badge.foreground, shape = MaterialTheme.shapes.small) {
        SelectableText(status, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun OpDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        SelectableText(label, modifier = Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectableText(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
