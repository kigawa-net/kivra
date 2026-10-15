package net.kigawa.kivra.db

import java.math.BigDecimal
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.CostBasis
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.GatewayWarning
import net.kigawa.kivra.modelgateway.LedgerClaim
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.Usage
import org.jetbrains.exposed.sql.transactions.transaction

/** Runs the shared contract over [DbRequestLedger]: one fresh migrated database per test (H2, or MariaDB with KIVRA_TEST_DB=mariadb). */
class DbRequestLedgerContractTest : RequestLedgerContract() {
    override fun withLedger(capacity: Int?, block: (LedgerFixture) -> Unit) {
        TestDb().use { db ->
            val cap = capacity ?: DbRequestLedger.DEFAULT_CAPACITY
            val extra = mutableListOf<DataSource>()
            try {
                block(
                    LedgerFixture(
                        ledger = DbRequestLedger(db.database, cap),
                        replica = {
                            val ds = db.replicaDataSource().also { extra.add(it) }
                            DbRequestLedger(connectDatabase(ds), cap)
                        },
                    ),
                )
            } finally {
                extra.forEach { closeDataSource(it) }
            }
        }
    }
}

/** DB-specific behaviour: size limit, fail-closed store failures, fidelity of the replayed response, and the table shape. */
class DbRequestLedgerTest {
    @Test
    fun responseLargerThan256KbIsSealedButNotReplayable() = TestDb().use { db ->
        val ledger = DbRequestLedger(db.database)
        // 90 000 three-byte characters: fewer than 256 K characters but more than 256 KiB of UTF-8.
        val big = sampleResponse("big", text = "あ".repeat(90_000))
        ledger.begin("big", DIGEST_A); ledger.markSending("big"); ledger.complete("big", big)
        assertEquals(LedgerClaim.Completed(null), ledger.begin("big", DIGEST_A))
        assertEquals(0, db.count("request_ledger", "request_id = 'big' AND response_replayable = TRUE"))
        assertEquals(1, db.count("request_ledger", "request_id = 'big' AND state = 'COMPLETED' AND response_json IS NULL"))
    }

    @Test
    fun responseAtTheLimitIsReplayable() = TestDb().use { db ->
        val ledger = DbRequestLedger(db.database)
        val resp = sampleResponse("ok", text = "x".repeat(1000))
        ledger.begin("ok", DIGEST_A); ledger.markSending("ok"); ledger.complete("ok", resp)
        assertEquals(LedgerClaim.Completed(resp), ledger.begin("ok", DIGEST_A))
        assertEquals(1, db.count("request_ledger", "request_id = 'ok' AND response_replayable = TRUE"))
    }

    @Test
    fun storeFailureIsFailClosedAndNeverReleasesOrRewrites() = TestDb().use { db ->
        val primary = DbRequestLedger(db.database)
        primary.begin("f", DIGEST_A); primary.markSending("f")

        val ds = db.replicaDataSource()
        val broken = DbRequestLedger(connectDatabase(ds))
        closeDataSource(ds) // every connection now fails

        assertEquals("STORE_UNAVAILABLE", rejectedCode(broken.begin("new", DIGEST_A)))
        assertFailsWith<RequestLedgerWriteException> { broken.markSending("f") }
        assertFailsWith<RequestLedgerWriteException> { broken.complete("f", sampleResponse("f")) }
        broken.markOutcomeUnknown("f") // logs and returns: must not throw into the Gateway's settlement path
        broken.releaseUnsent("f")

        // The durable state is untouched: still SENDING, so the requestId stays sealed.
        assertEquals("DUPLICATE_REQUEST", rejectedCode(primary.begin("f", DIGEST_A)))
        assertEquals(1, db.count("request_ledger", "request_id = 'f' AND state = 'SENDING'"))
        assertEquals(0, db.count("request_ledger", "request_id = 'new'"))
    }

    @Test
    fun aFailedMarkSendingBlocksTheProviderCallAndLeavesTheRequestIdClaimable() = TestDb().use { db ->
        val primary = DbRequestLedger(db.database)
        primary.begin("m", DIGEST_A)
        val ds = db.replicaDataSource()
        val broken = DbRequestLedger(connectDatabase(ds))
        closeDataSource(ds)
        assertFailsWith<RequestLedgerWriteException> { broken.markSending("m") }
        // No SENDING was written, so a release is safe and the requestId can be claimed again.
        primary.releaseUnsent("m")
        assertEquals(LedgerClaim.Claimed, primary.begin("m", DIGEST_A))
    }

    @Test
    fun capacityBoundaryIsEnforcedOnNewIdsOnly() = TestDb().use { db ->
        val ledger = DbRequestLedger(db.database, capacity = 2)
        assertEquals(LedgerClaim.Claimed, ledger.begin("a", DIGEST_A))
        assertEquals(LedgerClaim.Claimed, ledger.begin("b", DIGEST_A))
        assertEquals("LEDGER_FULL", rejectedCode(ledger.begin("c", DIGEST_A)))
        // Existing rows still answer, and the refused id was not written.
        assertEquals("DUPLICATE_REQUEST", rejectedCode(ledger.begin("a", DIGEST_A)))
        assertEquals(0, db.count("request_ledger", "request_id = 'c'"))
        assertEquals(2, db.count("request_ledger"))
    }

    @Test
    fun responseSurvivesARestartWithEveryFieldUnchanged() = TestDb().use { db ->
        val full = ModelResponse(
            requestId = "full",
            attemptId = "full-a1",
            selected = ModelRef("ext", "m1", "provider-m1"),
            message = Message(
                Role.ASSISTANT,
                listOf(
                    Part.Text("日本語 \"quoted\" \\ \n tab\t and emoji 😀"),
                    Part.Image("image/png", "iVBORw0KGgo="),
                    Part.ToolCall("call-1", "search", buildJsonObject { put("q", JsonPrimitive("kotlin")); put("n", JsonPrimitive(3)) }),
                    Part.ToolResult("call-1", "result", isError = true),
                ),
            ),
            structured = buildJsonObject { put("ok", JsonPrimitive(true)); put("none", JsonNull) },
            finishReason = FinishReason.TOOL_CALL,
            usage = Usage(123_456_789_012L, 7, 0),
            cost = Cost(BigDecimal("0.00012000"), "USD", CostBasis.ESTIMATED, unit = "currency_units",
                priceRevision = "rev-9", model = ModelRef("ext", "m1", "provider-m1"), requestId = "full"),
            latencyMs = 1234,
            routingDecisionId = "decision-9",
            warnings = listOf(GatewayWarning("W1", "w1"), GatewayWarning("W2", "")),
            usageComplete = false,
        )
        val writer = DbRequestLedger(db.database)
        writer.begin("full", DIGEST_A); writer.markSending("full"); writer.complete("full", full)

        val ds = db.replicaDataSource()
        try {
            val restarted = DbRequestLedger(connectDatabase(ds))
            val claim = restarted.begin("full", DIGEST_A)
            assertIs<LedgerClaim.Completed>(claim)
            assertEquals(full, (claim as LedgerClaim.Completed).response)
        } finally {
            closeDataSource(ds)
        }
    }

    @Test
    fun structuredNullAndAbsentAndDecimalScaleRoundTripExactly() {
        val withNullJson = sampleResponse("n").copy(structured = JsonNull)
        val withoutStructured = sampleResponse("n").copy(structured = null)
        assertEquals(withNullJson, ModelResponseCodec.decode(ModelResponseCodec.encode(withNullJson)))
        assertEquals(withoutStructured, ModelResponseCodec.decode(ModelResponseCodec.encode(withoutStructured)))
        val cost = ModelResponseCodec.decode(ModelResponseCodec.encode(sampleResponse("c"))).cost
        assertEquals("0.00015000", cost.amount.toPlainString())
    }

    @Test
    fun rowsAreNeverDeleted() = TestDb().use { db ->
        val ledger = DbRequestLedger(db.database)
        for (i in 1..5) {
            ledger.begin("r$i", DIGEST_A); ledger.markSending("r$i"); ledger.complete("r$i", sampleResponse("r$i"))
        }
        assertEquals(5, db.count("request_ledger", "state = 'COMPLETED'"))
    }

    @Test
    fun migrationCreatesTheLedgerTableWithTheDocumentedColumns() = TestDb().use { db ->
        val columns = transaction(db.database) {
            execQuery(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ${TestDb.schemaSql} AND TABLE_NAME = 'request_ledger'",
            ) { it.getString(1).lowercase() }.toSet()
        }
        assertEquals(
            setOf("request_id", "digest", "state", "response_json", "response_replayable", "created_at", "updated_at"),
            columns,
        )
    }
}
