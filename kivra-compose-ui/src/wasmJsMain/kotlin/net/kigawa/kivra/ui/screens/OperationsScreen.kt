package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.NodeRef
import net.kigawa.kivra.ui.NavDestination
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.SelectableText
import net.kigawa.kivra.ui.asNode
import net.kigawa.kivra.ui.operationRoute
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiOperation

/** 親の選択は一覧の絞り込み。API DTO に親情報を仮定しない。 */
@Composable
fun OperationsScreen(onNavigate: (Route) -> Unit, parent: NodeRef? = null) {
    val loaded = rememberLoad<List<ApiOperation>>(parent) { ApiClient.getOperations(parent?.id) }
    var expanded by remember(parent) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SelectableText("Operation", style = MaterialTheme.typography.headlineSmall)
        Box {
            TextButton(onClick = { expanded = true }) {
                SelectableText(if (parent == null) "親なし" else "親 ${parent.kind.label}: ${parent.id}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { SelectableText("親なし（全体）") }, onClick = {
                    expanded = false
                    onNavigate(Route.Page(NavDestination.Operations))
                })
                loaded.value.orEmpty().forEach { operation ->
                    DropdownMenuItem(text = { SelectableText(operation.name) }, onClick = {
                        expanded = false
                        onNavigate(operation.asNode(parent).operationsRoute())
                    })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LoadedList(loaded, emptyText = "Operation がありません。") { operations ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(operations, key = { it.id }) { operation ->
                    OperationCard(operation, parent, onNavigate)
                }
            }
        }
    }
}

@Composable
internal fun OperationCard(operation: ApiOperation, parent: NodeRef?, onNavigate: (Route) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable {
        onNavigate(operationRoute(operation.id, parent))
    }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectableText(operation.name, style = MaterialTheme.typography.titleMedium)
            StatusBadgeInfo(operation.status)
            SelectableText(operation.description, style = MaterialTheme.typography.bodyMedium)
            SelectableText("更新日時: ${operation.updatedAt}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
