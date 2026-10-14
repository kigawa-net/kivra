package net.kigawa.kivra.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseConfigTest {
    private val base = arrayOf(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:cfg;MODE=MySQL",
        "kivra.database.user" to "app_user",
        "kivra.database.password" to "app-secret-value",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )

    @Test
    fun `migration credentials are unset by default and the app credentials are used`() {
        val config = KivraConfig.from(MapApplicationConfig(*base)).database
        assertNull(config.migrationUser)
        assertNull(config.migrationPassword)
        assertEquals("app_user", config.user)
    }

    @Test
    fun `both migration credentials set are accepted`() {
        val config = KivraConfig.from(
            MapApplicationConfig(*base, "kivra.database.migrationUser" to "mig_user", "kivra.database.migrationPassword" to "mig-secret-value"),
        ).database
        assertEquals("mig_user", config.migrationUser)
        assertEquals("mig-secret-value", config.migrationPassword)
    }

    @Test
    fun `blank migration credentials count as unset`() {
        val config = KivraConfig.from(
            MapApplicationConfig(*base, "kivra.database.migrationUser" to "  ", "kivra.database.migrationPassword" to ""),
        ).database
        assertNull(config.migrationUser)
        assertNull(config.migrationPassword)
    }

    @Test
    fun `setting only the migration user fails at startup`() {
        val cfg = MapApplicationConfig(*base, "kivra.database.migrationUser" to "mig_user")
        val error = assertFailsWith<IllegalArgumentException> { KivraConfig.from(cfg) }
        assertTrue(error.message!!.contains("KIVRA_DB_MIGRATION_PASSWORD"))
    }

    @Test
    fun `setting only the migration password fails at startup and the message has no password`() {
        val cfg = MapApplicationConfig(*base, "kivra.database.migrationPassword" to "mig-secret-value")
        val error = assertFailsWith<IllegalArgumentException> { KivraConfig.from(cfg) }
        val message = error.message!!
        assertTrue(message.contains("KIVRA_DB_MIGRATION_USER"))
        assertFalse(message.contains("mig-secret-value"))
        assertFalse(message.contains("app-secret-value"))
    }

    @Test
    fun `toString does not reveal any password`() {
        val config = DatabaseConfig("jdbc:h2:mem:x", "app_user", "app-secret-value", "mig_user", "mig-secret-value")
        val text = config.toString()
        assertFalse(text.contains("app-secret-value"))
        assertFalse(text.contains("mig-secret-value"))
    }
}
