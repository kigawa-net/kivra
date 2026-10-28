package net.kigawa.kivra

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.routing.routing
import net.kigawa.kivra.auth.AgentApiSettings
import net.kigawa.kivra.auth.AgentIdentity
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.RefreshingJwkProvider
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.auth.resolveOperatorVerifier
import net.kigawa.kivra.auth.resolveTaskApiIdentity
import net.kigawa.kivra.coding.CodingReconcileWorker
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.buildCodingCostLedger
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.db.AssigneeStoreLookup
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingMonitorEventRepository
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.SqlLeaseStore
import net.kigawa.kivra.config.BudgetConfig
import net.kigawa.kivra.config.KivraConfig
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevAssigneeTokenRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.NoPrMergeAuthority
import net.kigawa.kivra.db.OutboxRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.connectDatabase
import net.kigawa.kivra.db.createDataSource
import net.kigawa.kivra.db.migrateDatabase
import net.kigawa.kivra.fx.FxExpiryWatcher
import net.kigawa.kivra.fx.fxSettingsForWarning
import net.kigawa.kivra.observability.MetricsRegistry
import net.kigawa.kivra.routes.AgentApiDeps
import net.kigawa.kivra.routes.configureAgentApiRoutes
import net.kigawa.kivra.routes.configureRunExecutionRoutes
import net.kigawa.kivra.routes.runExecutionDeps
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
import net.kigawa.kivra.routes.configureProjectRoutes
import net.kigawa.kivra.routes.configureRunViewRoutes
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.SelfDevGoalRouteDeps
import net.kigawa.kivra.routes.configureSelfDevAdminRoutes
import net.kigawa.kivra.routes.SelfDevMemoryRouteDeps
import net.kigawa.kivra.routes.configureSelfDevGoalRoutes
import net.kigawa.kivra.routes.configureSelfDevMemoryRoutes
import net.kigawa.kivra.routes.SelfDevAssigneeRouteDeps
import net.kigawa.kivra.routes.configureSelfDevAssigneeRoutes
import net.kigawa.kivra.routes.SelfDevOrderRouteDeps
import net.kigawa.kivra.routes.configureSelfDevOrderRoutes
import net.kigawa.kivra.routes.requiredAgentRole
import net.kigawa.kivra.routes.configureSelfDevRoutes
import net.kigawa.kivra.routes.configureOidcSessionRoutes
import net.kigawa.kivra.selfdev.AutoStopDeps
import net.kigawa.kivra.selfdev.AutopilotDispatcher
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.OrderWatch
import net.kigawa.kivra.selfdev.RolesSettings
import net.kigawa.kivra.selfdev.bootstrapRolesIfEnabled
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.CodingAttemptPatchSource
import net.kigawa.kivra.selfdev.DeferredPrMergeAuthority
import net.kigawa.kivra.selfdev.SelfDevSettings
import net.kigawa.kivra.selfdev.aiPrincipalFromEnv
import net.kigawa.kivra.selfdev.autopilotBudgetAllows
import net.kigawa.kivra.selfdev.buildSelfDev
import net.kigawa.kivra.selfdev.llmwiring.buildLlmStopJudge
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRetentionWorker
import net.kigawa.kivra.selfdev.memory.SelfDevMemorySettings
import net.kigawa.kivra.selfdev.memory.SuppressShadow
import net.kigawa.kivra.selfdev.memory.SuppressShadowSettings
import net.kigawa.kivra.mcp.McpSettings
import net.kigawa.kivra.mcp.McpGoalDeps
import net.kigawa.kivra.mcp.McpGoalSubmitDeps
import net.kigawa.kivra.mcp.McpOrderDeps
import net.kigawa.kivra.mcp.McpTokenAuth
import net.kigawa.kivra.mcp.buildMcpRouteDeps
import net.kigawa.kivra.mcp.registerMcpRoutes
import net.kigawa.kivra.auth.OidcSessionSettings
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.auth.withSessionCookie
import net.kigawa.kivra.db.DbSessionStore
import net.kigawa.kivra.routes.LoginSession

fun Application.module() = appModule(System.getenv())

/** [env] is injected for tests. The auth mode is resolved before any database work, so a bad setting fails the startup. */
internal fun Application.appModule(env: Map<String, String>) {
    val devPreviewEnabled = isDevPreviewEnabled(env)
    val taskApiIdentity = resolveTaskApiIdentity(env)
    // Fail-closed: when the agent API is off, no agent variable beyond the switch is read (PR 4a).
    val agentApiSettings = AgentApiSettings.fromEnv(env)
    // Fail-closed: when self-development is off, no self-development variable beyond the switch is read (ADR 0008 part 7).
    val selfDevSettings = SelfDevSettings.fromEnv(env)
    // Fail-closed: when the autopilot is off, nothing beyond its switch is read and nothing is wired (ADR 0011).
    val autopilotSettings = AutopilotSettings.fromEnv(env, selfDevSettings)
    // Fail-closed: the self-development memory's switch is read only while the autopilot is on (ADR 0016 P1). A bad value fails the startup.
    val memorySettings = SelfDevMemorySettings.fromStartupEnv(env, autopilotSettings)
    // The suppression shadow (ADR 0016 P3) is read only while the memory is on. It counts and never acts.
    val suppressShadowSettings = SuppressShadowSettings.fromStartupEnv(env, memorySettings)
    // The AI principal (ADR 0015 section 4.6) is read whatever the order switch says, so the Goal refusals hold with the orders off (#311).
    // It is read only while the autopilot is on, as the orders are.
    val aiPrincipal = aiPrincipalFromEnv(env, autopilotSettings)
    // Fail-closed: when the orders are off (the default), nothing beyond the switch is read and nothing is wired (ADR 0015 stage 1).
    val orderSettings = OrderSettings.fromEnv(env, autopilotSettings, aiPrincipal)
    // Fail-closed: when the MCP endpoint is off (the default), nothing beyond its switch is read and no /mcp route is registered (ADR 0013).
    val mcpSettings = McpSettings.fromEnv(env)
    val config = KivraConfig.from(environment.config)
    val dataSource = createDataSource(config.database)
    migrateDatabase(config.database, dataSource)
    val database = connectDatabase(dataSource)
    // The order repository exists only when the orders are on (ADR 0015 stage 1). It is not touched otherwise.
    val orderRepo = if (orderSettings.enabled) SelfDevOrderRepository(database) else null
    // The memory repository and its recorder exist only when the memory switch is on. Otherwise nothing is written, read or started.
    val memoryRepo = if (memorySettings.enabled) SelfDevMemoryRepository(database, enabled = true) else null
    val memory = memoryRepo?.let { SelfDevMemoryRecorder.of(it) } ?: SelfDevMemoryRecorder.OFF
    // The suppression shadow exists only with the memory and its own switch on. It writes the counts of what would be refused, nothing else.
    val suppressShadow = memoryRepo?.takeIf { suppressShadowSettings.enabled }?.let { SuppressShadow.of(it) } ?: SuppressShadow.OFF
    // Self-development installs the GitHub merge Authority later (in buildSelfDev, before the worker starts). Without it, nothing is verified.
    val deferredPrMerge = DeferredPrMergeAuthority()
    val audit = AuditRepository()
    val runs = DevelopmentRunRepository(
        database,
        OutboxRepository(database),
        audit,
        if (selfDevSettings.enabled) deferredPrMerge else NoPrMergeAuthority,
    )
    val projectRepo = ProjectRepository(database, java.time.Clock.systemUTC())
    val budgetConfig = BudgetConfig.from(environment.config)
    val budgetStore = DbBudgetStore(database, budgetConfig.tenantId, budgetConfig.tenantLimit())
    // Built only when the agent API is enabled. Nothing about the coding runtime is created otherwise. It is shared by the agent API
    // routes and, with KIVRA_SELFDEV_RUNTIME=cli, by the self-development runner (ADR 0010).
    val codingResults = DbCodingResultStore(database)
    val codingService = agentApiSettings.auth?.let {
        CodingRuntimeService(
            attempts = CodingAttemptRepository(database, audit = audit),
            runs = DbCodingRunPort(runs, SqlLeaseStore(database)),
            results = codingResults,
            tasks = CodingTaskSource { run -> run.taskTitle },
            cost = buildCodingCostLedger(
                env, agentApiSettings.attemptReserveUsd, budgetStore, budgetConfig.tenantId, database,
            ),
        )
    }
    // The run-execution monitor's event log (ADR 0012). Built only with the agent API, like the coding runtime.
    val codingMonitor = codingService?.let { CodingMonitorEventRepository(database) }
    // Built only when self-development is enabled: this is where the GitHub and model clients and their secrets come into play.
    val selfDevParts = if (selfDevSettings.enabled) {
        buildSelfDev(
            selfDevSettings, env, database, runs, budgetStore, budgetConfig.tenantId,
            requireNotNull(taskApiIdentity) { "self-development requires KIVRA_AUTH_MODE=oidc-jwt" },
            deferredPrMerge,
            coding = codingService?.let { CodingAttemptPatchSource(it) },
            autopilot = autopilotSettings,
            memory = memory,
            suppress = suppressShadow,
        )
    } else {
        null
    }
    // The roles (ADR 0020, PR 3a/3b/3c): off by default, and then the assignee store is not created or read. The order routes, the goal and memory routes (3b) and the REST routes of PR 3c (Task API, selfdev, budget and run admin, run views, execution) use the guard.
    val rolesSettings = RolesSettings.fromEnv(env, orderSettings, aiPrincipal)
    val assigneeRepo = if (rolesSettings.mode == PermissionMode.OFF) null else SelfDevAssigneeRepository(database)
    // Seeding runs before the permission service is built. A failure fails the startup: with the roles on, an unseeded store can make a stop unapprovable.
    bootstrapRolesIfEnabled(rolesSettings, orderSettings.rootApprover, aiPrincipal) { assigneeRepo!! }
    val permissionGuard = PermissionGuard(
        rolesSettings.service(orderSettings, aiPrincipal, assigneeRepo?.let { AssigneeStoreLookup(it) }),
    )
    // The autopilot's operator API (ADR 0011 section 1). Registered with the Task API identity, so the OIDC operator role is required. Its
    // agent-role setting is read only while the autopilot is enabled. Every goal endpoint answers 503 while it is disabled.
    val operatorVerifier = if (taskApiIdentity != null) {
        resolveOperatorVerifier(env, if (autopilotSettings.enabled) requiredAgentRole(env) else null)
    } else {
        null
    }
    // The assignee admin API and /me (ADR 0020 addendum, PR T3). With the roles off the repositories are null: the admin routes answer 503 and read nothing.
    val assigneeRoutes = operatorVerifier?.let { verifier ->
        SelfDevAssigneeRouteDeps(
            operators = verifier,
            guard = permissionGuard,
            assignees = assigneeRepo,
            tokens = assigneeRepo?.let { SelfDevAssigneeTokenRepository(database) },
            oidcIssuer = env["KIVRA_OIDC_ISSUER"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
    val goalRoutes = operatorVerifier?.let { verifier ->
        SelfDevGoalRouteDeps(
            operators = verifier,
            autopilot = autopilotSettings,
            targetRepo = selfDevSettings.config?.repo?.let { "${it.owner}/${it.repo}" },
            goals = SelfDevGoalRepository(database),
            plans = SelfDevPlanRepository(database),
            operations = SelfDevOperatorRepository(database),
            control = SelfDevAutopilotControlRepository(database),
            memory = memory,
            aiPrincipal = aiPrincipal,
            guard = permissionGuard,
        )
    }
    // The memory's operator routes (ADR 0016 P2). Registered with the operator check; the repository is null while the memory switch is off,
    // and then every endpoint answers 503.
    val memoryRoutes = operatorVerifier?.let { verifier ->
        SelfDevMemoryRouteDeps(operators = verifier, memory = memoryRepo, aiPrincipal = aiPrincipal, guard = permissionGuard)
    }
    // The order routes (ADR 0015 stage 1). Registered only when the orders are on; the operator check is the same as the goal routes'.
    val orderRoutes = if (orderRepo != null && operatorVerifier != null) {
        SelfDevOrderRouteDeps(
            operators = operatorVerifier,
            autopilot = autopilotSettings,
            orderSettings = orderSettings,
            aiPrincipal = aiPrincipal,
            targetRepo = selfDevSettings.config?.repo?.let { "${it.owner}/${it.repo}" },
            orders = orderRepo,
            goals = SelfDevGoalRepository(database),
            operations = SelfDevOperatorRepository(database),
            guard = permissionGuard,
        )
    } else {
        null
    }
    // The Goal stop over MCP needs the autopilot (ADR 0019 S1): refused at startup otherwise, as the write switch is. The same holds for the
    // Goal submission (ADR 0019 S2).
    check(!mcpSettings.goalStopEnabled || autopilotSettings.config != null) {
        "${McpSettings.ENV_GOAL_STOP_ENABLED}=true requires the autopilot"
    }
    check(!mcpSettings.goalSubmitEnabled || autopilotSettings.config != null) {
        "${McpSettings.ENV_GOAL_SUBMIT_ENABLED}=true requires the autopilot"
    }
    val mcpDeps = buildMcpRouteDeps(
        mcpSettings, env, runs, selfDevSettings.config?.repo, guard = permissionGuard,
        // The assignee token entrance exists only while the roles are on (ADR 0020 addendum, T2); off creates and reads nothing.
        tokenAuth = if (rolesSettings.mode == PermissionMode.OFF) null else McpTokenAuth.forDatabase(database, rolesSettings.mode),
        selfDevEnabled = selfDevSettings.enabled, maxActiveSelfDev = selfDevSettings.maxActiveRuns,
        // The order tools serve only while the orders answer (the same condition as the REST routes' requireEnabled).
        orders = orderRepo?.takeIf { autopilotSettings.config != null }?.let { McpOrderDeps(it, aiPrincipal) },
        // The Goal tools serve only while the autopilot answers (the same condition as the REST goal routes' requireEnabled).
        goals = autopilotSettings.config?.let {
            McpGoalDeps(
                operations = SelfDevOperatorRepository(database),
                plans = SelfDevPlanRepository(database),
                runs = runs,
                aiPrincipal = aiPrincipal,
                memory = memory,
                // The Goal submission over MCP (ADR 0019 S2) needs the target repository, as the REST submission does.
                submit = selfDevSettings.config?.repo?.let {
                    McpGoalSubmitDeps(
                        goals = SelfDevGoalRepository(database),
                        targetRepo = "${it.owner}/${it.repo}",
                        dailyLimit = mcpSettings.goalSubmitDailyLimit,
                    )
                },
            )
        },
    )
    selfDevParts?.worker?.start(this)
    selfDevParts?.planWorker?.start(this)
    // ADR 0014 S1: the shadow judgement of the automatic merge. Started only with its switch on (the watch is null otherwise).
    selfDevParts?.autoMergeShadow?.start(this)
    // The memory's retention step (ADR 0016 section 9). Started only with the memory switch on.
    memoryRepo?.let { repo -> SelfDevMemoryRetentionWorker({ repo.purgeRetention() }, SelfDevMemoryRetentionWorker.DEFAULT_INTERVAL).start(this) }
    // ADR 0015 U-14: after the orders are enabled, every ACTIVE order starts paused (STARTUP_HOLD). Nothing runs before this.
    orderRepo?.holdActiveOnStartup()
    // ADR 0021 L4: the shadow judgement of the LLM stop approval. Built only with its flag on (null otherwise, and nothing is read or created).
    // It judges and records; it never approves (that is L5). It starts after the hold above, so a request cannot predate the order hold.
    // It needs the Anthropic credential of self-development and the orders. While either is off, its flag is not even read (the fail-closed
    // rule of those switches: no self-development variable is read while self-development is off).
    if (selfDevParts != null && orderRepo != null) {
        buildLlmStopJudge(env, database, orderRepo, selfDevParts.anthropic, rolesSettings.mode)?.start(this)
    }
    // The FX expiry warning (daily). FX is read only when a USD path is on (the agent API or self-development, which need the rate);
    // otherwise nothing is read and nothing is started.
    val fxSettings = if (agentApiSettings.auth != null || selfDevSettings.enabled) fxSettingsForWarning(env) else null
    fxSettings?.let { FxExpiryWatcher(it).start(this) }
    // Built only when the autopilot is enabled (ADR 0011 section 5). Its monthly-budget check reads the same store as the coding ledger.
    autopilotSettings.config?.let { autopilot ->
        AutopilotDispatcher(
            database = database,
            runs = runs,
            config = autopilot,
            maxActiveSelfDev = selfDevSettings.maxActiveRuns,
            budgetAllows = { share ->
                val usage = budgetStore.usage(budgetConfig.tenantId)
                autopilotBudgetAllows(usage.committed, usage.reserved, budgetConfig.monthlyLimitJpy, share)
            },
            // With the orders off the gate allows every goal, so the dispatcher's flow is the same as before.
            orderGate = orderRepo?.let { repo -> { goalId: String -> repo.dispatchAllowed(goalId) } } ?: { _: String -> true },
            memory = memory,
        ).start(this)
        // The order guards (ADR 0015 section 5.2): the same budget share as the dispatcher, and the FX rate. Only with the orders on.
        orderRepo?.let { repo ->
            val fxForGuard = fxSettingsForWarning(env)
            OrderWatch(
                orders = repo,
                budgetAllows = {
                    val usage = budgetStore.usage(budgetConfig.tenantId)
                    autopilotBudgetAllows(usage.committed, usage.reserved, budgetConfig.monthlyLimitJpy, autopilot.budgetSharePercent)
                },
                fxExpired = { fxForGuard?.expiry(java.time.Clock.systemUTC())?.expired == true },
                // The automatic child stop (ADR 0015 section 4.4) is off unless its switch is on. Its goals are stopped after the decision.
                autoStop = if (orderSettings.autoStopEnabled) {
                    AutoStopDeps(SelfDevGoalRepository(database), SelfDevOperatorRepository(database))
                } else {
                    null
                },
                // The cost counting into spent_jpy (ADR 0015 addendum, stage 3g). Off unless its switch is on.
                countSpend = orderSettings.spendEnabled,
                // The automatic cycle start (ADR 0015 addendum 2026-10-24, dev only). Off unless its switch is on.
                autoCycleCap = orderSettings.maxCyclesCap.takeIf { orderSettings.autoCycleEnabled },
            ).start(this)
        }
    }
    // The coding reconcile and the retention of stored change sets (ADR 0010 addendum 6). Only when the agent API is on.
    codingService?.let {
        CodingReconcileWorker(
            it, codingResults, agentApiSettings.reconcileInterval, agentApiSettings.resultRetention,
            monitorPurge = checkNotNull(codingMonitor),
            monitorRetention = agentApiSettings.monitorRetention,
        ).start(this)
    }
    // OIDC session persistence: set up before routing so the session-aware identity is available to all API routes.
    val oidcSessionSettings = OidcSessionSettings.fromEnv(env)
    val sessionStore: DbSessionStore? = oidcSessionSettings?.let { DbSessionStore(database) }

    // Wrap taskApiIdentity with session cookie support: a request authenticated by a valid session cookie
    // uses the session's principal; otherwise falls back to the Bearer token (or other configured mode).
    val taskApiIdentityWithSession: TaskApiIdentity? = taskApiIdentity?.let { identity ->
        if (sessionStore != null) {
            identity.withSessionCookie(oidcSessionSettings!!.sessionCookieName, sessionStore)
        } else {
            identity
        }
    }

    val agentParts = agentApiSettings.auth?.let { auth ->
        AgentApiDeps(
            checkNotNull(codingService),
            AgentIdentity(auth, RefreshingJwkProvider(auth.jwksUrl)),
            checkNotNull(codingMonitor),
        )
    }

    // The run execution read API (ADR 0012 PR 3): read only. Its operator check reuses the Task API's verifier, with the agent role refused.
    val executionDeps = taskApiIdentityWithSession?.let {
        val agentRole = agentApiSettings.auth?.let { auth -> RequiredRole(auth.requiredClient, auth.requiredRole) }
        runExecutionDeps(runs, database, it, resolveOperatorVerifier(env, agentRole), agentApiSettings.attemptReserveUsd)
    }

    install(ContentNegotiation) { json() }
    install(SSE)
    install(Authentication)

    if (oidcSessionSettings != null) {
        install(Sessions) {
            cookie<LoginSession>(oidcSessionSettings.sessionCookieName)
        }
    }
    val metrics = MetricsRegistry()
    routing {
        configureHealthRoutes()
        configureReadyRoutes(ReadinessChecker(dataSource))
        if (devPreviewEnabled) {
            configureDevPreviewRoutes()
            configureDevTaskSubmitRoutes()
        }
        if (taskApiIdentityWithSession != null) {
            configureTaskApiRoutes(runs, taskApiIdentityWithSession, selfDevSettings.config?.repo, permissionGuard, projectRepo)
            configureOperationApiRoutes(runs, readOnlyOperationJournal(database), taskApiIdentityWithSession, permissionGuard)
            configureProjectRoutes(projectRepo, taskApiIdentityWithSession)
            // The UI calls the Task API, so it is served only when the Task API is enabled.
            configureUiRoutes()
            configureBudgetAdminRoutes(budgetStore, taskApiIdentityWithSession, fx = fxSettings, operatorView = budgetStore, guard = permissionGuard)
            configureSelfDevAdminRoutes(runs, taskApiIdentityWithSession, permissionGuard)
            configureRunViewRoutes(runs, taskApiIdentityWithSession, selfDevSettings, permissionGuard, projectRepo)
            if (goalRoutes != null) configureSelfDevGoalRoutes(goalRoutes)
            if (memoryRoutes != null) configureSelfDevMemoryRoutes(memoryRoutes)
            if (orderRoutes != null) configureSelfDevOrderRoutes(orderRoutes)
            if (assigneeRoutes != null) configureSelfDevAssigneeRoutes(assigneeRoutes)
            executionDeps?.let { configureRunExecutionRoutes(it.withGuard(permissionGuard)) }
        }
        if (selfDevParts != null) configureSelfDevRoutes(selfDevParts.routes.withGuard(permissionGuard))
        registerMcpRoutes(mcpDeps)
        if (agentParts != null) configureAgentApiRoutes(agentParts)
        configureObservabilityRoutes(metrics, config.metrics)
        // OIDC login session routes (login/logout/callback)
        if (oidcSessionSettings != null && sessionStore != null) {
            configureOidcSessionRoutes(oidcSessionSettings, sessionStore)
        }
    }
}
