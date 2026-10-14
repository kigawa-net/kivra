package net.kigawa.kivra.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.config.DatabaseConfig
import net.kigawa.kivra.appModule
import net.kigawa.kivra.db.closeDataSource
import net.kigawa.kivra.db.createDataSource
import org.slf4j.LoggerFactory

/**
 * Delegates everything to [delegate] except [getConnection], which [onConnection] decides. [onConnection] receives the
 * delegate, so it can open a real connection without calling back into this class.
 */
private class ScriptedDataSource(
    private val delegate: DataSource,
    private val onConnection: (DataSource) -> Connection,
) : DataSource by delegate {
    override fun getConnection(): Connection = onConnection(delegate)
}

class ReadyRoutesTest {
    private val clock = AtomicLong(0L)
    private val pools = mutableListOf<HikariDataSource>()
    private val hung = CountDownLatch(1)
    private lateinit var h2: DataSource

    @BeforeTest
    fun setUp() {
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:kivra_ready_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            user = "sa",
            password = "",
        )
        h2 = createDataSource(config).also { pools += it as HikariDataSource }
    }

    @AfterTest
    fun tearDown() {
        hung.countDown()
        pools.forEach { closeDataSource(it) }
    }

    private fun checker(
        dataSource: DataSource = h2,
        timeoutMillis: Long = READY_TIMEOUT_MILLIS,
        cacheMillis: Long = READY_CACHE_MILLIS,
    ) = ReadinessChecker(
        dataSource = dataSource,
        nanoTime = { clock.get() },
        timeoutMillis = timeoutMillis,
        cacheMillis = cacheMillis,
    )

    private fun withRoutes(readiness: ReadinessChecker, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    configureHealthRoutes()
                    configureReadyRoutes(readiness)
                }
            }
            block()
        }

    private val nanoMillis = 1_000_000L

    @Test
    fun `ready returns 200 when the database answers`() = withRoutes(checker()) {
        val response = client.get("/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ready"}""", response.bodyAsText())
    }

    @Test
    fun `ready returns 503 when the database is closed, and health stays 200`() {
        val pool = createDataSource(
            DatabaseConfig("jdbc:h2:mem:kivra_ready_closed_${System.nanoTime()};DB_CLOSE_DELAY=-1", "sa", ""),
        ) as HikariDataSource
        closeDataSource(pool)
        withRoutes(checker(dataSource = pool)) {
            val ready = client.get("/ready")
            assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
            assertEquals("""{"status":"not_ready"}""", ready.bodyAsText())

            val health = client.get("/health")
            assertEquals(HttpStatusCode.OK, health.status)
            assertEquals("""{"status":"ok"}""", health.bodyAsText())
        }
    }

    @Test
    fun `the response and the log never contain the JDBC URL, user, password or exception message`() {
        val user = "kivra-app-user-xyz"
        val password = "hunter2-secret-pw"
        val jdbcUrl = "jdbc:h2:mem:kivra_leak_probe_db;DB_CLOSE_DELAY=-1"
        val failing = ScriptedDataSource(h2) {
            throw SQLException("connect failed for $jdbcUrl user=$user password=$password")
        }
        val logger = LoggerFactory.getLogger(ReadinessChecker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.INFO
        logger.addAppender(appender)
        try {
            withRoutes(checker(dataSource = failing)) {
                val body = client.get("/ready").bodyAsText()
                assertFalse(body.contains(jdbcUrl))
                assertFalse(body.contains(user))
                assertFalse(body.contains(password))
            }
            val logged = appender.list.joinToString("\n") {
                it.formattedMessage + " " + (it.throwableProxy?.message ?: "")
            }
            assertTrue(logged.contains("reason=SQLException"), logged)
            assertTrue(appender.list.all { it.level == Level.WARN }, "failure is logged at WARN")
            assertTrue(appender.list.all { it.throwableProxy == null }, "no stack trace")
            assertFalse(logged.contains(jdbcUrl), "JDBC URL must not be logged")
            assertFalse(logged.contains(user), "user name must not be logged")
            assertFalse(logged.contains(password), "password must not be logged")
            assertFalse(logged.contains("connect failed"), "exception message must not be logged")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `a failure is logged at most once per minute`() {
        val failing = ScriptedDataSource(h2) { throw SQLException("boom") }
        val logger = LoggerFactory.getLogger(ReadinessChecker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.INFO
        logger.addAppender(appender)
        try {
            // cache 0 ms: every call probes the database again
            val readiness = checker(dataSource = failing, cacheMillis = 0L)
            runBlocking {
                repeat(20) { assertFalse(readiness.isReady()) }
            }
            assertEquals(1, appender.list.size, "flood of the same failure collapses into one line")
            clock.addAndGet(60_000L * nanoMillis)
            runBlocking { assertFalse(readiness.isReady()) }
            assertEquals(2, appender.list.size, "logged again after a minute")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `calls within 3 seconds share one database check, and the check runs again after the cache expires`() {
        val calls = AtomicInteger()
        val counting = ScriptedDataSource(h2) { ds -> calls.incrementAndGet(); ds.connection }
        val readiness = checker(dataSource = counting)
        runBlocking {
            repeat(5) { assertTrue(readiness.isReady()) }
        }
        assertEquals(1, calls.get())

        clock.addAndGet(2_999L * nanoMillis)
        runBlocking { assertTrue(readiness.isReady()) }
        assertEquals(1, calls.get(), "still cached at 2.999 s")

        clock.addAndGet(1L * nanoMillis)
        runBlocking { assertTrue(readiness.isReady()) }
        assertEquals(2, calls.get(), "re-checked once 3 s have passed")
    }

    @Test
    fun `concurrent callers are bundled into one database check`() {
        val calls = AtomicInteger()
        val counting = ScriptedDataSource(h2) { ds ->
            calls.incrementAndGet()
            Thread.sleep(100)
            ds.connection
        }
        val readiness = checker(dataSource = counting)
        val results = runBlocking {
            (1..50).map { async(Dispatchers.Default) { readiness.isReady() } }.awaitAll()
        }
        assertTrue(results.all { it })
        assertEquals(1, calls.get(), "50 simultaneous probes must hit the database once")
    }

    @Test
    fun `a database that never answers yields 503 within the timeout`() {
        val hangs = ScriptedDataSource(h2) {
            hung.await(10, TimeUnit.SECONDS)
            throw SQLException("released")
        }
        assertEquals(2_000L, READY_TIMEOUT_MILLIS)
        val readiness = checker(dataSource = hangs, timeoutMillis = 200L)
        withRoutes(readiness) {
            val started = System.nanoTime()
            val response = client.get("/ready")
            val elapsedMillis = (System.nanoTime() - started) / nanoMillis
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("""{"status":"not_ready"}""", response.bodyAsText())
            assertTrue(elapsedMillis < 2_000L, "answered in ${elapsedMillis}ms, not waiting for the hung call")
        }
    }

    @Test
    fun `the app boots without KIVRA_AUTH_MODE and serves ready and health`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_ready_boot_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "kivra.database.user" to "sa",
                "kivra.database.password" to "",
                "kivra.lease.ttlSeconds" to "30",
                "kivra.lease.graceSeconds" to "5",
            )
        }
        application { appModule(emptyMap()) }

        val ready = client.get("/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("""{"status":"ready"}""", ready.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }
}
