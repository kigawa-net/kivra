package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.NavDestination
import net.kigawa.kivra.ui.PROJECT_DESCRIPTION_MAX
import net.kigawa.kivra.ui.PROJECT_NAME_MAX
import net.kigawa.kivra.ui.PROJECT_CREATED_MESSAGE
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.SESSION_EXPIRED_MESSAGE
import net.kigawa.kivra.ui.UNASSIGNED_LABEL
import net.kigawa.kivra.ui.UNASSIGNED_PROJECT_ID
import net.kigawa.kivra.ui.landingPageOf
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.projectCreateFailureText
import net.kigawa.kivra.ui.submitBlocked
import net.kigawa.kivra.ui.yenText
import net.kigawa.kivra.ui.validatedProjectDescription
import net.kigawa.kivra.ui.validatedProjectName

/**
 * プロジェクトの一覧(全体の入口)。行を開くとそのプロジェクトのダッシュボードへ進み、各行に要約(承認待ち・実行中・失敗/解放待ち・予算)を出す。
 * [notice] は旧い URL からここへ誘導されたときの案内。作成フォームは CREATE_PROJECT の人だけ。
 * 作成の後は一覧を読み直し、[onProjectsChanged] で画面上部のプロジェクト切替も読み直させる。
 */
@Composable
fun ProjectsScreen(notice: String?, onNavigate: (Route) -> Unit, onProjectsChanged: () -> Unit) {
    val perms = LocalCallerPermissions.current
    var reload by remember { mutableStateOf(0) }
    val loaded = rememberLoad(Unit, reload) { ApiClient.getProjects() }
    val summaries = rememberLoad(loaded.value?.map { it.projectId }) {
        loaded.value.orEmpty().associate { it.projectId to loadProjectSummary(it.projectId) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("プロジェクト", style = MaterialTheme.typography.headlineSmall)
        notice?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (perms.can(GatedOperation.CREATE_PROJECT)) {
            ProjectCreateCard(onCreated = {
                reload += 1
                onProjectsChanged()
            })
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 未分類(プロジェクトに属さない既存のデータ)。プロジェクトが無くても出す。
        UnassignedCard(onNavigate)
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            LoadedList(loaded, emptyText = "プロジェクトがありません。") { projects ->
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(projects, key = { it.projectId }) { project ->
                        Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable {
                            onNavigate(Route.ProjectPage(project.projectId, landingPageOf(project.projectId)))
                        }) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                project.description?.takeIf { it.isNotBlank() }?.let { description ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    summaryLine(summaries.value?.get(project.projectId)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * 未分類の行(プロジェクトの一覧の上)。開くと未分類のタスク(Run の一覧)へ行く。データが 0 件なら薄く出す。
 * 作成はできない(未分類にデータを増やさない)ので、その旨を一文で示す。
 */
@Composable
private fun UnassignedCard(onNavigate: (Route) -> Unit) {
    val loaded = rememberLoad(Unit) { loadProjectSummary(UNASSIGNED_PROJECT_ID) }
    val s = loaded.value
    val faint = s?.unassignedRows == 0
    Card(modifier = Modifier.fillMaxWidth().alpha(if (faint) 0.6f else 1f).clickable {
        onNavigate(Route.ProjectPage(UNASSIGNED_PROJECT_ID, NavDestination.Tasks))
    }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(UNASSIGNED_LABEL, style = MaterialTheme.typography.titleMedium)
            Text(
                "プロジェクトに属さない既存のデータ(Run・開発目標・order・記憶)。作成はできません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (s?.unassignedRows == 0) "未分類のデータはありません" else summaryLine(s),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行の要約の文言。読み込み中・読めなかったものは、その旨を出す。 */
private fun summaryLine(s: ProjectSummary?): String {
    if (s == null) return "要約: 読み込み中"
    val approvals = s.approvals?.let { "承認待ち $it" } ?: "承認待ち 取得できません"
    val running = s.running?.let { "実行中 $it" } ?: "実行中 取得できません"
    val attention = s.attention?.let { "失敗・解放待ち $it" } ?: "失敗・解放待ち 取得できません"
    val budget = s.budget?.let { b ->
        "今月 ${yenText(b.committedJpy)}" + (b.monthlyLimitJpy?.let { " / 上限 ${yenText(it)}" } ?: "") +
            if (b.softExceeded) "（超過の警告）" else ""
    }
    return listOfNotNull(approvals, running, attention, budget).joinToString(" ・ ")
}

/**
 * プロジェクトの作成フォーム(旧 UI の project-create-form)。名前は 1〜100、説明は任意で 0〜500(UTF-16 の長さで数える。旧 UI と同じ)。
 * 失敗時は入力を残し、成功時は両方を消す。送信中は二重に送らない。
 */
@Composable
private fun ProjectCreateCard(onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    val blocked = submitBlocked(busy, ApiSession.expired)
    val nameOk = validatedProjectName(name) != null
    val descriptionOk = validatedProjectDescription(description) != null

    fun send() {
        if (submitBlocked(busy, ApiSession.expired)) return
        val validName = validatedProjectName(name) ?: return
        val validDescription = validatedProjectDescription(description) ?: return
        busy = true
        notice = null
        scope.launch {
            var created = false
            try {
                ApiClient.createProject(validName, validDescription.takeIf { it.isNotEmpty() })
                created = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = ActionNotice(projectCreateFailureText((e as? ApiException)?.status), ok = false)
            } finally {
                busy = false
            }
            if (created) {
                name = ""
                description = ""
                notice = ActionNotice(PROJECT_CREATED_MESSAGE, ok = true)
                onCreated()
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("新しいプロジェクト", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名前（1〜100 文字）") },
                enabled = !blocked,
                singleLine = true,
                isError = name.length > PROJECT_NAME_MAX,
                supportingText = { CounterText(name.length, PROJECT_NAME_MAX) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("説明（任意、0〜500 文字）") },
                enabled = !blocked,
                isError = description.length > PROJECT_DESCRIPTION_MAX,
                supportingText = { CounterText(description.length, PROJECT_DESCRIPTION_MAX) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { send() },
                enabled = !blocked && nameOk && descriptionOk,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("作成")
            }
            if (ApiSession.expired) {
                Text(SESSION_EXPIRED_MESSAGE, color = MaterialTheme.colorScheme.error)
            }
            notice?.let { n ->
                Text(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
