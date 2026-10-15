package net.kigawa.kivra.acceptance

/**
 * The ordered event list of one scenario run. Events carry only normalized facts (statuses, counts, fault names), never
 * generated ids, timestamps, request bodies or credentials, so two runs of the same scenario must produce equal traces.
 */
class ReplayTrace {
    private val events = mutableListOf<String>()

    fun record(event: String) {
        check(event.length <= MAX_EVENT_LENGTH) { "trace event too long" }
        events += event
    }

    fun snapshot(): List<String> = events.toList()

    companion object {
        const val MAX_EVENT_LENGTH = 300
    }
}
