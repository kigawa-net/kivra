package net.kigawa.kivra.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.ui.api.ApiChatMessage

/*
 * The AI reply stream of the project chat (server `POST /api/projects/{id}/chat/stream`), as pure logic: one server-sent event is a name and a
 * one-line JSON data. A name this client does not know, or data that does not decode, is not an event (null), so a half-read frame is never shown.
 * The error text is fixed here: the server's codes are the gateway's error kinds (or NO_REPLY / EMPTY_REPLY), never a message.
 */

/** One event of the reply stream. `user` comes first, then `delta`s, then exactly one of `done` or `error`. */
sealed interface ChatStreamEvent {
    /** The user's message, as stored by the server. */
    data class User(val message: ApiChatMessage) : ChatStreamEvent

    /** A piece of the assistant's text, in order. */
    data class Delta(val text: String) : ChatStreamEvent

    /**
     * A tool call of the AI (ADR 0023 追補 5): the tool's name, its fixed status (STARTED, OK, DENIED, PROPOSED, FAILED) and, at the end, a fixed
     * code. Nothing of the call's arguments or data.
     */
    data class Tool(val name: String, val status: String, val code: String? = null) : ChatStreamEvent

    /** The reply is complete and stored; [message] is the stored assistant message. */
    data class Done(val message: ApiChatMessage) : ChatStreamEvent

    /** The reply was not stored. [code] is the server's fixed code. */
    data class Failed(val code: String) : ChatStreamEvent
}

@Serializable private class ChatDeltaData(val text: String)

@Serializable private class ChatErrorData(val code: String)

@Serializable private class ChatToolData(val name: String, val status: String, val code: String? = null)

private val chatStreamJson = Json { ignoreUnknownKeys = true }

/** The event of one server-sent event frame, or null for an unknown name or data that does not decode. */
fun chatStreamEventOf(name: String, data: String): ChatStreamEvent? = try {
    when (name) {
        "user" -> ChatStreamEvent.User(chatStreamJson.decodeFromString(ApiChatMessage.serializer(), data))
        "delta" -> ChatStreamEvent.Delta(chatStreamJson.decodeFromString(ChatDeltaData.serializer(), data).text)
        "done" -> ChatStreamEvent.Done(chatStreamJson.decodeFromString(ApiChatMessage.serializer(), data))
        "error" -> ChatStreamEvent.Failed(chatStreamJson.decodeFromString(ChatErrorData.serializer(), data).code)
        "tool" -> chatStreamJson.decodeFromString(ChatToolData.serializer(), data).let { ChatStreamEvent.Tool(it.name, it.status, it.code) }
        else -> null
    }
} catch (e: Exception) {
    null
}

/** The text shown for a failed reply. Known codes get their own text; any other code gets the generic one. */
fun chatStreamErrorText(code: String): String = when (code) {
    "TIMEOUT" -> "返信がタイムアウトしました。もう一度送ってください。"
    "POLICY_DENIED" -> "予算または設定の制限により、返信を作れませんでした。"
    "OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED" -> "返信の結果を確認できませんでした。もう一度送る前に、チャットを開き直してください。"
    "NO_REPLY", "EMPTY_REPLY" -> "返信が空でした。もう一度送ってください。"
    "NETWORK" -> "返信の受信が途中で切れました。チャットを開き直して確認してください。"
    "TOOL_ROUND_LIMIT", "LOOP_BUDGET", "LOOP_TIMEOUT" -> "確認の回数か時間の上限に達したため、返信を作れませんでした。依頼を絞ってもう一度送ってください。"
    else -> "返信を作れませんでした。"
}

/** What a tool is about, in Japanese: the object of 「…を確認中」 or 「…を実行」. An unknown name gets a generic word (the name is not shown). */
private fun chatToolSubject(name: String): String = when (name) {
    "order_get", "order_list" -> "order"
    "run_get", "run_list" -> "Run"
    "task_list" -> "Task"
    "project_get" -> "プロジェクト"
    "memory_search", "memory_get" -> "プロジェクトの記憶"
    "order_start_cycle" -> "周の開始"
    "order_pause" -> "order の一時停止"
    "order_resume" -> "order の再開"
    "project_update" -> "プロジェクトの変更"
    "order_abandon_cycle" -> "周の放棄"
    "order_stop_request" -> "order の停止要求"
    "member_add" -> "メンバーの追加"
    "member_remove" -> "メンバーの削除"
    else -> "操作"
}

/** The reads (their STARTED is 「…を確認中…」); the rest are operations or proposals. */
private val CHAT_READ_TOOLS = setOf("order_get", "order_list", "run_get", "run_list", "task_list", "project_get", "memory_search", "memory_get")

/** The proposals: the AI only proposes them, and the operator confirms with the button below. */
private val CHAT_PROPOSAL_TOOLS = setOf("order_abandon_cycle", "order_stop_request", "member_add", "member_remove")

/**
 * The line shown for a tool event while the reply streams. A read in progress is 「order を確認中…」; a proposal is 「提案しました（確定は下の
 * ボタン）」; an immediate operation that ran says so. A refusal or a failure says it was not done, with a fixed text (the code is not shown).
 */
fun chatToolStatusText(event: ChatStreamEvent.Tool): String {
    // An English word (order, Run, Task) takes a space before the particle, as the rest of the UI writes it.
    val subject = chatToolSubject(event.name).let { if (it.last() in 'A'..'z') "$it " else it }
    val read = event.name in CHAT_READ_TOOLS
    return when (event.status) {
        "STARTED" -> if (read) "${subject}を確認中…" else "${subject}を実行中…"
        "OK" -> if (read) "${subject}を確認しました" else "${subject}を実行しました"
        "PROPOSED" -> "${subject}を提案しました（確定は下のボタン）"
        "DENIED" -> "${subject}は許可されていないため、実行しませんでした"
        else -> when (event.code) {
            "NOT_FOUND" -> "${subject.trimEnd()}: 対象がこのプロジェクトに見つかりませんでした"
            else -> "${subject}を実行できませんでした"
        }
    }.let { if (event.name in CHAT_PROPOSAL_TOOLS && event.status == "STARTED") "${subject.trimEnd()}の提案を準備中…" else it }
}

/**
 * The tool lines of a turn: one line per call, the newest status of each. A call's STARTED line is replaced by its end, in order. [lines] is the
 * current list; the event's line is appended, or replaces the last STARTED line of the same tool.
 */
fun chatToolLines(lines: List<Pair<String, ChatStreamEvent.Tool>>, event: ChatStreamEvent.Tool): List<Pair<String, ChatStreamEvent.Tool>> {
    val line = chatToolStatusText(event) to event
    if (event.status == "STARTED") return lines + line
    val open = lines.indexOfLast { it.second.name == event.name && it.second.status == "STARTED" }
    return if (open < 0) lines + line else lines.toMutableList().also { it[open] = line }
}
