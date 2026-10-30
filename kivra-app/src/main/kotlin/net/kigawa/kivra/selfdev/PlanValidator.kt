package net.kigawa.kivra.selfdev

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.PatchPolicyConfig
import net.kigawa.kivra.core.patch.PatchPolicyDefaults
// NewTask removed in V40; PlanValidator temporarily disabled

/*
 * Output schema and checks of the autopilot Planner (ADR 0011 section 3). Pure functions, no I/O, so every rule is testable without a
 * model. The model's text is data: it is parsed strictly, checked, and only then turned into [NewTask]s. Nothing in it is executed.
 *
 * The schema is closed. Unknown fields, wrong types, and missing fields are refused by the decoder. The model cannot send
 * `requiresApproval`: that flag is computed here, from the paths and the approval setting, and is never read from the model.
 *
 * Reasons are a fixed enum. They never carry the text, a path, or an exception message.
 */

/** Output of the Planner: one JSON object. Strict: unknown keys are an error (see [PlanValidator.JSON]). */
@Serializable
internal data class PlanDto(
    val summary: String,
    val tasks: List<PlanTaskDto>,
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
    SIZE_ESTIMATE,
    DEPENDENCY,
}

sealed interface PlanCheck {
    data class Valid(val summary: String?, val tasks: List<NewTask>) : PlanCheck
    /** [path] is set only for [PlanRejectReason.PATH_NOT_ALLOWED]: the model's own text, for the retry note only (never logged). */
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
     * True when the Planner may name [path] in a task: the same rule as [validate] (the allowlist, and not denylisted). Used to choose the
     * repository files the Planner is shown, so a listed path is always one the validator accepts. It never accepts a path by itself.
     */
    internal fun allowsPlannedPath(path: String): Boolean = policy.allowsPath(path)

    /**
     * Parses and checks the model's [text]. Returns [PlanCheck.Valid] with the tasks to store, or the first reason it fails.
     * The dependency rule (`dependsOn` names only earlier tasks) also rules out cycles, so no separate cycle check is needed.
     */
    fun validate(text: String, config: AutopilotConfig): PlanCheck {
        val dto = try {
            JSON.decodeFromString(PlanDto.serializer(), text)
        } catch (e: SerializationException) {
            return PlanCheck.Invalid(PlanRejectReason.SCHEMA)
        } catch (e: IllegalArgumentException) {
            return PlanCheck.Invalid(PlanRejectReason.SCHEMA)
        }

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
            }
            // Stage 2 (ADR 0011 section 6.1): AUTO only, S, at most 3 paths, all in the narrow allowlist, none denylisted.
            // The model's own size estimate is trusted only for this choice; the real diff is re-checked later (section 6.2).
            val autoEligible = config.approval == AutopilotApproval.AUTO &&
                !denylisted &&
                raw.sizeEstimate == "S" &&
                raw.targetPaths.size <= AUTO_MAX_PATHS &&
                raw.targetPaths.all { autoPolicy.allowsPath(it) }
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
        return PlanCheck.Valid(summary.ifEmpty { null }, tasks)
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
