package net.kigawa.kivra

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import net.kigawa.kivra.auth.resolveTaskApiIdentity
import net.kigawa.kivra.config.KivraConfig
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.NoPrMergeAuthority
import net.kigawa.kivra.db.OutboxRepository
import net.kigawa.kivra.db.connectDatabase
import net.kigawa.kivra.db.createDataSource
import net.kigawa.kivra.db.migrateDatabase
import net.kigawa.kivra.observability.MetricsRegistry
import net.kigawa.kivra.routes.configureDevPreviewRoutes
import net.kigawa.kivra.routes.configureDevTaskSubmitRoutes
import net.kigawa.kivra.routes.ReadinessChecker
import net.kigawa.kivra.routes.configureHealthRoutes
import net.kigawa.kivra.routes.configureReadyRoutes
import net.kigawa.kivra.routes.isDevPreviewEnabled
import net.kigawa.kivra.routes.configureObservabilityRoutes
import net.kigawa.kivra.routes.configureTaskApiRoutes
import net.kigawa.kivra.routes.configureUiRoutes

fun Application.module() = appModule(System.getenv())

/** [env] is injected for tests. The auth mode is resolved before any database work, so a bad setting fails the startup. */
internal fun Application.appModule(env: Map<String, String>) {
    val devPreviewEnabled = isDevPreviewEnabled(env)
    val taskApiIdentity = resolveTaskApiIdentity(env)
    val config = KivraConfig.from(environment.config)
    val dataSource = createDataSource(config.database)
    migrateDatabase(config.database, dataSource)
    val database = connectDatabase(dataSource)
    val runs = DevelopmentRunRepository(database, OutboxRepository(database), AuditRepository(), NoPrMergeAuthority)

    install(ContentNegotiation) { json() }
    val metrics = MetricsRegistry()
    routing {
        configureHealthRoutes()
        configureReadyRoutes(ReadinessChecker(dataSource))
        if (devPreviewEnabled) {
            configureDevPreviewRoutes()
            configureDevTaskSubmitRoutes()
        }
        if (taskApiIdentity != null) {
            configureTaskApiRoutes(runs, taskApiIdentity)
            // The UI calls the Task API, so it is served only when the Task API is enabled.
            configureUiRoutes()
        }
        configureObservabilityRoutes(metrics, config.metrics)
    }
}
