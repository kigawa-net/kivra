package net.kigawa.kivra.selfdev

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.PatchPolicyConfig
import net.kigawa.kivra.core.patch.PatchPolicyDefaults
import net.kigawa.kivra.db.PlanningOrder
import net.kigawa.kivra.routes.MAX_POLICY_CODE_POINTS
import net.kigawa.kivra.routes.MAX_TITLE_CODE_POINTS
import net.kigawa.kivra.routes.validatedPolicy
import net.kigawa.kivra.routes.validatedText

/*
 * Output schema and checks of the autopilot Planner (ADR 0011 section 3). Pure functions, no I/O, so every rule is testable without a
 * model. The model's text is data: it is parsed strictly, checked, and only then turned into [NewTask]s. Nothing in it is executed.
 *
 * The schema is closed. Unknown fields, wrong types, and missing fields are refused by the decoder. The model cannot send
 * `requiresApproval`: that flag is computed here, from the paths and the approval setting, and is never read from the model.
 *
 * `childOrders` (ADR 0015 addendum 2026-11-22) is a proposal only: it is stored for an operator and creates no order here. It is accepted
 * only when [childOrderRulesFor] says the plan's order may have children; otherwise a non-empty `childOrders` is refused.
 *
 * Reasons are a fixed enum. They never carry the text, a path, or an exception message.
 */

/** Output of the Planner: one JSON object. Strict: unknown keys are an error (see [PlanValidator.JSON]). */
@Serializable
internal data class PlanDto(
    val summary: String,
    val tasks: List<PlanTaskDto>,
    /** Optional: a missing key means no child orders. Refused when the order may not propose children ([PlanRejectReason.CHILD_ORDERS_NOT_ALLOWED]). */
    val childOrders: List<ChildOrderDto> = emptyList(),
)

/** A proposed child order. Same rules as the order routes' title and policy, and the chat's cycle cap (see [PlanValidator.validate]). */
@Serializable
internal data class ChildOrderDto(
    val title: String,
    val instruction: String,
    /** Read as raw JSON, as [PlanTaskDto.dependsOn]: a plain integer only, never a quoted number. */
    val maxCycles: JsonElement,
)

@Serializable
internal data class PlanTaskDto(
    val title: String,
    val body: String,
    val targetPaths: List<String>,
    val sizeEstimate: String,
    /** Read as raw JSON and checked element by element: a decoder may accept a quoted number as an Int, which this schema does not. */
    val dependsOn: JsonArray,
)

/** Why a Plan was refused. Fixed codes. */
enum class PlanRejectReason {
    SCHEMA,
    SUMMARY_TOO_LONG,
    TASK_COUNT,
    TITLE,
    BODY,
    PATH_COUNT,
    PATH_SHAPE,
    PATH_NOT_ALLOWED,
    /** A target path is an existing file larger than [PatchPolicyDefaults.MAX_FILE_BYTES] (ADR 0011 section 3, 追補 2026-11-19). */
    FILE_TOO_LARGE,
    SIZE_ESTIMATE,
    DEPENDENCY,
    /** The plan has child orders, and the order may not propose any (a child order, a depth with no room, or no order). */
    CHILD_ORDERS_NOT_ALLOWED,
    CHILD_ORDER_COUNT,
    CHILD_ORDER_TITLE,
    CHILD_ORDER_INSTRUCTION,
    CHILD_ORDER_MAX_CYCLES,
    CHILD_ORDER_DUPLICATE_TITLE,
}

/** A child order a plan proposes: stored as a proposal, never created here. [ordinal] is 1-based within the plan. */
data class NewChildOrder(val ordinal: Int, val title: String, val instruction: String, val maxCycles: Int)

/**
 * What the plan's order may propose (ADR 0015 addendum 2026-11-22). Present only when a child order may be proposed; null otherwise.
 * [maxCyclesCap] is the chat's and the REST route's cap (`KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`).
 */
data class ChildOrderRules(val maxCyclesCap: Int) {
    init {
        require(maxCyclesCap >= 1) { "maxCyclesCap must be at least 1" }
    }
}

/**
 * The child-order rules of a plan's order. Only a root order proposes: a child's plan never proposes grandchildren, so the recursion stops
 * at the first level (ADR 0015 addendum 2026-11-22). A root may propose only while a child fits under [maxDepth] (the root's depth is 0).
 * With no order (a task the operator submitted), or [maxDepth] 0 (children disabled), there are no rules.
 */
internal fun childOrderRulesFor(order: PlanningOrder?, maxDepth: Int, maxCyclesCap: Int): ChildOrderRules? {
    if (order == null || order.parentOrderId != null) return null
    if (order.depth + 1 > maxDepth) return null
    return ChildOrderRules(maxCyclesCap)
}

sealed interface PlanCheck {
    /** [childOrders] is empty unless the plan's order may propose children (see [ChildOrderRules]). */
    data class Valid(val summary: String?, val tasks: List<NewTask>, val childOrders: List<NewChildOrder> = emptyList()) : PlanCheck
    /**
     * [path] is set only for [PlanRejectReason.PATH_NOT_ALLOWED] and [PlanRejectReason.FILE_TOO_LARGE]: the model's own text, for the retry
     * note only (never logged).
     */
    data class Invalid(val reason: PlanRejectReason, val path: String? = null) : PlanCheck
}

/** Paths that make a task need the operator's approval even under AUTO (ADR 0011 section 4), beyond the PatchPolicy denylist. */
internal val PLANNER_EXTRA_DENIED_PATTERNS: List<String> = listOf(
    "**/db/migration/**",
    "**/auth/**",
    ".kivra/**",
)

/** The auto-submittable paths of ADR 0011 section 6.1 (stage 2): docs, test sources and the README only. */
internal val AUTO_ALLOWED_PATH_PATTERNS: List<String> = listOf(
    "docs/**",
    "kivra-*/src/test/**",
    "README.md",
)

object PlanValidator {
    const val MAX_TASKS = 8
    const val MAX_TITLE_CHARS = 200
    const val MAX_BODY_CHARS = 8_000
    const val MAX_PATHS_PER_TASK = 10
    const val MAX_SUMMARY_CHARS = 500
    /** The most child orders one plan may propose (ADR 0015 addendum 2026-11-22). The tree limits (MAX_CHILDREN, MAX_TREE) still apply at creation. */
    const val MAX_CHILD_ORDERS = 3
    private const val AUTO_MAX_PATHS = 3

    /** Strict decoding: an unknown key, a wrong type, or a missing field fails the decode. Numbers are never coerced from strings. */
    private val JSON = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    /** Path rules: the PatchPolicy allowlist and denylist, plus the extra denylist of ADR 0011 section 4. */
    private val policy = PatchPolicy(
        PatchPolicyConfig(
            allowedPathPatterns = PatchPolicyDefaults.ALLOWED_PATH_PATTERNS,
            deniedPathPatterns = PatchPolicyDefaults.DENIED_PATH_PATTERNS + PLANNER_EXTRA_DENIED_PATTERNS,
        ),
    )

    /** The narrower allowlist of stage 2. The denylist is the same, so a denylisted path is never auto-submittable. */
    private val autoPolicy = PatchPolicy(
        PatchPolicyConfig(
            allowedPathPatterns = AUTO_ALLOWED_PATH_PATTERNS,
            deniedPathPatterns = PatchPolicyDefaults.DENIED_PATH_PATTERNS + PLANNER_EXTRA_DENIED_PATTERNS,
        ),
    )

    /**
     * True when [path] is in the stage-2 scope (ADR 0011 sections 4 and 6.1): allowed by the narrow allowlist and not denylisted. The
     * re-check of the actual change set (section 6.2) uses this same rule, so the plan and the diff are judged alike.
     */
    internal fun isAutoScopePath(path: String): Boolean = autoPolicy.allowsPath(path)

    /**
     * True when [path] is in the stage-3 scope (ADR 0011 section 6.1): the PatchPolicy allowlist and not denylisted, with the stage 2
     * narrowing (docs, test sources, README only) lifted. The re-check of the actual change set under `full` uses this rule.
     */
    internal fun isFullScopePath(path: String): Boolean = policy.allowsPath(path)

    /**
     * True when [path] is on the denylist (the PatchPolicy denylist and the extra denylist of ADR 0011 section 4). The same under every mode.
     */
    internal fun isDenylistedPath(path: String): Boolean = autoPolicy.deniesPath(path)

    /**
     * True when the Planner may name [path] in a task: the same rule as [validate] (the allowlist, and not denylisted). Used to choose the
     * repository files the Planner is shown, so a listed path is always one the validator accepts. It never accepts a path by itself.
     */
    internal fun allowsPlannedPath(path: String): Boolean = policy.allowsPath(path)

    /**
     * True when [path] is an existing file of the base tree that is larger than [PatchPolicyDefaults.MAX_FILE_BYTES]. [fileSizes] maps the
     * regular files of the base tree to their sizes. A path that is not in the map is new, so it is never too large. Null [fileSizes] (the
     * tree could not be read) gives false: the check is not made, and the write-time PatchPolicy still is (ADR 0011 section 3).
     */
    internal fun isTooLargeToChange(path: String, fileSizes: Map<String, Long>?): Boolean =
        fileSizes != null && (fileSizes[path] ?: 0L) > PatchPolicyDefaults.MAX_FILE_BYTES

    /**
     * Parses and checks the model's [text]. Returns [PlanCheck.Valid] with the tasks to store, or the first reason it fails.
     * The dependency rule (`dependsOn` names only earlier tasks) also rules out cycles, so no separate cycle check is needed.
     * [fileSizes] (see [isTooLargeToChange]) is the base tree's file sizes, or null when they are not known.
     * [childOrders] (see [childOrderRulesFor]) is null when the plan's order may not propose children: then any `childOrders` is refused.
     *
     * A plan needs at least one task, also when it has child orders. A plan with no task would be settled with nothing to run, and the
     * proposals would be the whole plan; that is not allowed before the operator's approval path exists (PR2).
     */
    fun validate(
        text: String,
        config: AutopilotConfig,
        fileSizes: Map<String, Long>? = null,
        childOrders: ChildOrderRules? = null,
    ): PlanCheck {
        val dto = try {
            JSON.decodeFromString(PlanDto.serializer(), text)
        } catch (e: SerializationException) {
            return PlanCheck.Invalid(PlanRejectReason.SCHEMA)
        } catch (e: IllegalArgumentException) {
            return PlanCheck.Invalid(PlanRejectReason.SCHEMA)
        }

        if (dto.childOrders.isNotEmpty() && childOrders == null) return PlanCheck.Invalid(PlanRejectReason.CHILD_ORDERS_NOT_ALLOWED)
        val summary = singleLine(dto.summary)
        if (summary.codePointCount(0, summary.length) > MAX_SUMMARY_CHARS) return PlanCheck.Invalid(PlanRejectReason.SUMMARY_TOO_LONG)
        if (dto.tasks.isEmpty() || dto.tasks.size > MAX_TASKS) return PlanCheck.Invalid(PlanRejectReason.TASK_COUNT)

        val tasks = ArrayList<NewTask>(dto.tasks.size)
        for ((index, raw) in dto.tasks.withIndex()) {
            val ordinal = index + 1
            val title = singleLine(raw.title)
            if (title.isEmpty() || title.codePointCount(0, title.length) > MAX_TITLE_CHARS) return PlanCheck.Invalid(PlanRejectReason.TITLE)
            val body = bodyText(raw.body)
            if (body.isEmpty() || body.codePointCount(0, body.length) > MAX_BODY_CHARS) return PlanCheck.Invalid(PlanRejectReason.BODY)
            if (raw.targetPaths.isEmpty() || raw.targetPaths.size > MAX_PATHS_PER_TASK) {
                return PlanCheck.Invalid(PlanRejectReason.PATH_COUNT)
            }
            if (raw.sizeEstimate !in SIZES) return PlanCheck.Invalid(PlanRejectReason.SIZE_ESTIMATE)
            val dependsOn = raw.dependsOn.map { integerOf(it) ?: return PlanCheck.Invalid(PlanRejectReason.SCHEMA) }
            if (dependsOn.any { it !in 1 until ordinal } || dependsOn.distinct().size != dependsOn.size) {
                return PlanCheck.Invalid(PlanRejectReason.DEPENDENCY)
            }

            var denylisted = false
            for (path in raw.targetPaths) {
                if (!policy.hasValidPathShape(path)) return PlanCheck.Invalid(PlanRejectReason.PATH_SHAPE)
                if (policy.deniesPath(path)) {
                    denylisted = true
                } else if (!policy.allowsPath(path)) {
                    return PlanCheck.Invalid(PlanRejectReason.PATH_NOT_ALLOWED, path)
                }
                // Checked for a denylisted path too: such a task waits for approval, and its write would be refused the same way.
                if (isTooLargeToChange(path, fileSizes)) return PlanCheck.Invalid(PlanRejectReason.FILE_TOO_LARGE, path)
            }
            // Stage 2 (ADR 0011 section 6.1): AUTO only, S, at most 3 paths, all in the narrow allowlist, none denylisted.
            // Stage 3 (section 6.1): FULL only, none denylisted, at most 3 paths. The size estimate and the narrow allowlist are not
            // required; the 3-path cap stays as a guard (section 5.2). The model's own estimate is trusted only for this choice;
            // the real diff is re-checked later (section 6.2).
            val autoEligible = when (config.approval) {
                AutopilotApproval.REQUIRED -> false
                AutopilotApproval.AUTO -> !denylisted &&
                    raw.sizeEstimate == "S" &&
                    raw.targetPaths.size <= AUTO_MAX_PATHS &&
                    raw.targetPaths.all { autoPolicy.allowsPath(it) }
                AutopilotApproval.FULL -> !denylisted && raw.targetPaths.size <= AUTO_MAX_PATHS
            }
            val requiresApproval = config.requiresApproval(denylisted) || !autoEligible
            tasks += NewTask(
                title = title,
                body = body,
                targetPaths = raw.targetPaths,
                sizeEstimate = raw.sizeEstimate,
                dependsOn = dependsOn,
                requiresApproval = requiresApproval,
                initialState = if (autoEligible) TaskState.QUEUED else TaskState.PENDING_APPROVAL,
            )
        }
        val children = when (val checked = childOrdersOf(dto.childOrders, childOrders)) {
            is ChildOrdersCheck.Refused -> return PlanCheck.Invalid(checked.reason)
            is ChildOrdersCheck.Ok -> checked.orders
        }
        return PlanCheck.Valid(summary.ifEmpty { null }, tasks, children)
    }

    private sealed interface ChildOrdersCheck {
        data class Ok(val orders: List<NewChildOrder>) : ChildOrdersCheck
        data class Refused(val reason: PlanRejectReason) : ChildOrdersCheck
    }

    /**
     * The proposed child orders, checked with the rules the order routes and the chat apply when they create one: the title (1 to
     * [MAX_TITLE_CODE_POINTS] code points, no control characters), the policy ([validatedPolicy]), and maxCycles within the cap. Titles are
     * unique within the plan. Nothing is created here.
     */
    private fun childOrdersOf(raw: List<ChildOrderDto>, rules: ChildOrderRules?): ChildOrdersCheck {
        if (raw.size > MAX_CHILD_ORDERS) return ChildOrdersCheck.Refused(PlanRejectReason.CHILD_ORDER_COUNT)
        val titles = HashSet<String>()
        val orders = ArrayList<NewChildOrder>(raw.size)
        for ((index, child) in raw.withIndex()) {
            val title = validatedText(child.title, MAX_TITLE_CODE_POINTS)
                ?: return ChildOrdersCheck.Refused(PlanRejectReason.CHILD_ORDER_TITLE)
            if (!titles.add(title)) return ChildOrdersCheck.Refused(PlanRejectReason.CHILD_ORDER_DUPLICATE_TITLE)
            val instruction = validatedPolicy(child.instruction)
                ?: return ChildOrdersCheck.Refused(PlanRejectReason.CHILD_ORDER_INSTRUCTION)
            // Not a plain integer is a schema error, as for dependsOn; an integer outside the cap is CHILD_ORDER_MAX_CYCLES.
            val maxCycles = integerOf(child.maxCycles) ?: return ChildOrdersCheck.Refused(PlanRejectReason.SCHEMA)
            if (rules == null || maxCycles !in 1..rules.maxCyclesCap) {
                return ChildOrdersCheck.Refused(PlanRejectReason.CHILD_ORDER_MAX_CYCLES)
            }
            orders += NewChildOrder(ordinal = index + 1, title = title, instruction = instruction, maxCycles = maxCycles)
        }
        return ChildOrdersCheck.Ok(orders)
    }

    private val SIZES = setOf("S", "M", "L")
    private val INTEGER = Regex("-?[0-9]{1,9}")

    /** A JSON number written as a plain integer, and nothing else: no quotes, no fraction, no exponent, no literal. */
    private fun integerOf(element: kotlinx.serialization.json.JsonElement): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || !INTEGER.matches(primitive.content)) return null
        return primitive.content.toInt()
    }

    /** A title or summary: no line breaks, and every other control character becomes a space. */
    internal fun singleLine(text: String): String = buildString {
        for (cp in text.codePoints().toArray()) {
            if (Character.getType(cp) == Character.CONTROL.toInt()) append(' ') else appendCodePoint(cp)
        }
    }.trim()

    /** A task body keeps line breaks and tabs. Other control characters are removed. */
    internal fun bodyText(text: String): String = buildString {
        for (cp in text.codePoints().toArray()) {
            if (Character.getType(cp) != Character.CONTROL.toInt() || cp == '\n'.code || cp == '\t'.code) appendCodePoint(cp)
        }
    }.trim()
}
