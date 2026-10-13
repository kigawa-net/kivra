package net.kigawa.kivra

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import net.kigawa.kivra.auth.resolveTaskApiAuthMode
import net.kigawa.kivra.config.KivraConfig
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.NoPrMergeAuthority
import net.kigawa.kivra.db.OutboxRepository
import net.kigawa.kivra.db.connectDatabase
import net.kigawa.kivra.db.createDataSource
import net.kigawa.kivra.db.runMigrations
import net.kigawa.kivra.observability.MetricsRegistry
import net.kigawa.kivra.routes.configureDevPreviewRoutes
import net.kigawa.kivra.routes.configureDevTaskSubmitRoutes
import net.kigawa.kivra.routes.configureHealthRoutes
import net.kigawa.kivra.routes.isDevPreviewEnabled
import net.kigawa.kivra.routes.configureObservabilityRoutes
import net.kigawa.kivra.routes.configureTaskApiRoutes

fun Application.module() = appModule(System.getenv())

/** [env] is injected for tests. The auth mode is resolved before any database work, so a bad setting fails the startup. */
internal fun Application.appModule(env: Map<String, String>) {
    val devPreviewEnabled = isDevPreviewEnabled(env)
    val taskApiAuthMode = resolveTaskApiAuthMode(env)
    val config = KivraConfig.from(environment.config)
    val dataSource = createDataSource(config.database)
    runMigrations(dataSource)
    val database = connectDatabase(dataSource)
    val runs = DevelopmentRunRepository(database, OutboxRepository(database), AuditRepository(), NoPrMergeAuthority)

    install(ContentNegotiation) { json() }
    val metrics = MetricsRegistry()
    routing {
        configureHealthRoutes()
        if (devPreviewEnabled) {
            configureDevPreviewRoutes()
            configureDevTaskSubmitRoutes()
        }
        if (taskApiAuthMode != null) configureTaskApiRoutes(runs, taskApiAuthMode)
        configureObservabilityRoutes(metrics, config.metrics)
    }
}
