package net.kigawa.kivra.policy

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class PolicyHashTest {
    private val base = """
        {"version":1,"rules":[
          {"action":"fusha.comment","resource":"*","roles":["DEVELOPER","REVIEWER"],"risk":"LOW"},
          {"action":"github.pr.create","resource":"repo:kigawa-net/*","roles":["DEVELOPER"],"risk":"MEDIUM"},
          {"action":"k8s.apply","resource":"ns:prod","roles":["DEVELOPER"],"risk":"HIGH"}
        ]}
    """.trimIndent()

    private val fixedClock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC)
    private val dev = Principal("run1", "agentA", Role.DEVELOPER, setOf("*"))

    private fun hashOf(json: String) = PolicyEngine.parse(json).policyHash

    @Test fun sameRulesInDifferentOrderAndFormattingHashEqually() {
        val reordered = """{"version":1,"rules":[
          {"risk":"HIGH","roles":["DEVELOPER"],"resource":"ns:prod","action":"k8s.apply"},
          {"roles":["REVIEWER","DEVELOPER"],"action":"fusha.comment","risk":"LOW"},
          {"action":"github.pr.create","risk":"MEDIUM","resource":"repo:kigawa-net/*","roles":["DEVELOPER"]}
        ]}"""
        assertEquals(hashOf(base), hashOf(reordered), "record order, role order, key order and whitespace must not matter")
    }

    @Test fun changingAnyFieldOfOneRuleChangesHash() {
        val h = hashOf(base)
        val variants = listOf(
            base.replace("\"action\":\"k8s.apply\"", "\"action\":\"k8s.delete\""),          // action
            base.replace("\"resource\":\"ns:prod\"", "\"resource\":\"ns:stage\""),         // resource
            base.replace("\"resource\":\"repo:kigawa-net/*\"", "\"resource\":\"repo:kigawa-net/x*\""), // resource pattern
            base.replace("\"roles\":[\"DEVELOPER\"],\"risk\":\"HIGH\"", "\"roles\":[\"REVIEWER\"],\"risk\":\"HIGH\""), // roles
            base.replace("\"roles\":[\"DEVELOPER\"],\"risk\":\"HIGH\"", "\"roles\":[\"DEVELOPER\"],\"risk\":\"MEDIUM\""), // risk
        )
        for (v in variants) {
            assertNotEquals(h, hashOf(v), "a single changed field must change the hash: $v")
        }
    }

    @Test fun roleSetChangeChangesHash() {
        val withoutReviewer = base.replace("[\"DEVELOPER\",\"REVIEWER\"]", "[\"DEVELOPER\"]")
        assertNotEquals(hashOf(base), hashOf(withoutReviewer))
    }

    @Test fun hashIsDeterministicAndHex() {
        val h = hashOf(base)
        repeat(5) { assertEquals(h, hashOf(base)) }
        assertTrue(Regex("[0-9a-f]{64}").matches(h), "expected SHA-256 hex, got $h")
    }

    @Test fun emptyRuleSetHasStableHash() {
        assertEquals(PolicyEngine.denyAll().policyHash, PolicyEngine.parse("""{"rules":[]}""").policyHash)
        assertNotEquals(PolicyEngine.denyAll().policyHash, hashOf(base))
    }

    @Test fun constructionCopiesRulesSoCallerMutationHasNoEffect() {
        val roles = mutableListOf(Role.DEVELOPER)
        val rules = mutableListOf(PolicyRule("fusha.comment", "*", roles, RiskClass.LOW))
        val engine = PolicyEngine.fromRules(rules, fixedClock)
        val h = engine.policyHash
        val before = engine.evaluate(dev, "fusha.comment", "x")

        rules.add(PolicyRule("github.*", "*", listOf(Role.DEVELOPER), RiskClass.LOW))
        rules.clear()
        roles.add(Role.REVIEWER)
        roles.clear()

        assertEquals(h, engine.policyHash)
        assertEquals(1, engine.describe().ruleCount)
        assertTrue(engine.verifyIntegrity(h))
        assertEquals(before, engine.evaluate(dev, "fusha.comment", "x"))
        assertIs<Decision.Allow>(engine.evaluate(dev, "fusha.comment", "x"))
    }

    @Test fun verifyIntegrityPassesWhileRulesAreUnchanged() {
        val engine = PolicyEngine.parse(base)
        assertTrue(engine.verifyIntegrity(engine.policyHash))
        assertFalse(engine.verifyIntegrity(hashOf(base.replace("LOW", "MEDIUM"))))
    }

    @Test fun tamperedRulesAreDeniedByGatewayWhileUntamperedRulesPass() {
        val engine = PolicyEngine.parse(base)
        val audits = mutableListOf<AuditRecord>()
        val gw = ToolGateway(
            verifier = { dev },
            status = { _, _ -> true },
            engine = engine,
            approvals = { _, _, _ -> false },
            audit = { audits += it; true },
            bindings = mapOf("fusha.comment" to ToolBinding(
                CommandDecoder { CanonicalCommand.of("fusha.comment", "x") },
                ToolHandler { _, _ -> "ok" },
            )),
        )
        val req = ToolRequest("tok", "fusha.comment", "x")
        assertIs<ToolResult.Success>(gw.call(req))

        // Simulate a bypass of the read-only design (reflection): the rule list is changed after load.
        val field = PolicyEngine::class.java.getDeclaredField("rules").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(engine) as MutableList<PolicyRule>).clear()

        val denied = gw.call(req)
        assertIs<ToolResult.Denied>(denied)
        assertTrue(denied.reason.startsWith("POLICY_TAMPERED"), denied.reason)
        assertTrue(audits.last().outcome == AuditOutcome.DENIED)
    }

    @Test fun describeReportsHashAndCountButNoRuleContents() {
        val engine = PolicyEngine.parse(base, fixedClock)
        val d = engine.describe()
        assertEquals(POLICY_HASH_VERSION, d.version)
        assertEquals(engine.policyHash, d.policyHash)
        assertEquals(3, d.ruleCount)
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), d.loadedAt)
        val text = d.toString()
        listOf("fusha.comment", "github.pr.create", "k8s.apply", "repo:kigawa-net", "ns:prod").forEach {
            assertFalse(text.contains(it), "descriptor must not expose rule contents: $text")
        }
    }

    @Test fun logLineHasOnlyTwelveHashCharsAndCount() {
        val engine = PolicyEngine.parse(base, fixedClock)
        val line = engine.describe().logLine()
        assertEquals("policy loaded: hash=${engine.policyHash.take(12)} rules=3", line)
        assertEquals(12, line.substringAfter("hash=").substringBefore(" ").length)
        listOf("fusha.comment", "github.pr.create", "k8s.apply", "kigawa-net", "ns:prod", "DEVELOPER").forEach {
            assertFalse(line.contains(it), "log line must not expose rule contents: $line")
        }
    }
}
