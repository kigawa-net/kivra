package net.kigawa.kivra.memory

import java.time.Instant

object T {
    val tenant = ScopeRef.tenant("t1")
    val project = ScopeRef.project("t1", "kivra")
    val otherTenant = ScopeRef.tenant("t2")
    val at: Instant = Instant.parse("2026-10-12T00:00:00Z")

    fun ctx(scope: ScopeRef = tenant, exec: String = "exec-1") = ctx(listOf(scope), exec)

    fun ctx(scopes: List<ScopeRef>, exec: String = "exec-1") = AccessContext(
        allowedScopes = scopes.toSet(), executionId = exec, taskId = "task-1", agentProfile = "dev", traceId = "trace-1", purpose = "test",
    )

    fun source(uri: String = "https://github.com/kigawa-net/kivra/issues/37") =
        SourceRef(SourceType.GITHUB_ISSUE, uri, null, at, "sha256:abcdef0123456789")

    fun entry(
        title: String = "Gradle requires JDK 21",
        summary: String = "Build kivra with JDK 21 because the wrapper does not run on JDK 25",
        body: String? = "Detailed note about the toolchain.",
        scope: ScopeRef = project,
        kind: MemoryKind = MemoryKind.SEMANTIC,
        sensitivity: Sensitivity = Sensitivity.INTERNAL,
        pinned: Boolean = false,
        importance: Double = 0.5,
        exec: String = "exec-1",
    ) = NewMemoryEntry(
        kind = kind, scope = listOf(scope), title = title, summary = summary, body = body, sources = listOf(source()),
        provenance = MemoryProvenance.PRIMARY_SOURCE, confidence = 0.9, importance = importance, sensitivity = sensitivity,
        retention = "default", pinned = pinned, writtenBy = WriteProvenance("task-1", exec, "agent:dev"),
    )

    fun key(s: String) = net.kigawa.kivra.core.IdempotencyKey(s)
}
