package net.kigawa.kivra.acceptance

import net.kigawa.kivra.db.JournalEntry

/**
 * Safety invariants that every scenario is checked against (issue #70). A violation is an AssertionError, never a skip.
 * The checker has its own negative test (SafetyCheckerNegativeTest): a deliberately unsafe fake must make it fail.
 */
object InvariantChecker {
    /** No provider request beyond what the Journal dispatched (no automatic replay of an operation). */
    fun checkNoAutomaticReplay(entry: JournalEntry, automaticReplays: Int) {
        check(automaticReplays == 0) {
            "INVARIANT VIOLATED: operation ${entry.opType} was sent $automaticReplays time(s) beyond its journal dispatches (attempt=${entry.attempt})"
        }
    }

    /** RETRYABLE is only reachable with a NOT_SENT proof from the trusted Authority. */
    fun checkRetryableHasNotSentProof(entry: JournalEntry) {
        if (entry.status.name == "RETRYABLE") {
            check(entry.evidencePurpose == "NOT_SENT") {
                "INVARIANT VIOLATED: operation ${entry.opType} is RETRYABLE without NOT_SENT evidence (purpose=${entry.evidencePurpose})"
            }
        }
    }

    /** The provider never holds more external effects than the scenario allows. */
    fun checkEffectsAtMost(actual: Int, max: Int, what: String) {
        check(actual <= max) { "INVARIANT VIOLATED: $what is $actual, expected at most $max" }
    }
}
