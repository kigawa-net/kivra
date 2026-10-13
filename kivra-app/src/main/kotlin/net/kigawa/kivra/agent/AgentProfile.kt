package net.kigawa.kivra.agent

import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.exkes.ExecutionType

/**
 * Declarative Agent Profile with a pinned [version] (design #3 section 3.1, minimal M2 form). It describes the
 * Runtime template and the single Execution one Agent runs; its `name@version` is part of every request hash,
 * so a changed profile under an already journaled key is refused instead of silently re-sent.
 *
 * Which exkes template / Execution type really runs an agent is still open (design #1 section 11-2, docs/exkes-requests.md).
 */
@Serializable
data class AgentProfile(
    val name: String,
    val version: String,
    val role: String = "developer",
    val templateId: String,
    val executionType: String = "AGENT",
    val commandJson: String? = null,
    val agentConfigJson: String? = null,
    val timeoutSeconds: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "profile name must not be blank" }
        require(version.isNotBlank()) { "profile version must be pinned (not blank)" }
        require(templateId.isNotBlank()) { "templateId must not be blank" }
        requireNotNull(ExecutionType.entries.firstOrNull { it.name == executionType }) { "unknown executionType $executionType" }
        require(timeoutSeconds == null || timeoutSeconds > 0) { "timeoutSeconds must be positive" }
    }

    val ref: String get() = "$name@$version"
    val type: ExecutionType get() = ExecutionType.valueOf(executionType)
    val timeout: Duration? get() = timeoutSeconds?.let(Duration::ofSeconds)

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /** Parses a profile; when [pinnedVersion] is given the document must be exactly that version. */
        fun parse(text: String, pinnedVersion: String? = null): AgentProfile {
            val profile = json.decodeFromString(serializer(), text)
            if (pinnedVersion != null) require(profile.version == pinnedVersion) { "profile ${profile.name} is ${profile.version}, pinned $pinnedVersion" }
            return profile
        }
    }
}
