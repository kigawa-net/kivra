package net.kigawa.kivra.exkes

/** Stable references chosen by Kivra BEFORE the external call (design #11 section 4.4). */
object KivraNaming {
    const val RUN_ID_LABEL = "kivra.run-id"

    /**
     * Deterministic Workspace name. exkes enforces `[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}` and a UNIQUE
     * constraint on workspaces.name, so a Workspace created for a run can always be found again by
     * this name, and a replayed POST cannot create a second one (it gets 409 CONFLICT).
     */
    fun workspaceName(runId: String): String {
        require(runId.matches(RUN_ID_PATTERN)) { "runId is not usable in an exkes workspace name: $runId" }
        return "kivra-run-$runId".also { require(it.length <= 128) { "workspace name too long" } }
    }

    fun runLabels(runId: String): Map<String, String> = mapOf(RUN_ID_LABEL to runId)

    /**
     * The runId encoded in a Kivra workspace name, or null when [name] is not exactly `kivra-run-<runId>`.
     * Used by the Reaper: a name that does not match is never considered Kivra's.
     */
    fun runIdOfWorkspaceName(name: String): String? {
        val runId = name.removePrefix(WORKSPACE_PREFIX).takeIf { name.startsWith(WORKSPACE_PREFIX) } ?: return null
        return runId.takeIf { it.matches(ULID_PATTERN) }
    }

    private const val WORKSPACE_PREFIX = "kivra-run-"

    /** Run ids are ULIDs (Crockford base32, 26 chars; CHAR(26) in the schema). */
    private val ULID_PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{26}")

    private val RUN_ID_PATTERN = Regex("[a-zA-Z0-9._-]{1,100}")
}
