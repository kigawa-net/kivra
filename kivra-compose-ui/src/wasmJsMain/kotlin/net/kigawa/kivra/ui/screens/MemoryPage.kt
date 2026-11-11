package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.MEMORY_CONDITION_LIMIT
import net.kigawa.kivra.ui.MEMORY_EXPLAIN_ONE
import net.kigawa.kivra.ui.MEMORY_KIND_LABELS
import net.kigawa.kivra.ui.MEMORY_MSG_DELETED
import net.kigawa.kivra.ui.MEMORY_MSG_NEED_FILTER
import net.kigawa.kivra.ui.MEMORY_MSG_NETWORK
import net.kigawa.kivra.ui.MEMORY_PAGE_SIZE
import net.kigawa.kivra.ui.MEMORY_REASON_LABEL
import net.kigawa.kivra.ui.MEMORY_REASON_MAX
import net.kigawa.kivra.ui.MEMORY_TIER_LABELS
import net.kigawa.kivra.ui.MemoryDryRun
import net.kigawa.kivra.ui.MemoryListFilter
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiMemory
import net.kigawa.kivra.ui.api.ApiMemoryPage
import net.kigawa.kivra.ui.memoryBodyText
import net.kigawa.kivra.ui.memoryCodeText
import net.kigawa.kivra.ui.memoryConditionDeleteEnabled
import net.kigawa.kivra.ui.memoryCountsText
import net.kigawa.kivra.ui.memoryDryRunFailureText
import net.kigawa.kivra.ui.memoryDryRunText
import net.kigawa.kivra.ui.memoryExplainCondition
import net.kigawa.kivra.ui.memoryFailureText
import net.kigawa.kivra.ui.memoryFilterOf
import net.kigawa.kivra.ui.memoryHasNext
import net.kigawa.kivra.ui.memoryHasPrev
import net.kigawa.kivra.ui.memoryKindLabel
import net.kigawa.kivra.ui.memoryPageText
import net.kigawa.kivra.ui.memoryTierLabel
import net.kigawa.kivra.ui.memoryTotalText

/** 削除の確認の対象。[recordId] があれば個別の削除、なければ [filter] の条件での削除([matched] 件)。 */
private class MemoryDeleteTarget(val recordId: String?, val filter: MemoryListFilter, val matched: Int)

/**
 * 記憶の画面(運用者向け、ADR 0016 P2)。種類と信頼で絞り込み、20 件ずつ読む。個別の削除は理由を入れてから。
 * 条件での削除は、件数の確認(dry run)をしてから、同じ条件で 1 件以上のときだけ出せる(二段階)。
 * 本文はサーバーが返すものだけ出す(未検証の文章の本文は返らない)。
 *
 * [projectId] は選んだプロジェクトで絞る(旧 UI の PROJECT_SCOPED_GET と同じ)。
 */
@Composable
fun MemoryScreen(projectId: String) {
    val perms = LocalCallerPermissions.current
    val scope = rememberCoroutineScope()
    val canRead = perms.can(GatedOperation.READ_MEMORY)
    val canDelete = perms.can(GatedOperation.DELETE_MEMORY)

    // 絞り込みの欄の選択(まだ適用していない値)と、適用中の絞り込み。
    var pickKind by remember { mutableStateOf<String?>(null) }
    var pickTier by remember { mutableStateOf<String?>(null) }
    var applied by remember { mutableStateOf(MemoryListFilter()) }
    var offset by remember { mutableStateOf(0) }
    var reloadKey by remember { mutableStateOf(0) }

    var dryRun by remember { mutableStateOf<MemoryDryRun?>(null) }
    var dryText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    var target by remember { mutableStateOf<MemoryDeleteTarget?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val loaded = rememberLoad(Triple(projectId, applied, offset), reloadKey) {
        // 見られない利用者には読まない(画面は権限の文言を出す)。
        if (canRead) ApiClient.getMemoryPage(applied, offset, projectId) else ApiMemoryPage(emptyList(), 0, 0, 0)
    }

    // 絞り込みを適用する。件数の確認は絞り込みと結び付いているので、適用で消す。
    val onApply: () -> Unit = {
        if (!busy) {
            applied = memoryFilterOf(pickKind, pickTier)
            offset = 0
            dryRun = null
            dryText = ""
        }
    }

    // 条件の件数を確かめる(dry run)。条件が無ければ送らない。
    val onDryRun: () -> Unit = {
        val filter = applied
        if (!busy) {
            if (!filter.hasCondition) {
                dryText = MEMORY_MSG_NEED_FILTER
            } else {
                busy = true
                scope.launch {
                    try {
                        val r = ApiClient.deleteMemoriesByCondition(filter, dryRun = true, reason = null)
                        dryRun = MemoryDryRun(filter.key, r.matched)
                        dryText = memoryDryRunText(r.matched)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        dryRun = null
                        dryText = memoryDryRunFailureText(e.status)
                    } catch (e: Exception) {
                        dryRun = null
                        dryText = MEMORY_MSG_NETWORK
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    // 確認の理由を受けて削除する。失敗なら確認を開いたままにする(理由を失わないため)。
    val onSubmitDelete: (String) -> Unit = { reason ->
        val t = target
        if (t != null && !busy) {
            busy = true
            dialogError = null
            scope.launch {
                try {
                    if (t.recordId != null) {
                        ApiClient.deleteMemory(t.recordId, reason)
                    } else {
                        ApiClient.deleteMemoriesByCondition(t.filter, dryRun = false, reason = reason)
                    }
                    target = null
                    dryRun = null
                    dryText = ""
                    notice = ActionNotice(MEMORY_MSG_DELETED, true)
                    reloadKey++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    dialogError = memoryFailureText(e.status)
                    // 404 は既に消えている。読み直して、消えた記録を一覧から外す(旧 UI と同じ)。
                    if (e.status == 404) reloadKey++
                } catch (e: Exception) {
                    dialogError = MEMORY_MSG_NETWORK
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SelectableText("記憶", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))

        if (!canRead) {
            SelectableText("この画面を見る権限がありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            notice?.let { n ->
                SelectableText(n.text, color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 絞り込み(種類・信頼)。適用するまで一覧は変わらない。
            val kindOptions: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "すべての種類") +
                MEMORY_KIND_LABELS.map { (k, v) -> k to v }
            val tierOptions: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "すべての信頼") +
                MEMORY_TIER_LABELS.map { (k, v) -> k to v }
            Row(verticalAlignment = Alignment.Bottom) {
                OptionPicker(
                    label = "種類",
                    options = kindOptions,
                    selected = pickKind,
                    onSelect = { pickKind = it },
                    enabled = !busy,
                )
                Spacer(modifier = Modifier.width(12.dp))
                OptionPicker(
                    label = "信頼",
                    options = tierOptions,
                    selected = pickTier,
                    onSelect = { pickTier = it },
                    enabled = !busy,
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onApply, enabled = !busy) { SelectableText("絞り込む") }
            }
            Spacer(modifier = Modifier.height(12.dp))

            LoadedView(loaded) { page ->
                SelectableText(memoryTotalText(page.total), style = MaterialTheme.typography.bodySmall)
                SelectableText(
                    memoryCountsText(page.tombstones, page.deletionAudit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (page.items.isEmpty()) {
                    SelectableText("該当する記録はありません。")
                } else {
                    page.items.forEach { mem ->
                        MemoryRow(
                            mem = mem,
                            canDelete = canDelete,
                            enabled = !busy,
                            onDelete = {
                                dialogError = null
                                target = MemoryDeleteTarget(recordId = mem.recordId, filter = applied, matched = 0)
                            },
                        )
                    }
                }
                SelectableText(
                    memoryPageText(offset, page.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { offset = (offset - MEMORY_PAGE_SIZE).coerceAtLeast(0) },
                        enabled = !busy && memoryHasPrev(offset),
                    ) { SelectableText("前の $MEMORY_PAGE_SIZE 件") }
                    OutlinedButton(
                        onClick = { offset += MEMORY_PAGE_SIZE },
                        enabled = !busy && memoryHasNext(offset, page.items.size, page.total),
                    ) { SelectableText("次の $MEMORY_PAGE_SIZE 件") }
                }
            }

            if (canDelete) {
                Spacer(modifier = Modifier.height(24.dp))
                SelectableText("条件での削除", style = MaterialTheme.typography.titleMedium)
                SelectableText(
                    "種類か信頼を選んで「絞り込む」を押し、件数を確認してから削除します。削除は、確認した条件と同じ条件のときだけできます（1 回の上限は $MEMORY_CONDITION_LIMIT 件）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDryRun, enabled = !busy) { SelectableText("該当の件数を確認") }
                    Button(
                        onClick = {
                            dialogError = null
                            target = MemoryDeleteTarget(recordId = null, filter = applied, matched = dryRun?.matched ?: 0)
                        },
                        enabled = !busy && memoryConditionDeleteEnabled(dryRun, applied),
                    ) { SelectableText("この条件で削除") }
                }
                if (dryText.isNotEmpty()) {
                    SelectableText(dryText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // 確認(理由の入力と送信)。個別と条件で文言を分ける。
    target?.let { t ->
        ReasonDialog(
            title = if (t.recordId != null) "記録の削除" else "条件での削除",
            message = if (t.recordId != null) MEMORY_EXPLAIN_ONE else memoryExplainCondition(t.matched),
            submitLabel = "削除する",
            reasonLabel = MEMORY_REASON_LABEL,
            busy = busy,
            errorText = dialogError,
            onSubmit = { reason -> if (reason != null) onSubmitDelete(reason) },
            onDismiss = {
                if (!busy) {
                    target = null
                    dialogError = null
                }
            },
            maxCodePoints = MEMORY_REASON_MAX,
        )
    }
}

/** 記憶の 1 件。種類・信頼・作成時刻・件数・理由コード・本文(あれば)。削除は [canDelete] のときだけ。 */
@Composable
private fun MemoryRow(mem: ApiMemory, canDelete: Boolean, enabled: Boolean, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectableText(memoryKindLabel(mem.kind), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                SelectableText(
                    "[${memoryTierLabel(mem.tier)}]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val meta = buildList {
                add("作成: ${localDateTimeText(mem.createdAt)}")
                mem.countValue?.let { add("件数: $it") }
                mem.code?.let { add("理由コード: ${memoryCodeText(it)}") }
                add("出所: ${mem.source}")
            }
            SelectableText(
                meta.joinToString(" | "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            memoryBodyText(mem.body, mem.bodyWithheld)?.let { text ->
                Spacer(modifier = Modifier.height(4.dp))
                SelectableText(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (canDelete) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = onDelete, enabled = enabled) { SelectableText("この記録を削除") }
            }
        }
    }
}
