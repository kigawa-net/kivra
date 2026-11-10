package net.kigawa.kivra.selfdev

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb

/**
 * The startup step of ADR 0015 U-14 (addendum 2026-11-10): the hold is the default, and `KIVRA_SELFDEV_ORDER_AUTO_RESUME_ON_STARTUP=true`
 * leaves the ACTIVE orders ACTIVE. The database is the real one (TestDb).
 */
class OrderStartupHoldTest {

    private val approver = "1:i:kigawa"

    private fun settings(autoResume: String?): OrderSettings {
        val env = mutableMapOf(
            OrderSettings.ENV_ENABLED to "true",
            OrderSettings.ENV_ROOT_APPROVER to approver,
        )
        autoResume?.let { env[OrderSettings.ENV_AUTO_RESUME_ON_STARTUP] = it }
        return OrderSettings.fromEnv(env, AutopilotSettings.fromEnv(autopilotEnv(), selfDev()), null)
    }

    private fun newActiveOrder(db: TestDb, orders: SelfDevOrderRepository): String {
        val project = ProjectRepository(db.database, Clock.systemUTC()).create("Startup hold", null).projectId
        val created = orders.createRoot("operator", "Order title", "Improve the docs", "kigawa-net/kivra", 1, project)
        return (created as CreateRootResult.Created).order.orderId
    }

    @Test
    fun `by default the startup holds every ACTIVE order with STARTUP_HOLD`() = TestDb().use { db ->
        val orders = SelfDevOrderRepository(db.database)
        val id = newActiveOrder(db, orders)
        assertEquals(1, settings(autoResume = null).startupHold(orders))
        val held = assertNotNull(orders.get(id))
        assertEquals(OrderState.PAUSED, held.state)
        assertEquals(OrderReason.STARTUP_HOLD, held.stateReason)
    }

    @Test
    fun `an explicit false holds the ACTIVE orders as well`() = TestDb().use { db ->
        val orders = SelfDevOrderRepository(db.database)
        val id = newActiveOrder(db, orders)
        assertEquals(1, settings(autoResume = "false").startupHold(orders))
        assertEquals(OrderState.PAUSED, assertNotNull(orders.get(id)).state)
    }

    @Test
    fun `with the auto-resume on the startup leaves the ACTIVE orders ACTIVE`() = TestDb().use { db ->
        val orders = SelfDevOrderRepository(db.database)
        val id = newActiveOrder(db, orders)
        assertEquals(0, settings(autoResume = "true").startupHold(orders))
        val kept = assertNotNull(orders.get(id))
        assertEquals(OrderState.ACTIVE, kept.state)
        assertNull(kept.stateReason)
    }

    @Test
    fun `with the orders off there is no repository and nothing is held`() {
        assertEquals(0, settings(autoResume = null).startupHold(null))
    }

    private fun autopilotEnv(): Map<String, String> = mapOf(
        AutopilotSettings.ENV_ENABLED to "true",
        "KIVRA_AGENT_API_ENABLED" to "true",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
    )

    private fun selfDev(): SelfDevSettings = SelfDevSettings.fromEnv(
        mapOf(
            "KIVRA_SELFDEV_ENABLED" to "true",
            "KIVRA_AUTH_MODE" to "oidc-jwt",
            "KIVRA_SELFDEV_GITHUB_TOKEN" to "test-token-not-used",
            "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "test-key-not-used",
            "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "1",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_RUNTIME" to "cli",
            "KIVRA_AGENT_API_ENABLED" to "true",
        ),
    )
}
