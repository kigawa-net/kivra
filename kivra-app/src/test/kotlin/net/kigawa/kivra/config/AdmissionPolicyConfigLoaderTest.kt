package net.kigawa.kivra.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdmissionPolicyConfigLoaderTest {
    private val valid = mapOf(
        "KIVRA_ORCHESTRATOR_POLICY_STATUS_FIELD" to "status",
        "KIVRA_ORCHESTRATOR_POLICY_ADMITTABLE_STATUSES" to "ready,queued",
        "KIVRA_ORCHESTRATOR_POLICY_CANCELLED_STATUSES" to "cancelled",
        "KIVRA_ORCHESTRATOR_POLICY_DEPENDENCIES_FIELD" to "dependencies",
        "KIVRA_ORCHESTRATOR_POLICY_TARGET_FIELD" to "target",
        "KIVRA_ORCHESTRATOR_POLICY_ALLOWED_TARGETS" to "A,B",
        "KIVRA_ORCHESTRATOR_POLICY_REPOSITORY_FIELD" to "repository",
    )

    @Test fun `no settings is fail closed`() = assertNull(AdmissionPolicyConfigLoader.from(emptyMap()))

    @Test fun `loads policy sets from environment`() {
        val policy = requireNotNull(AdmissionPolicyConfigLoader.from(valid))
        assertEquals("status", policy.statusField)
        assertEquals(setOf("ready", "queued"), policy.admittableStatuses)
        assertEquals(setOf("A", "B"), policy.allowedTargets)
    }

    @Test fun `partial settings fail with missing variable names`() {
        val error = assertFailsWith<IllegalArgumentException> { AdmissionPolicyConfigLoader.from(mapOf(valid.entries.first().toPair())) }
        kotlin.test.assertTrue(error.message!!.contains("KIVRA_ORCHESTRATOR_POLICY_ADMITTABLE_STATUSES"))
    }

    @Test fun `empty list member is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            AdmissionPolicyConfigLoader.from(valid + ("KIVRA_ORCHESTRATOR_POLICY_ALLOWED_TARGETS" to "A,"))
        }
        kotlin.test.assertTrue(error.message!!.contains("ALLOWED_TARGETS"))
    }
}
