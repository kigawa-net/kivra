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

    private val RUN_ID_PATTERN = Regex("[a-zA-Z0-9._-]{1,100}")
}
