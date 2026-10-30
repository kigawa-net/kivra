package net.kigawa.kivra.selfdev

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelRegistry

/** NewTask placeholder for PlanValidator, removed from SelfDevPlanRepository in V40. */
data class NewTask(
    val title: String,
    val body: String?,
    val targetPaths: List<String>,
    val sizeEstimate: String,
    val dependsOn: List<Int>,
    val requiresApproval: Boolean,
    val initialState: TaskState = TaskState.PENDING_APPROVAL,
)

/**
 * Temporarily disabled pending Task model migration (V40).
 */
class AutopilotPlanner(
    goals: Any,
    gateway: ModelGateway,
    config: PlannerConfig,
    repoFiles: Any?,
    memory: Any,
    suppress: Any,
)

data class PlannerConfig(
    val budgetScopes: List<String>,
    val autopilot: AutopilotConfig,
)

class PlannerWorker(
    goals: Any,
    planner: AutopilotPlanner,
    interval: Duration,
) {
    fun start(scope: CoroutineScope): Job = scope.launch { }
}
