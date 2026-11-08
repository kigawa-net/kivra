package net.kigawa.kivra.ui.api

import kotlinx.serialization.Serializable

/**
 * `GET /api/selfdev/memory` の 1 ページ = server `MemoryListResponse`。件数(total、墓標、監査)も一緒に返る。
 */
@Serializable
data class ApiMemoryPage(
    val items: List<ApiMemory>,
    val total: Int,
    val tombstones: Int,
    val deletionAudit: Int,
)

/** `POST /api/selfdev/memory/delete-by-condition` の応答 = server `MemoryConditionResponse`。 */
@Serializable
data class ApiMemoryCondition(val matched: Int, val deleted: Int, val dryRun: Boolean)

/** `GET /api/selfdev/assignees/{id}/members` の 1 件 = server `MemberRefResponse`。人の sub は返らない(参照だけ)。 */
@Serializable
data class ApiMemberRef(val memberRef: String)

/**
 * `GET /api/selfdev/assignees/{id}/tokens` の 1 件 = server `TokenListItem` の表示に使う項目だけ。
 * 作成者の参照(createdBy)は読まない(画面に出さない)。
 */
@Serializable
data class ApiAssigneeToken(
    val tokenRef: String,
    val status: String,
    val expiresAt: String,
)

/**
 * トークンの発行の応答 = server `IssuedTokenResponse`。[token] は平文で、この応答にしか無い。
 * 文字列化しても平文を出さない(ログや例外の表示に混ざらないように)。
 */
@Serializable
class ApiIssuedToken(
    val token: String,
    val tokenRef: String,
    val expiresAt: String,
) {
    override fun toString(): String = "ApiIssuedToken(tokenRef=$tokenRef, expiresAt=$expiresAt)"
}

/** 担当の詳細の読み取り結果(メンバーの参照とトークンの一覧)。形の合わない参照は除いてある。 */
data class ApiAssigneeDetail(
    val memberRefs: List<String>,
    val tokens: List<ApiAssigneeToken>,
)
