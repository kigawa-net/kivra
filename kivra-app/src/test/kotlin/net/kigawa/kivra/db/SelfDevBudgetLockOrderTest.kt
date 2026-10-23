package net.kigawa.kivra.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lock order of the cycle cost (ADR 0015 addendum 2026-10-23, section C.2): order rows first (from the root down), then the tenant
 * budget rows. The order is safe only while the budget store never touches an order row, and the cost reader takes no lock and writes
 * nothing. These tests read the production sources, as SelfDevMemoryArchitectureTest does.
 */
class SelfDevBudgetLockOrderTest {

    private val dbDir = File("src/main/kotlin/net/kigawa/kivra/db")

    @Test
    fun `the budget store never names an order table, so it never takes an order row lock`() {
        assertTrue(dbDir.isDirectory, "run the test from the kivra-app module directory: ${dbDir.absolutePath}")
        val text = File(dbDir, "DbBudgetStore.kt").readText()
        assertFalse(text.contains("selfdev_order"), "DbBudgetStore must not reference selfdev_orders or the tables that hang off it")
        assertFalse(text.contains("SelfDevOrderRepository"), "DbBudgetStore must not call the order repository")
    }

    @Test
    fun `the cycle cost reader writes no row and takes no lock`() {
        val text = File(dbDir, "SelfDevCycleSpendReader.kt").readText()
        val writesOrLocks = Regex("(?i)\\b(INSERT|UPDATE|DELETE)\\b|FOR\\s+UPDATE|LOCK\\s+IN\\s+SHARE\\s+MODE")
        assertFalse(writesOrLocks.containsMatchIn(text), "the reader is read-only: no write statement and no row lock")
    }
}
