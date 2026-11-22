package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import net.kigawa.kivra.ui.api.ApiClient

/** Uses project members, never the global assignee directory. Text renders names without interpreting markup. */
@Composable
internal fun OrderAssigneePicker(projectId: String, selected: String?, enabled: Boolean, onSelected: (String?) -> Unit) {
    val loaded = rememberLoad(projectId) { ApiClient.getProjectMembers(projectId) }
    var expanded by remember { mutableStateOf(false) }
    Column {
        val eligible = loaded.value.orEmpty().filter { it.assignable }
        loaded.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
                Text("担当: " + (eligible.firstOrNull { it.assigneeId == selected }?.displayName ?: selected ?: "未担当"))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("未担当") }, onClick = { onSelected(null); expanded = false })
                eligible.forEach { member ->
                    DropdownMenuItem(text = { Text(member.displayName) }, onClick = { onSelected(member.assigneeId); expanded = false })
                }
            }
        }
    }
}
