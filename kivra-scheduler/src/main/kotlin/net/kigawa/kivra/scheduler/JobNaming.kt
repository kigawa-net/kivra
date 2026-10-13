package net.kigawa.kivra.scheduler

/**
 * Deterministic ai-scheduler job names: `kivra:fusha:{env}:{id}` (issue #38). The name is the reconciliation key
 * (ai-scheduler job names are not unique server-side), so it must be derived, never random.
 */
object JobNaming {
    private const val PREFIX = "kivra:fusha:"
    private val segment = Regex("[a-z0-9][a-z0-9._-]{0,62}")

    /** [env] e.g. `prod`; [id] e.g. `tick`, `resume-<runId>` or a fusha task ref. Lower-case only so names are canonical. */
    fun build(env: String, id: String): String {
        require(segment.matches(env)) { "invalid env segment" }
        require(segment.matches(id)) { "invalid id segment" }
        return "$PREFIX$env:$id"
    }

    /** Returns (env, id) or null when [name] is not a Kivra job name. */
    fun parse(name: String): Pair<String, String>? {
        if (!name.startsWith(PREFIX)) return null
        val parts = name.removePrefix(PREFIX).split(':')
        if (parts.size != 2 || !segment.matches(parts[0]) || !segment.matches(parts[1])) return null
        return parts[0] to parts[1]
    }

    fun isValid(name: String) = parse(name) != null
}
