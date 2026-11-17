package net.kigawa.kivra.recl

import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.memory.*
import java.time.Instant

internal object M5MemoryFixtures {
    val scope = ScopeRef.tenant("test")
    private val now = Instant.parse("2026-11-17T00:00:00Z")
    val task = TaskSnapshot(
        id = TaskId("fusha:test:1"), title = "Gradle", description = "Build memory support",
        assigneeId = "bot", dueDate = null,
        fields = mapOf("status" to "\"ready\"", "dependencies" to "[]", "target" to "\"A\"", "repository" to "\"kigawa/kivra\""),
        nextTaskIds = emptyList(), relatedTaskIds = emptyList(), updatedAt = now, fetchedAt = now,
    )
    val ctx = AccessContext(setOf(scope), "execution", task.id.value, "orchestrator", "trace", "test")
    fun entry() = NewMemoryEntry(
        kind = MemoryKind.SEMANTIC, scope = listOf(scope), title = "Gradle",
        summary = "PRIVATE_MEMORY_CONTENT Gradle needs JDK 21",
        sources = listOf(SourceRef(SourceType.GITHUB_ISSUE, "https://github.com/kigawa/kivra/issues/1", retrievedAt = now)),
        provenance = MemoryProvenance.PRIMARY_SOURCE, confidence = 0.9, importance = 0.5,
        sensitivity = Sensitivity.INTERNAL, retention = "default",
        writtenBy = WriteProvenance(task.id.value, "execution", "test"),
    )
}
