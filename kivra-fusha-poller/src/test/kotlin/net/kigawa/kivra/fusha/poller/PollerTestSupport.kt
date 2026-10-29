package net.kigawa.kivra.fusha.poller

import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.TaskCapability
import net.kigawa.kivra.core.TaskErrorKind
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskPatch
import net.kigawa.kivra.core.TaskProvider
import net.kigawa.kivra.core.TaskProviderException
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.core.TaskWriteResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Scripted in-memory provider. The test controls both what is stored and what each call returns or throws. */
internal class ScriptedProvider : TaskProvider {
    override val capabilities: Set<TaskCapability> = setOf(
        TaskCapability.GET, TaskCapability.LIST, TaskCapability.UPDATE, TaskCapability.COMMENT,
    )

    /** Source of truth: tasks present here are visible in list and get; removing one simulates delete or invisibility. */
    val tasks = LinkedHashMap<TaskId, TaskSnapshot>()

    var listError: TaskProviderException? = null
    var getError: TaskProviderException? = null

    /** When set, replaces the listing entirely (duplicates, stale data, invalid ids). */
    var listOverride: ((TaskFilter) -> List<TaskSnapshot>)? = null

    /** When set, replaces GET entirely; returning null means "not found or not visible". */
    var getOverride: ((TaskId) -> TaskSnapshot?)? = null

    var listCalls = 0
    var getCalls = 0

    fun put(task: TaskSnapshot) {
        tasks[task.id] = task
    }

    override suspend fun get(id: TaskId): TaskSnapshot? {
        getCalls++
        getError?.let { throw it }
        getOverride?.let { return it(id) }
        return tasks[id]
    }

    override suspend fun list(filter: TaskFilter): List<TaskSnapshot> {
        listCalls++
        listError?.let { throw it }
        listOverride?.let { return it(filter) }
        return tasks.values.filter { filter.assigneeId == null || it.assigneeId == filter.assigneeId }
    }

    override suspend fun update(id: TaskId, patch: TaskPatch, expectedUpdatedAt: Instant?): TaskWriteResult =
        TaskWriteResult.Unsupported(TaskCapability.UPDATE, "scripted provider is read-only")

    override suspend fun comment(id: TaskId, body: String, key: IdempotencyKey): TaskWriteResult =
        TaskWriteResult.Unsupported(TaskCapability.COMMENT, "scripted provider is read-only")
}

internal class RecordingSink : TaskEventSink {
    val batches = mutableListOf<List<TaskPollEvent>>()
    var failNext = false

    val all: List<TaskPollEvent> get() = batches.flatten()

    override suspend fun publish(events: List<TaskPollEvent>) {
        if (failNext) {
            failNext = false
            error("sink unavailable")
        }
        batches += events
    }
}

internal class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

internal const val T0 = "2026-10-12T00:00:00.000Z"
internal const val SCOPE_ASSIGNEE = "kigawa"

internal fun task(
    n: Long,
    assignee: String = SCOPE_ASSIGNEE,
    updatedAt: String = T0,
    title: String = "task $n",
    fields: Map<String, String> = emptyMap(),
    environment: String = "dev",
    nextTaskIds: List<TaskId> = emptyList(),
    fetchedAt: Instant = Instant.EPOCH,
): TaskSnapshot = TaskSnapshot(
    id = TaskId("fusha:$environment:$n"),
    title = title,
    description = null,
    assigneeId = assignee,
    dueDate = null,
    fields = fields,
    nextTaskIds = nextTaskIds,
    relatedTaskIds = emptyList(),
    updatedAt = Instant.parse(updatedAt),
    fetchedAt = fetchedAt,
)

internal fun config(
    fullRescanEveryPolls: Int = 1000,
    threshold: Int = 3,
    cooldown: Duration = Duration.ofSeconds(60),
) = TaskPollerConfig(
    environment = "dev",
    filter = TaskFilter(assigneeId = SCOPE_ASSIGNEE),
    interval = Duration.ofSeconds(30),
    jitter = Duration.ZERO,
    fullRescanEveryPolls = fullRescanEveryPolls,
    breakerFailureThreshold = threshold,
    breakerCooldown = cooldown,
)

internal fun TaskPollEvent.reasonOrNull(): ChangeReason? = (this as? TaskChanged)?.reason
