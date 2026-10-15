package net.kigawa.kivra.policy

import kotlin.test.*

/** Approval binding to the policy hash (ADR 0007, TOCTOU): the digest and the refusal after a Policy change. */
class ApprovalPolicyHashTest {
    private val base = """{"version":1,"rules":[
          {"action":"fusha.comment","resource":"*","roles":["DEVELOPER"],"risk":"LOW"},
          {"action":"k8s.apply","resource":"ns:prod","roles":["DEVELOPER"],"risk":"HIGH"}]}"""

    // Same rules, different record order, field order and formatting.
    private val reordered = """{"rules":[
          {"risk":"HIGH","roles":["DEVELOPER"],"resource":"ns:prod","action":"k8s.apply"},
          {"roles":["DEVELOPER"],"risk":"LOW","action":"fusha.comment"}],"version":1}"""

    // One extra rule for an unrelated action: the decision for k8s.apply is unchanged, the policy hash is not.
    private val extended = base.replace("]}", ",\n          {\"action\":\"fusha.comment\",\"resource\":\"*\",\"roles\":[\"REVIEWER\"],\"risk\":\"LOW\"}]}")

    private val changedRisk = base.replace("\"risk\":\"LOW\"", "\"risk\":\"MEDIUM\"")

    private val dev = Principal("run1", "agentA", Role.DEVELOPER, setOf("k8s.*", "fusha.*", "approval.*"))
    private val cmd = CanonicalCommand.of("k8s.apply", "ns:prod", mapOf("namespace" to "prod"))

    private class Store {
        val digests = mutableMapOf<String, OperationDigest>()
        val issued = mutableMapOf<String, String>()
        val consumed = mutableSetOf<String>()
        val audits = mutableListOf<AuditRecord>()
        val executed = mutableListOf<String>()
    }

    private fun gateway(store: Store, engine: PolicyEngine) = ToolGateway(
        verifier = { if (it == "tok") dev else null },
        status = { _, _ -> true },
        engine = engine,
        approvals = object : ApprovalConsumer {
            override fun consume(approvalId: String, digest: OperationDigest, decisionId: String): Boolean =
                store.digests[approvalId] == digest && store.consumed.add(approvalId)

            override fun issuedUnderPolicyHash(approvalId: String): String? = store.issued[approvalId]
        },
        audit = { store.audits += it; true },
        bindings = mapOf(
            "k8s.apply" to ToolBinding(
                StrictParamsDecoder("k8s.apply", setOf("namespace")) { "ns:" + it.getValue("namespace") },
                ToolHandler { _, _ -> store.executed += "k8s.apply"; "ok" },
            ),
        ),
    )

    private fun apply(approval: String? = null) =
        ToolRequest("tok", "k8s.apply", "ns:prod", mapOf("namespace" to "prod"), approval)

    /** Requests an approval under [engine] and records it as granted for that policy, as the approval service would. */
    private fun grant(store: Store, engine: PolicyEngine) {
        val need = assertIs<ToolResult.ApprovalRequired>(gateway(store, engine).call(apply()))
        store.digests["ap"] = need.digest
        store.issued["ap"] = engine.policyHash
    }

    @Test fun sameOperationAndSamePolicyGiveTheSameDigest() {
        val d1 = OperationDigest.of(dev, cmd, PolicyEngine.parse(base).policyHash)
        val d2 = OperationDigest.of(dev, cmd, PolicyEngine.parse(base).policyHash)
        assertEquals(d1, d2)
    }

    @Test fun oneRuleDifferenceChangesTheDigestOfTheSameOperation() {
        val h = PolicyEngine.parse(base).policyHash
        assertNotEquals(OperationDigest.of(dev, cmd, h), OperationDigest.of(dev, cmd, PolicyEngine.parse(extended).policyHash))
        assertNotEquals(OperationDigest.of(dev, cmd, h), OperationDigest.of(dev, cmd, PolicyEngine.parse(changedRisk).policyHash))
    }

    @Test fun approvalIssuedUnderAnotherPolicyIsRefusedWithPolicyChangedAndNotConsumed() {
        val store = Store()
        val p1 = PolicyEngine.parse(base)
        grant(store, p1)

        val denied = assertIs<ToolResult.Denied>(gateway(store, PolicyEngine.parse(extended)).call(apply("ap")))
        assertEquals(POLICY_CHANGED_REASON, denied.reason)
        assertTrue(store.consumed.isEmpty(), "a refused approval must not be consumed")
        assertTrue(store.executed.isEmpty())

        // Back to the policy the approval was issued under: the same approval is valid and is consumed once.
        assertIs<ToolResult.Success>(gateway(store, p1).call(apply("ap")))
        assertEquals(setOf("ap"), store.consumed)
    }

    @Test fun approvalWithUnknownIssuingPolicyIsStillRefusedByDigestAndNotConsumed() {
        val store = Store()
        grant(store, PolicyEngine.parse(base))
        store.issued.clear() // the approval service does not report the policy hash
        assertIs<ToolResult.Denied>(gateway(store, PolicyEngine.parse(extended)).call(apply("ap")))
        assertTrue(store.consumed.isEmpty())
        assertTrue(store.executed.isEmpty())
    }

    @Test fun rebuiltPolicyWithSameRulesAcceptsTheApproval() {
        val store = Store()
        grant(store, PolicyEngine.parse(base))
        val rebuilt = PolicyEngine.parse(reordered)
        assertEquals(PolicyEngine.parse(base).policyHash, rebuilt.policyHash)
        assertIs<ToolResult.Success>(gateway(store, rebuilt).call(apply("ap")))
    }

    @Test fun runAgentArgumentsAndArgumentOrderAreStillPartOfTheDigest() {
        val h = PolicyEngine.parse(base).policyHash
        val baseDigest = OperationDigest.of(dev, cmd, h)
        val reorderedArgs = CanonicalCommand.of("k8s.apply", "ns:prod", linkedMapOf("manifest" to "a", "namespace" to "prod"))
        val withArg = CanonicalCommand.of("k8s.apply", "ns:prod", mapOf("namespace" to "prod", "manifest" to "a"))
        assertEquals(OperationDigest.of(dev, withArg, h), OperationDigest.of(dev, reorderedArgs, h), "argument order must not matter")
        assertNotEquals(baseDigest, OperationDigest.of(dev.copy(runId = "run2"), cmd, h))
        assertNotEquals(baseDigest, OperationDigest.of(dev.copy(agentId = "agentB"), cmd, h))
        assertNotEquals(baseDigest, OperationDigest.of(dev, withArg, h))
    }

    @Test fun digestIsSixtyFourLowercaseHexCharacters() {
        val d = OperationDigest.of(dev, cmd, PolicyEngine.parse(base).policyHash)
        assertTrue(Regex("[0-9a-f]{64}").matches(d.hex), d.hex)
    }

    @Test fun malformedPolicyHashIsRejected() {
        assertFailsWith<IllegalArgumentException> { OperationDigest.of(dev, cmd, "not-a-hash") }
    }

    @Test fun policyChangedRefusalDoesNotLeakRuleContents() {
        val store = Store()
        grant(store, PolicyEngine.parse(base))
        store.audits.clear() // only the records of the refused call are checked
        val denied = assertIs<ToolResult.Denied>(gateway(store, PolicyEngine.parse(extended)).call(apply("ap")))
        assertTrue(store.audits.isNotEmpty())
        val texts = listOf(denied.reason) + store.audits.map { it.reason }
        for (t in texts) {
            for (secret in listOf("DEVELOPER", "REVIEWER", "HIGH", "LOW", "fusha", "k8s.apply", "ns:prod", "kivra-policy-v1")) {
                assertFalse(t.contains(secret), "refusal text must not contain '$secret': $t")
            }
        }
    }
}
