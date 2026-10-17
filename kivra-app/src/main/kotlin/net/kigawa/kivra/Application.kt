package net.kigawa.kivra

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import net.kigawa.kivra.auth.AgentApiSettings
import net.kigawa.kivra.auth.AgentIdentity
import net.kigawa.kivra.auth.RefreshingJwkProvider
import net.kigawa.kivra.auth.resolveTaskApiIdentity
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.buildCodingCostLedger
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.SqlLeaseStore
import net.kigawa.kivra.config.BudgetConfig
import net.kigawa.kivra.config.KivraConfig
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.NoPrMergeAuthority
import net.kigawa.kivra.db.OutboxRepository
import net.kigawa.kivra.db.connectDatabase
import net.kigawa.kivra.db.createDataSource
import net.kigawa.kivra.db.migrateDatabase
import net.kigawa.kivra.observability.MetricsRegistry
import net.kigawa.kivra.routes.AgentApiDeps
import net.kigawa.kivra.routes.configureAgentApiRoutes
import net.kigawa.kivra.routes.configureBudgetAdminRoutes
import net.kigawa.kivra.routes.configureDevPreviewRoutes
import net.kigawa.kivra.routes.configureDevTaskSubmitRoutes
import net.kigawa.kivra.routes.ReadinessChecker
import net.kigawa.kivra.routes.configureHealthRoutes
import net.kigawa.kivra.routes.configureReadyRoutes
import net.kigawa.kivra.routes.isDevPreviewEnabled
import net.kigawa.kivra.routes.configureObservabilityRoutes
import net.kigawa.kivra.routes.configureTaskApiRoutes
import net.kigawa.kivra.routes.configureOperationApiRoutes
import net.kigawa.kivra.routes.readOnlyOperationJournal
import net.kigawa.kivra.routes.configureUiRoutes
import net.kigawa.kivra.routes.configureRunViewRoutes
import net.kigawa.kivra.routes.configureSelfDevAdminRoutes
import net.kigawa.kivra.routes.configureSelfDevRoutes
import net.kigawa.kivra.selfdev.CodingAttemptPatchSource
import net.kigawa.kivra.selfdev.DeferredPrMergeAuthority
import net.kigawa.kivra.selfdev.SelfDevSettings
import net.kigawa.kivra.selfdev.buildSelfDev

fun Application.module() = appModule(System.getenv())

/** [env] is injected for tests. The auth mode is resolved before any database work, so a bad setting fails the startup. */
internal fun Application.appModule(env: Map<String, String>) {
    val devPreviewEnabled = isDevPreviewEnabled(env)
    val taskApiIdentity = resolveTaskApiIdentity(env)
    // Fail-closed: when the agent API is off, no agent variable beyond the switch is read (PR 4a).
    val agentApiSettings = AgentApiSettings.fromEnv(env)
    // Fail-closed: when self-development is off, no self-development variable beyond the switch is read (ADR 0008 part 7).
    val selfDevSettings = SelfDevSettings.fromEnv(env)
    val config = KivraConfig.from(environment.config)
    val dataSource = createDataSource(config.database)
    migrateDatabase(config.database, dataSource)
    val database = connectDatabase(dataSource)
    // Self-development installs the GitHub merge Authority later (in buildSelfDev, before the worker starts). Without it, nothing is verified.
    val deferredPrMerge = DeferredPrMergeAuthority()
    val audit = AuditRepository()
    val runs = DevelopmentRunRepository(
        database,
        OutboxRepository(database),
        audit,
        if (selfDevSettings.enabled) deferredPrMerge else NoPrMergeAuthority,
    )
    val budgetConfig = BudgetConfig.from(environment.config)
    val budgetStore = DbBudgetStore(database, budgetConfig.tenantId, budgetConfig.tenantLimit())
    // Built only when the agent API is enabled. Nothing about the coding runtime is created otherwise. It is shared by the agent API
    // routes and, with KIVRA_SELFDEV_RUNTIME=cli, by the self-development runner (ADR 0010).
    val codingService = agentApiSettings.auth?.let {
        CodingRuntimeService(
            attempts = CodingAttemptRepository(database, audit = audit),
            runs = DbCodingRunPort(runs, SqlLeaseStore(database)),
            results = DbCodingResultStore(database),
            tasks = CodingTaskSource { run -> run.taskTitle },
            cost = buildCodingCostLedger(
                env, agentApiSettings.attemptReserveUsd, budgetStore, budgetConfig.tenantId, database,
            ),
        )
    }
    // Built only when self-development is enabled: this is where the GitHub and model clients and their secrets come into play.
    val selfDevParts = if (selfDevSettings.enabled) {
        buildSelfDev(
            selfDevSettings, env, database, runs, budgetStore, budgetConfig.tenantId,
            requireNotNull(taskApiIdentity) { "self-development requires KIVRA_AUTH_MODE=oidc-jwt" },
            deferredPrMerge,
            coding = codingService?.let { CodingAttemptPatchSource(it) },
        )
    } else {
        null
    }
    selfDevParts?.worker?.start(this)
    val agentParts = agentApiSettings.auth?.let { auth ->
        AgentApiDeps(checkNotNull(codingService), AgentIdentity(auth, RefreshingJwkProvider(auth.jwksUrl)))
    }

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
            configureTaskApiRoutes(runs, taskApiIdentity, selfDevSettings.config?.repo)
            configureOperationApiRoutes(runs, readOnlyOperationJournal(database), taskApiIdentity)
            // The UI calls the Task API, so it is served only when the Task API is enabled.
            configureUiRoutes()
            configureBudgetAdminRoutes(budgetStore, taskApiIdentity)
            configureSelfDevAdminRoutes(runs, taskApiIdentity)
            configureRunViewRoutes(runs, taskApiIdentity, selfDevSettings)
        }
        if (selfDevParts != null) configureSelfDevRoutes(selfDevParts.routes)
        if (agentParts != null) configureAgentApiRoutes(agentParts)
        configureObservabilityRoutes(metrics, config.metrics)
    }
}
