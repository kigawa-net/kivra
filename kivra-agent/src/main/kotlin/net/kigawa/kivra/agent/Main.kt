package net.kigawa.kivra.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kigawa.kivra.agent.docker.DockerSpecException
import net.kigawa.kivra.agent.workdir.WorkDirException
import org.slf4j.LoggerFactory

private const val SHUTDOWN_GRACE_SECONDS = 15L
private const val EXIT_CONFIG = 2
private const val EXIT_ALREADY_RUNNING = 3
private const val EXIT_EXECUTOR_CONFIG = 2

fun main() {
    val logger = LoggerFactory.getLogger("net.kigawa.kivra.agent.Main")
    // The messages here name a variable or a fixed condition, never a value.
    val config = try {
        AgentConfig.fromEnv(System.getenv())
    } catch (e: ConfigException) {
        System.err.println("kivra-agent: ${e.message}")
        exitProcess(EXIT_CONFIG)
    }
    try {
        StartupChecks.checkWorkDir(config.workDir)
        StartupChecks.checkSecretFile(config.clientSecretFile)
    } catch (e: ConfigException) {
        System.err.println("kivra-agent: ${e.message}")
        exitProcess(EXIT_CONFIG)
    }
    val lock = try {
        WorkDirLock.acquire(config.workDir)
    } catch (e: AlreadyRunningException) {
        System.err.println("kivra-agent: ${e.message}")
        exitProcess(EXIT_ALREADY_RUNNING)
    } catch (e: IOException) {
        System.err.println("kivra-agent: the lock file could not be opened")
        exitProcess(EXIT_CONFIG)
    }
    lock.use {
        HttpClient(CIO) { agentClientConfig() }.use { http ->
            val tokens = KeycloakTokenSource(http, config.tokenUrl, config.clientId, config.clientSecretFile)
            val client = KivraAgentClient(http, config.kivraUrl, tokens)
            val executor = executorFor(config, client)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val job = scope.launch { AgentLoop(config, client, executor).run() }
            val done = CountDownLatch(1)
            job.invokeOnCompletion { done.countDown() }
            // SIGTERM: cancel the loop, which reports CANCELLED for a running attempt. Wait for that, within a bounded time.
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    job.cancel()
                    done.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
                },
            )
            logger.info("kivra-agent started")
            done.await()
            logger.info("kivra-agent stopped")
        }
    }
}

/**
 * The executor for this run. The stub unless `KIVRA_AGENT_EXECUTOR=real`. The real executor checks its docker and work root settings
 * when it is built, so an invalid setting ends the start here (exit 2) and no attempt is claimed. The message names no value.
 */
private fun executorFor(config: AgentConfig, client: KivraAgentClient): AttemptExecutor {
    val real = config.realExecutor ?: return NotImplementedExecutor
    return try {
        RealAttemptExecutor(client, config.workDir, config.stopFile, real)
    } catch (e: DockerSpecException) {
        System.err.println("kivra-agent: the ${AgentConfig.ENV_EXECUTOR}=real settings are invalid")
        exitProcess(EXIT_EXECUTOR_CONFIG)
    } catch (e: WorkDirException) {
        System.err.println("kivra-agent: the ${AgentConfig.ENV_EXECUTOR}=real work directory is invalid")
        exitProcess(EXIT_EXECUTOR_CONFIG)
    }
}
