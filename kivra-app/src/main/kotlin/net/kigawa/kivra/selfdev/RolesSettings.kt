package net.kigawa.kivra.selfdev

import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiAuthMode
import net.kigawa.kivra.auth.resolveTaskApiAuthMode

/**
 * The roles settings. Fail-closed, like [OrderSettings]: while the orders are off no roles variable is read, and the mode is off.
 * A bad value is a startup failure whose message names the variable only.
 */
class RolesSettings private constructor(val mode: PermissionMode, val default: RolesDefault, val recoverAdmin: Boolean = false) {
    override fun toString(): String = "RolesSettings(mode=$mode)"

    fun service(orders: OrderSettings, aiPrincipal: String?, assignees: AssigneeLookup?): PermissionService =
        PermissionService(mode, default, aiPrincipal, orders.rootApprover, assignees)

    companion object {
        const val ENV_MODE = "KIVRA_SELFDEV_ROLES_MODE"
        const val ENV_DEFAULT = "KIVRA_SELFDEV_ROLES_DEFAULT"
        const val ENV_RECOVER_ADMIN = "KIVRA_SELFDEV_ROLES_RECOVER_ADMIN"
        const val ENV_ALLOW_INSECURE_AUTH = "KIVRA_SELFDEV_ROLES_ALLOW_INSECURE_AUTH"

        /** For tests of the startup wiring, which need a mode without the environment. */
        internal fun of(mode: PermissionMode, recoverAdmin: Boolean = false): RolesSettings =
            RolesSettings(mode, RolesDefault.OPERATOR, recoverAdmin)

        fun disabled(): RolesSettings = RolesSettings(PermissionMode.OFF, RolesDefault.OPERATOR)

        fun fromEnv(env: Map<String, String>, orders: OrderSettings, aiPrincipal: String?): RolesSettings {
            if (!orders.enabled) return disabled()
            val mode = when (env[ENV_MODE]?.trim()) {
                null, "", "off" -> PermissionMode.OFF
                "shadow" -> PermissionMode.SHADOW
                "enforce" -> PermissionMode.ENFORCE
                else -> throw IllegalStateException("$ENV_MODE must be off, shadow or enforce")
            }
            if (mode == PermissionMode.OFF) return disabled()
            val default = when (env[ENV_DEFAULT]?.trim()) {
                null, "", "operator" -> RolesDefault.OPERATOR
                "none" -> RolesDefault.NONE
                else -> throw IllegalStateException("$ENV_DEFAULT must be operator or none")
            }
            if (aiPrincipal != null) {
                check(aiPrincipal != orders.rootApprover) {
                    "${OrderSettings.ENV_AI_PRINCIPAL} must not be the root approver (${OrderSettings.ENV_ROOT_APPROVER})"
                }
            }
            val authMode = resolveTaskApiAuthMode(env)
            if (authMode == TaskApiAuthMode.NONE || authMode == TaskApiAuthMode.OAUTH2_PROXY) {
                check(boolFlag(env, ENV_ALLOW_INSECURE_AUTH, default = false)) {
                    "$ENV_MODE other than off needs KIVRA_AUTH_MODE=oidc-jwt (or $ENV_ALLOW_INSECURE_AUTH=true for development)"
                }
            }
            // Read only while the mode is not off (the early return above).
            val recover = boolFlag(env, ENV_RECOVER_ADMIN, default = false)
            return RolesSettings(mode, default, recover)
        }
    }
}
