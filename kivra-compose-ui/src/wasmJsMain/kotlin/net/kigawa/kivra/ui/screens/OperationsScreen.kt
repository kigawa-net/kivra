package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.NodeId
import net.kigawa.kivra.ui.NodeKind
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.SelectableText
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiOperation
import net.kigawa.kivra.ui.asNode
import net.kigawa.kivra.ui.openRoute
import net.kigawa.kivra.ui.operationsRouteOf

/** 親を選んで、その直下の Operation を表示する。 */
@Composable
fun OperationsScreen(onNavigate: (Route) -> Unit, parent: NodeId? = null) {
    val loaded = rememberLoad<List<ApiOperation>>(parent) { ApiClient.getOperations(parent?.id) }
    val parents = rememberLoad<List<ApiOperation>>(Unit) { ApiClient.getOperations() }
    var expanded by remember(parent) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SelectableText("Operation", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text("親: ${parent?.id ?: "なし（全体）"}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("なし（全体）") }, onClick = {
                    expanded = false
                    onNavigate(operationsRouteOf(null))
                })
                (parents.value.orEmpty() + loaded.value.orEmpty()).distinctBy { it.id }.forEach { operation ->
                    DropdownMenuItem(text = { Text(operation.name) }, onClick = {
                        expanded = false
                        onNavigate(operationsRouteOf(NodeId(operation.id, NodeKind.OPERATION)))
                    })
                }
            }
        }
        parents.error?.let { SelectableText("親 Operation 一覧: $it", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        LoadedList(loaded, emptyText = "Operation がありません。") { operations ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(operations, key = { it.id }) { operation ->
                    Card(modifier = Modifier.fillMaxWidth().clickable {
                        onNavigate(operation.asNode(parent).openRoute())
                    }) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectableText(operation.name, style = MaterialTheme.typography.titleMedium)
                            StatusBadgeInfo(operation.status)
                            SelectableText(operation.description, style = MaterialTheme.typography.bodyMedium)
                            SelectableText("更新日時: ${operation.updatedAt}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
