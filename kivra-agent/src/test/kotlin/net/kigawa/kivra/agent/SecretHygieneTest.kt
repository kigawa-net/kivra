package net.kigawa.kivra.agent

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.slf4j.Logger as SlfLogger
import org.slf4j.LoggerFactory

/** The client secret and the bearer token must not appear in any log line or in toString (design #187 section 4). */
class SecretHygieneTest {
    private val secretValue = "CLIENT-SECRET-VALUE-9f3a"
    private val tokenValue = "ACCESS-TOKEN-VALUE-77bc"

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(SlfLogger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.context = root.loggerContext; it.start(); root.addAppender(it) }
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list
    }

    /** Runs the loop for a while against a kivra that fails, with a token endpoint answering [tokenStatus]. */
    private fun runLoop(tokenStatus: Int) = runTest {
        val dir = Files.createTempDirectory("kivra-agent-hygiene")
        val secretFile = dir.resolve("secret").writeSecret(secretValue)
        val recorded = synchronizedList()
        val http = fakeKivraHttp(recorded, nowMillis = { testScheduler.currentTime }) { rec ->
            if (rec.path.endsWith("/token")) {
                if (tokenStatus == 200) Canned(200, """{"access_token":"$tokenValue","expires_in":300}""") else Canned(tokenStatus)
            } else {
                Canned(500, "server detail $tokenValue")
            }
        }
        val client = KivraAgentClient(http, KIVRA_BASE, KeycloakTokenSource(http, TOKEN_URL, "kivra-agent-test", secretFile))
        val loop = AgentLoop(AgentConfig.fromEnv(testConfigEnv(dir, secretFile, poll = 1_000)), client)
        val job = launch { loop.run() }
        advanceTimeBy(20_000)
        job.cancelAndJoin()
        assertTrue(recorded.isNotEmpty(), "the loop must have made requests (a token request, then claims when the token works)")
    }

    @Test
    fun secretAndTokenNeverReachLogs() {
        val events = captureLogs {
            runLoop(tokenStatus = 200)
            runLoop(tokenStatus = 401)
        }
        assertTrue(events.isNotEmpty(), "the loop logs its failures")
        for (event in events) {
            val text = event.formattedMessage + event.throwableProxy?.message.orEmpty()
            assertFalse(text.contains(secretValue), "secret in a log line")
            assertFalse(text.contains(tokenValue), "token in a log line")
        }
    }

    @Test
    fun toStringOfSecretBearingValuesShowsNoValue() {
        val dir = Files.createTempDirectory("kivra-agent-tostring")
        val secretFile = dir.resolve("secret").writeSecret(secretValue)
        val config = AgentConfig.fromEnv(testConfigEnv(dir, secretFile))
        assertFalse(config.toString().contains(secretFile.toString()))
        assertFalse(AccessToken(tokenValue).toString().contains(tokenValue))
    }
}
