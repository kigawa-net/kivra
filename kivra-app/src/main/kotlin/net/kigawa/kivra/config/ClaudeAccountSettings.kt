package net.kigawa.kivra.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val CLAUDE_ACCOUNT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

@Serializable
internal data class ClaudeAccountOption(val id: String, val label: String)

internal class ClaudeAccountSettings private constructor(val available: List<ClaudeAccountOption>) {
    private val ids = available.map { it.id }.toSet()

    fun contains(id: String): Boolean = id in ids

    companion object {
        const val ENV_ACCOUNTS = "KIVRA_CLAUDE_ACCOUNTS"
        val EMPTY = ClaudeAccountSettings(emptyList())

        fun fromEnv(env: Map<String, String>): ClaudeAccountSettings {
            val raw = env[ENV_ACCOUNTS] ?: return EMPTY
            val array = try {
                Json.parseToJsonElement(raw) as? JsonArray
            } catch (_: SerializationException) {
                null
            }
            checkNotNull(array) { "$ENV_ACCOUNTS must be a JSON array of objects with id and label strings" }

            val seen = mutableSetOf<String>()
            val accounts = array.mapIndexed { index, entry ->
                val obj = entry as? JsonObject
                val id = obj?.get("id") as? JsonPrimitive
                val label = obj?.get("label") as? JsonPrimitive
                val location = "$ENV_ACCOUNTS[$index]"
                check(obj?.keys == setOf("id", "label") && id?.isString == true && label?.isString == true) {
                    "$location must contain exactly two string fields: id and label"
                }
                check(CLAUDE_ACCOUNT_ID_PATTERN.matches(id.content)) {
                    "$location.id must match ${CLAUDE_ACCOUNT_ID_PATTERN.pattern}"
                }
                check(label.content.isNotBlank()) { "$location.label must not be blank" }
                check(seen.add(id.content)) { "$location.id duplicates an earlier account ID" }
                ClaudeAccountOption(id.content, label.content)
            }
            return ClaudeAccountSettings(accounts)
        }
    }
}
