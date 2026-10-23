package net.kigawa.kivra.selfdev

/**
 * The self-development orders' environment settings (ADR 0015 stage 1, Issue #273). Fail-closed, like [AutopilotSettings]:
 *
 * - The orders exist only with the autopilot (their cycles are goals of the autopilot's Planner and Dispatcher). While the autopilot is off,
 *   **no order variable is read** (not even the switch, which the default-off wiring must not read) and the orders are off.
 * - `KIVRA_SELFDEV_ORDERS_ENABLED` is off unless it is exactly `true`. A value other than `true` or `false` is a startup failure.
 * - `KIVRA_SELFDEV_ROOT_APPROVER` is required: the owner key of the one person who may approve a stop of the root order (kigawa). It is not a
 *   secret, but it is a subject identifier, so it is never logged. Its value is never in a failure message either.
 * - The AI principal (`KIVRA_SELFDEV_AI_PRINCIPAL`) is not an order setting. It is read by [aiPrincipalFromEnv] whatever the order switch says
 *   (while the autopilot is on). While the orders are on, it must differ from the root approver.
 */
class OrderSettings private constructor(
    val enabled: Boolean,
    /** Present only when [enabled]. */
    val rootApprover: String?,
    /**
     * The deepest level a child order may have (ADR 0015 section 2.7). 0 (the default, and the rollback of stage 2) means no child can be
     * created. Present only when [enabled].
     */
    val maxDepth: Int = 0,
    /** The most children one parent may have. Present only when [enabled]. */
    val maxChildren: Int = DEFAULT_MAX_CHILDREN,
    /** The most orders one tree may have, the root included. Present only when [enabled]. */
    val maxTree: Int = DEFAULT_MAX_TREE,
    /**
     * The parent's automatic stop of a child whose cycles failed twice in a row (ADR 0015 section 4.4). Off by default. Present only when
     * [enabled].
     */
    val autoStopEnabled: Boolean = false,
) {
    override fun toString(): String = "OrderSettings(enabled=$enabled)"

    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_ORDERS_ENABLED"
        const val ENV_ROOT_APPROVER = "KIVRA_SELFDEV_ROOT_APPROVER"
        const val ENV_AI_PRINCIPAL = "KIVRA_SELFDEV_AI_PRINCIPAL"
        const val ENV_MAX_DEPTH = "KIVRA_SELFDEV_ORDER_MAX_DEPTH"
        const val ENV_MAX_CHILDREN = "KIVRA_SELFDEV_ORDER_MAX_CHILDREN"
        const val ENV_MAX_TREE = "KIVRA_SELFDEV_ORDER_MAX_TREE"
        const val ENV_AUTO_STOP = "KIVRA_SELFDEV_ORDER_AUTO_STOP_ENABLED"
        const val DEFAULT_MAX_CHILDREN = 5
        const val DEFAULT_MAX_TREE = 15

        /** The settings when the feature is off. Reads nothing but [ENV_ENABLED]. */
        fun disabled(): OrderSettings = OrderSettings(enabled = false, rootApprover = null)

        /**
         * Reads the order settings. [autopilot] is the already-parsed autopilot settings. Reads no other variable when off.
         * [aiPrincipal] is the value [aiPrincipalFromEnv] already read: it is compared with the root approver only while the orders are on.
         */
        fun fromEnv(env: Map<String, String>, autopilot: AutopilotSettings, aiPrincipal: String?): OrderSettings {
            if (!autopilot.enabled) return disabled()
            when (env[ENV_ENABLED]?.trim()) {
                null, "", "false" -> return disabled()
                "true" -> Unit
                else -> throw IllegalStateException("$ENV_ENABLED must be true or false")
            }
            val approver = env[ENV_ROOT_APPROVER]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("$ENV_ENABLED=true requires $ENV_ROOT_APPROVER")
            check(isOwnerKeyShape(approver)) { "$ENV_ROOT_APPROVER must be an owner key (<length>:<issuer>:<subject>)" }
            if (aiPrincipal != null) {
                check(aiPrincipal != approver) { "$ENV_AI_PRINCIPAL must not be the root approver ($ENV_ROOT_APPROVER)" }
            }
            // Read only while the orders are on. A bad value fails the startup, and the message never shows the value.
            return OrderSettings(
                enabled = true,
                rootApprover = approver,
                maxDepth = boundedInt(env, ENV_MAX_DEPTH, default = 0, range = 0..2),
                maxChildren = boundedInt(env, ENV_MAX_CHILDREN, default = DEFAULT_MAX_CHILDREN, range = 1..20),
                maxTree = boundedInt(env, ENV_MAX_TREE, default = DEFAULT_MAX_TREE, range = 1..100),
                autoStopEnabled = boolFlag(env, ENV_AUTO_STOP, default = false),
            )
        }
    }
}

/** An integer setting with a range. Unset or blank is [default]. A value out of range or not an integer is a startup failure. */
private fun boundedInt(env: Map<String, String>, name: String, default: Int, range: IntRange): Int {
    val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
    val value = raw.toIntOrNull()
    check(value != null && value in range) { "$name must be an integer from ${range.first} to ${range.last}" }
    return value
}

/**
 * A switch that is exactly `true` or `false`; unset is [default]. Any other value is a startup failure, and the message never shows the value.
 */
private fun boolFlag(env: Map<String, String>, name: String, default: Boolean): Boolean = when (env[name]?.trim()) {
    null -> default
    "true" -> true
    "false" -> false
    else -> throw IllegalStateException("$name must be true or false")
}

/** An owner key is `<issuer byte length>:<issuer>:<subject>` (see `oidcOwnerKey`). */
private val OWNER_KEY_SHAPE = Regex("[0-9]{1,4}:.+:.+")
private const val MAX_OWNER_KEY_LENGTH = 255

private fun isOwnerKeyShape(value: String): Boolean =
    value.length <= MAX_OWNER_KEY_LENGTH && OWNER_KEY_SHAPE.matches(value) && value.none { it.isISOControl() }

/**
 * The AI principal's owner key (`KIVRA_SELFDEV_AI_PRINCIPAL`, ADR 0015 section 4.6), or null when it is not set. It is read on its own, so it
 * holds whether or not the orders are on: the Goal routes and the Goal MCP tools refuse that caller even in an order-off environment (#311).
 * Like the order switch, it is read only while the autopilot is on (the Goal surfaces answer 503 or are not registered without it, so
 * nothing is lost); with the autopilot off nothing is read. A set value must be an owner key; a bad value is a startup failure, and the
 * value is never in the failure message.
 */
fun aiPrincipalFromEnv(env: Map<String, String>, autopilot: AutopilotSettings): String? {
    if (!autopilot.enabled) return null
    val ai =env[OrderSettings.ENV_AI_PRINCIPAL]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    check(isOwnerKeyShape(ai)) { "${OrderSettings.ENV_AI_PRINCIPAL} must be an owner key (<length>:<issuer>:<subject>)" }
    return ai
}
