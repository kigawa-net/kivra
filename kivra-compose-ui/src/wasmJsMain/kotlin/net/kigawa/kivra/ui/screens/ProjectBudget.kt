package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import net.kigawa.kivra.ui.BUDGET_CLEAR_DONE_MESSAGE
import net.kigawa.kivra.ui.BUDGET_CLEAR_EXPLAIN
import net.kigawa.kivra.ui.BUDGET_CLEAR_SUBMIT_LABEL
import net.kigawa.kivra.ui.BUDGET_CLEAR_TITLE
import net.kigawa.kivra.ui.BUDGET_INVALID_MESSAGE
import net.kigawa.kivra.ui.BUDGET_LIMIT_INPUT_MAX
import net.kigawa.kivra.ui.BUDGET_RATIO_DEFAULT
import net.kigawa.kivra.ui.BUDGET_RATIO_INPUT_MAX
import net.kigawa.kivra.ui.BUDGET_SECTION_NOTE
import net.kigawa.kivra.ui.BUDGET_SET_DONE_MESSAGE
import net.kigawa.kivra.ui.BUDGET_SET_SUBMIT_LABEL
import net.kigawa.kivra.ui.BUDGET_SET_TITLE
import net.kigawa.kivra.ui.budgetFailText
import net.kigawa.kivra.ui.budgetPeriodText
import net.kigawa.kivra.ui.budgetSetExplain
import net.kigawa.kivra.ui.budgetWarningOf
import net.kigawa.kivra.ui.budgetYenText
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiProjectBudget
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.parseBudgetLimit
import net.kigawa.kivra.ui.parseBudgetRatio
import net.kigawa.kivra.ui.submitBlocked

/** 確認を経て送る操作。設定は検査済みの値、解除は値を持たない。 */
private sealed interface BudgetChange {
    data class Set(val limit: Long, val ratio: Double) : BudgetChange
    object Clear : BudgetChange
}

/**
 * 運用者だけの「今月の予算」(旧 UI の project-budget、ADR 0022 PR-E)。運用者の欄の中に出す(dashboard の budget が null なら呼ばない)。
 *
 * 表示は [budget] の値。上限と警告の基準の入力欄は、プロジェクトごとに 1 回だけ保存値で埋める(読み直しで入力中の値を上書きしない)。
 * 設定と解除は確認ダイアログの後だけ送る。成功したら、入力欄を保存値で埋め直すために [onChanged] でダッシュボードを読み直させる。
 * 失敗は [budgetFailText] の固定文言だけを出し、ダイアログは開いたままにする。権限と判定はサーバーが行う。
 */
@Composable
internal fun ProjectBudgetSection(budget: ApiProjectBudget, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val expired = ApiSession.expired
    var busy by remember { mutableStateOf(false) }

    // 入力欄。保存値で埋めたプロジェクトの id。保存の成功の後は、読み直した値が来たら埋め直す([reseedFrom] が来た値と違うとき)。
    var seededFor by remember { mutableStateOf<String?>(null) }
    var reseedFrom by remember { mutableStateOf<ApiProjectBudget?>(null) }
    var limitInput by remember { mutableStateOf("") }
    var ratioInput by remember { mutableStateOf(BUDGET_RATIO_DEFAULT) }
    LaunchedEffect(budget) {
        val reseed = reseedFrom
        if (seededFor != budget.projectId || (reseed != null && budget != reseed)) {
            limitInput = budget.monthlyLimitJpy ?: ""
            ratioInput = budget.softRatio ?: BUDGET_RATIO_DEFAULT
            seededFor = budget.projectId
            reseedFrom = null
        }
    }

    // 状態行(設定の検査の失敗、成功、送信の失敗の固定文言)と、確認ダイアログの対象と失敗の文言。
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    var pending by remember { mutableStateOf<BudgetChange?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    fun openSet() {
        if (busy) return
        val limit = parseBudgetLimit(limitInput)
        val ratio = parseBudgetRatio(ratioInput)
        if (limit == null || ratio == null) {
            notice = ActionNotice(BUDGET_INVALID_MESSAGE, ok = false)
            return
        }
        notice = null
        dialogError = null
        pending = BudgetChange.Set(limit, ratio)
    }

    fun openClear() {
        if (busy) return
        notice = null
        dialogError = null
        pending = BudgetChange.Clear
    }

    fun confirm(change: BudgetChange) {
        if (submitBlocked(busy, expired)) return
        busy = true
        dialogError = null
        scope.launch {
            var failure: String? = null
            try {
                when (change) {
                    is BudgetChange.Set -> ApiClient.putProjectBudget(budget.projectId, change.limit, change.ratio)
                    BudgetChange.Clear -> ApiClient.deleteProjectBudget(budget.projectId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = budgetFailText((e as? ApiException)?.status)
            } finally {
                busy = false
            }
            if (failure != null) {
                dialogError = failure
                return@launch
            }
            pending = null
            notice = ActionNotice(
                if (change is BudgetChange.Set) BUDGET_SET_DONE_MESSAGE else BUDGET_CLEAR_DONE_MESSAGE,
                ok = true,
            )
            reseedFrom = budget
            onChanged()
        }
    }

    val warning = budgetWarningOf(budget.monthlyLimitJpy, budget.softExceeded)
    Column(modifier = Modifier.fillMaxWidth()) {
        SelectableText("今月の予算（運用者のみ）", style = MaterialTheme.typography.titleMedium)
        SelectableText(BUDGET_SECTION_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectableText(budgetPeriodText(budget.period), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        BudgetFigure("上限", budgetYenText(budget.monthlyLimitJpy))
        BudgetFigure("警告の基準", budgetYenText(budget.softLimitJpy))
        BudgetFigure("確定", budgetYenText(budget.committedJpy))
        BudgetFigure("予約中", budgetYenText(budget.reservedJpy))
        BudgetFigure("残額", budgetYenText(budget.remainingJpy))
        SelectableText(
            warning.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (warning.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = limitInput,
                onValueChange = { if (it.length <= BUDGET_LIMIT_INPUT_MAX) limitInput = it },
                label = { SelectableText("月の上限（円、整数）") },
                placeholder = { SelectableText("例 10000") },
                singleLine = true,
                enabled = !busy && !expired,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = ratioInput,
                onValueChange = { if (it.length <= BUDGET_RATIO_INPUT_MAX) ratioInput = it },
                label = { SelectableText("警告の基準（0 より大きく 1 以下）") },
                singleLine = true,
                enabled = !busy && !expired,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && !expired, onClick = { openSet() }) { SelectableText("上限を設定…") }
            OutlinedButton(enabled = !busy && !expired, onClick = { openClear() }) { SelectableText("上限を解除…") }
        }
        notice?.let { n ->
            SelectableText(
                n.text,
                color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    // 確認ダイアログ。送信中と失敗時は開いたままにする(閉じるのは成功のときと、キャンセルのとき)。
    val change = pending
    if (change != null) {
        val isSet = change is BudgetChange.Set
        ReasonDialog(
            title = if (isSet) BUDGET_SET_TITLE else BUDGET_CLEAR_TITLE,
            message = if (change is BudgetChange.Set) budgetSetExplain(change.limit, change.ratio) else BUDGET_CLEAR_EXPLAIN,
            submitLabel = if (isSet) BUDGET_SET_SUBMIT_LABEL else BUDGET_CLEAR_SUBMIT_LABEL,
            reasonLabel = null,
            busy = busy,
            errorText = dialogError,
            onSubmit = { confirm(change) },
            onDismiss = {
                if (!busy) {
                    pending = null
                    dialogError = null
                }
            },
        )
    }
}

/** 予算の一行(項目名と金額)。金額は [budgetYenText] の文字。 */
@Composable
private fun BudgetFigure(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        SelectableText(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        SelectableText(value, style = MaterialTheme.typography.bodySmall)
    }
}
