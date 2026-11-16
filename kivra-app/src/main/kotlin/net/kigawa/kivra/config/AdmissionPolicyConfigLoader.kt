package net.kigawa.kivra.config

import net.kigawa.kivra.core.AdmissionPolicy

/** Loads the deployment-confirmed admission policy from KIVRA_ORCHESTRATOR_POLICY_* variables. */
object AdmissionPolicyConfigLoader {
    private const val PREFIX = "KIVRA_ORCHESTRATOR_POLICY_"
    private val keys = listOf("STATUS_FIELD", "ADMITTABLE_STATUSES", "CANCELLED_STATUSES", "DEPENDENCIES_FIELD", "TARGET_FIELD", "ALLOWED_TARGETS", "REPOSITORY_FIELD")

    fun from(env: Map<String, String>): AdmissionPolicy? {
        val supplied = keys.associateWith { key -> env["$PREFIX$key"]?.trim()?.takeIf(String::isNotEmpty) }
        if (supplied.values.all { it == null }) return null
        val missing = supplied.filterValues { it == null }.keys
        require(missing.isEmpty()) {
            "Incomplete admission policy configuration; missing ${missing.joinToString { "$PREFIX$it" }}"
        }
        fun value(key: String) = requireNotNull(supplied[key])
        fun csv(key: String): Set<String> = value(key).split(',').map(String::trim).also {
            require(it.all(String::isNotEmpty)) { "$PREFIX$key must be a comma-separated list of non-empty values" }
        }.toSet().also { require(it.isNotEmpty()) { "$PREFIX$key must not be empty" } }

        return try {
            AdmissionPolicy(
                statusField = value("STATUS_FIELD"),
                admittableStatuses = csv("ADMITTABLE_STATUSES"),
                cancelledStatuses = csv("CANCELLED_STATUSES"),
                dependenciesField = value("DEPENDENCIES_FIELD"),
                targetField = value("TARGET_FIELD"),
                allowedTargets = csv("ALLOWED_TARGETS"),
                repositoryField = value("REPOSITORY_FIELD"),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid admission policy configuration: ${e.message}", e)
        }
    }
}
