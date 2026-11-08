package net.kigawa.kivra.selfdev.review

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef

/** The V45 table: the claim is the idempotency key, and the counts leave SKIPPED rows out. */
class DbPrReviewStoreTest {

    private val repo = RepoRef("kigawa-net", "kivra")
    private val head1 = "1".repeat(40)
    private val head2 = "2".repeat(40)

    private fun store(db: TestDb) = DbPrReviewStore(db.database, db.runs, SelfDevOperatorRepository(db.database), AuditRepository())

    @Test
    fun `a head is claimed once, and another head is a new claim`() = TestDb().use { db ->
        val s = store(db)
        assertTrue(s.claim(repo, 7, head1, RunId("run-a")))
        assertFalse(s.claim(repo, 7, head1, RunId("run-a")))
        assertTrue(s.claim(repo, 7, head2, RunId("run-a")))
        assertTrue(s.claim(RepoRef("kigawa-net", "other"), 7, head1, RunId("run-b")))
    }

    @Test
    fun `the state, verdict and count are set, and the counts leave the skipped rows out`() = TestDb().use { db ->
        val s = store(db)
        s.claim(repo, 7, head1, RunId("run-a"))
        s.finish(repo, 7, head1, PrReviewState.SKIPPED, null, 0)
        assertEquals(0, s.reviewsOfPr(repo, 7))
        assertEquals(0, s.reviewsSince(Instant.now().minusSeconds(3600)))

        s.claim(repo, 7, head2, RunId("run-a"))
        s.finish(repo, 7, head2, PrReviewState.POSTED, PrReviewVerdict.CONCERNS, 3)
        assertEquals(1, s.reviewsOfPr(repo, 7))
        assertEquals(0, s.reviewsOfPr(repo, 8))
        assertEquals(1, s.reviewsSince(Instant.now().minusSeconds(3600)))
        assertEquals(0, s.reviewsSince(Instant.now().plusSeconds(3600)))
    }

    @Test
    fun `a finding count out of range is kept inside the schema bound`() = TestDb().use { db ->
        val s = store(db)
        s.claim(repo, 9, head1, RunId("run-a"))
        s.finish(repo, 9, head1, PrReviewState.POSTED, PrReviewVerdict.CONCERNS, 99)
        assertEquals(1, s.reviewsOfPr(repo, 9))
    }
}
