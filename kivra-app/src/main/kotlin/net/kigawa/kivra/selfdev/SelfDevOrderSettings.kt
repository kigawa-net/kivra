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
) {
    override fun toString(): String = "OrderSettings(enabled=$enabled)"

    companion object {
        const val ENV_ENABLED = "KIVRA_SELFDEV_ORDERS_ENABLED"
        const val ENV_ROOT_APPROVER = "KIVRA_SELFDEV_ROOT_APPROVER"
        const val ENV_AI_PRINCIPAL = "KIVRA_SELFDEV_AI_PRINCIPAL"

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
            return OrderSettings(enabled = true, rootApprover = approver)
        }
    }
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
