package net.kigawa.kivra.selfdev.memory

import net.kigawa.kivra.db.SelfDevMemoryRepository
import org.slf4j.LoggerFactory

/**
 * The suppression shadow (ADR 0016 section 11, P3): counts what the deterministic rules would have refused, and writes only that count.
 *
 * - Called after a plan has been stored, so it counts stored plans only. It never changes the plan, refuses it, or retries the call. Its
 *   result is never returned to the Planner.
 * - Reads facts only (through [SelfDevMemoryRepository.shadowFacts]) and writes one audit row per rule that matched, with the rule's name
 *   and the count (`SHADOW_SUPPRESS`). No title, body, summary, path or exception message is logged or stored.
 * - Never throws. A failure is logged with the exception's class name only, and the Planner goes on.
 * - Off ([OFF], the default) reads and writes nothing.
 */
class SuppressShadow private constructor(private val repository: SelfDevMemoryRepository?) {
    /** True when the shadow is attached to a repository. */
    val enabled: Boolean get() = repository != null

    /**
     * [identities] are the identities of the new plan's tasks, in order (see [net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder.identityOf]).
     * [planId] is the new plan, which is excluded from the "recent plans".
     */
    fun planStored(goalId: String, planId: String, identities: List<String>) {
        val repo = repository ?: return
        try {
            val facts = repo.shadowFacts(identities.toSet(), planId)
            for ((rule, count) in SuppressShadowRules.evaluate(identities, facts)) {
                repo.recordSuppressShadow(rule.name, count)
                logger.info("suppress shadow: goalId={} planId={} rule={} count={}", goalId, planId, rule.name, count)
            }
        } catch (e: Exception) {
            logger.warn("suppress shadow failed: goalId={} exception={}", goalId, e.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SuppressShadow::class.java)

        /** The shadow of a switched-off feature: reads nothing, writes nothing. */
        val OFF = SuppressShadow(null)

        /** Counts through [repository]. The repository checks its own switch first. */
        fun of(repository: SelfDevMemoryRepository): SuppressShadow = SuppressShadow(repository)
    }
}
