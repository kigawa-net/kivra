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

    /** The reply is complete and stored; [message] is the stored assistant message. */
    data class Done(val message: ApiChatMessage) : ChatStreamEvent

    /** The reply was not stored. [code] is the server's fixed code. */
    data class Failed(val code: String) : ChatStreamEvent
}

@Serializable private class ChatDeltaData(val text: String)

@Serializable private class ChatErrorData(val code: String)

private val chatStreamJson = Json { ignoreUnknownKeys = true }

/** The event of one server-sent event frame, or null for an unknown name or data that does not decode. */
fun chatStreamEventOf(name: String, data: String): ChatStreamEvent? = try {
    when (name) {
        "user" -> ChatStreamEvent.User(chatStreamJson.decodeFromString(ApiChatMessage.serializer(), data))
        "delta" -> ChatStreamEvent.Delta(chatStreamJson.decodeFromString(ChatDeltaData.serializer(), data).text)
        "done" -> ChatStreamEvent.Done(chatStreamJson.decodeFromString(ApiChatMessage.serializer(), data))
        "error" -> ChatStreamEvent.Failed(chatStreamJson.decodeFromString(ChatErrorData.serializer(), data).code)
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
    else -> "返信を作れませんでした。"
}
