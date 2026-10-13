package net.kigawa.kivra.observability

/**
 * Allowlist filter for span attributes and log fields.
 *
 * A key outside [allowedKeys] is dropped, and so is an allowed key whose value fails
 * [Redaction.isSafeValue]. Dropping (rather than masking) keeps secrets out of every backend.
 */
class AttributeFilter(allowedKeys: Set<String>, private val maxValueLength: Int = Redaction.MAX_VALUE_LENGTH) {
    private val allowed: Set<String> = allowedKeys.toSet()

    fun filter(attributes: Map<String, String>): Map<String, String> = buildMap {
        for ((key, value) in attributes) {
            if (key in allowed && Redaction.isSafeValue(value, maxValueLength)) put(key, value)
        }
    }

    companion object {
        /**
         * Keys allowed on spans and logs. Correlation IDs are intentionally here, but they must never
         * become metric labels (see [MetricCatalog]). Prompt, response, token and credential keys are
         * deliberately absent.
         */
        val SPAN_ATTRIBUTE_ALLOWLIST: Set<String> = setOf(
            "traceId", "taskId", "runId", "executionId", "agentId",
            "operationClass", "provider", "model", "purpose", "outcome", "errorKind",
        )
    }
}
