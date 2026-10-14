package net.kigawa.kivra.config

import io.ktor.server.config.MapApplicationConfig
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BudgetConfigTest {
    @Test
    fun `defaults are a 10,000 JPY monthly hard cap with an 80 percent soft limit`() {
        val c = BudgetConfig.from(MapApplicationConfig())
        assertEquals("default", c.tenantId)
        assertEquals(0, BigDecimal("10000").compareTo(c.monthlyLimitJpy))
        val limit = c.tenantLimit()
        assertEquals("JPY", limit.currency)
        assertEquals(0, BigDecimal("8000").compareTo(limit.soft))
    }

    @Test
    fun `values are read from the kivra budget keys`() {
        val c = BudgetConfig.from(
            MapApplicationConfig(
                "kivra.budget.tenantId" to "acme",
                "kivra.budget.monthlyLimitJpy" to "5000",
                "kivra.budget.softRatio" to "0.5",
            ),
        )
        assertEquals("acme", c.tenantId)
        assertEquals(0, BigDecimal("2500").compareTo(c.tenantLimit().soft))
    }

    @Test
    fun `invalid values are refused at startup`() {
        assertFailsWith<IllegalArgumentException> { BudgetConfig.from(MapApplicationConfig("kivra.budget.monthlyLimitJpy" to "0")) }
        assertFailsWith<IllegalArgumentException> { BudgetConfig.from(MapApplicationConfig("kivra.budget.softRatio" to "1.5")) }
    }
}
