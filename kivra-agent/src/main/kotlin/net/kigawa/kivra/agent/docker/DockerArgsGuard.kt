package net.kigawa.kivra.agent.docker

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Why an argument list was refused. The rule is the only detail: no option value, path or secret is ever in the message. */
enum class GuardRule {
    MALFORMED,
    FORBIDDEN_OPTION,
    UNKNOWN_OPTION,
    MISSING_OPTION,
    DUPLICATE_OPTION,
    FORBIDDEN_VALUE,
    PATH_REJECTED,
    ENV_REJECTED,
    /** The command after the image is not the fixed one (wrong program, wrong order, or a missing or out-of-range value). */
    COMMAND_NOT_ALLOWED,
    /** The command has an extra or changed argument that is not in the allowlist. */
    COMMAND_UNKNOWN_ARGUMENT,
    /** A token of the command holds a shell operator, an expansion, a quote or a control character. */
    COMMAND_SHELL_SYNTAX,
    /** The prompt position holds something other than the fixed prompt, which is how task text would enter the command. */
    COMMAND_TASK_TEXT,
}

class DockerArgsRejectedException(val rule: GuardRule) : IllegalArgumentException("docker argument rejected: ${rule.name}")

/**
 * The last check before a `docker run` (ADR 0010 invariant 2, design #187 section 3.3). It works on the complete argument list,
 * so a forbidden item is refused even when the builder did not add it, and even when an allowed option is given a forbidden value.
 *
 * The argument list is `run`, the options, the image, and the command ([CliCommand]). The image is the first argument that does not
 * start with `-`. Rules, in order (fail-closed; the first failure throws):
 * 1. Options: a short option (`-e`, `-v`, `-it`) is refused whatever its value. A name on the explicit forbidden list (`--privileged`,
 *    `--cap-add`, `--device`, `--pid`, `--volume`, `--env-file`, ...) is refused.
 * 2. Allowlist: any option not in [BOOL_OPTIONS] or [VALUE_OPTIONS] is refused.
 * 3. Shape: a value option must use `--name=value`; a flag must not have a value. The space form is never accepted, so a value cannot
 *    be mistaken for an option.
 * 4. Values: each option's value must match its exact allowed form. The network is one of the configured internal networks (never
 *    host, none, container:, or bridge). The mount is the one work directory. Each environment entry has an allowed name and value,
 *    and no value contains `sk-ant-`. `JAVA_TOOL_OPTIONS` has the fixed proxy form.
 * 5. Duplicates: a single-valued option may not repeat.
 * 6. Required: every option the container needs must be present (read-only, cap-drop, network, memory, mount, ...).
 * 7. Cross checks: the memory-swap equals the memory; the container name starts with the run label; the proxy URLs agree, and
 *    `JAVA_TOOL_OPTIONS` equals the form built from the proxy.
 * 8. Image: one of the configured images.
 * 9. Command: exactly [CliCommand.argv] with an in-range `--max-turns`. Checked by [checkCommand]: the program, the prompt position
 *    (must be the fixed prompt, so no task text), shell syntax in any other token, and any extra or changed argument are refused.
 */
class DockerArgsGuard(private val settings: DockerSettings) {

    private class Opt(val name: String, val value: String?)

    fun check(args: List<String>) {
        if (args.isEmpty() || args[0] != "run") reject(GuardRule.MALFORMED)
        // Every option starts with "-", so the first other argument is the image, and everything after it is the command.
        val imageIndex = (1 until args.size).firstOrNull { !args[it].startsWith("-") } ?: reject(GuardRule.MALFORMED)
        val imageName = args[imageIndex]
        val opts = ArrayList<Opt>()
        for (i in 1 until imageIndex) {
            val token = args[i]
            if (!token.startsWith("--")) reject(GuardRule.FORBIDDEN_OPTION)
            opts += parseLong(token)
        }

        // 1. Explicit forbidden names.
        for (o in opts) if (o.name in FORBIDDEN_NAMES) reject(GuardRule.FORBIDDEN_OPTION)
        // 2. Allowlist.
        for (o in opts) if (o.name !in BOOL_OPTIONS && o.name !in VALUE_OPTIONS) reject(GuardRule.UNKNOWN_OPTION)
        // 3. Shape.
        for (o in opts) {
            if (o.name in BOOL_OPTIONS && o.value != null) reject(GuardRule.MALFORMED)
            if (o.name in VALUE_OPTIONS && o.value == null) reject(GuardRule.MALFORMED)
        }
        // 4. Values.
        for (o in opts) checkValue(o)
        // 5. Duplicates.
        checkDuplicates(opts)
        // 6. Required.
        val present = opts.map { it.name }.toSet()
        for (name in REQUIRED_SINGLE) if (name !in present) reject(GuardRule.MISSING_OPTION)
        checkRequiredEntries(opts)
        // 7. Cross checks.
        checkCrossValues(opts)
        // 8. Image.
        if (imageName !in settings.allowedImages) reject(GuardRule.FORBIDDEN_VALUE)
        // 9. Command.
        checkCommand(args.subList(imageIndex + 1, args.size))
    }

    /**
     * The command must be exactly [CliCommand.argv], except for the `--max-turns` value, which must be an integer in range. The
     * prompt position may only hold the fixed prompt. Shell syntax is refused in every other token, so that a value cannot be
     * passed to a shell even if a later change ran it through one.
     */
    private fun checkCommand(tokens: List<String>) {
        if (tokens.isEmpty() || tokens[0] != CLAUDE_PROGRAM) reject(GuardRule.COMMAND_NOT_ALLOWED)
        for ((i, token) in tokens.withIndex()) {
            if (i != CliCommand.PROMPT_INDEX && (token.any { it in SHELL_CHARS || it.isISOControl() })) {
                reject(GuardRule.COMMAND_SHELL_SYNTAX)
            }
        }
        val template = CliCommand.argv(CliCommand.DEFAULT_MAX_TURNS)
        if (tokens.size != template.size) reject(GuardRule.COMMAND_UNKNOWN_ARGUMENT)
        for (i in tokens.indices) {
            if (i == CliCommand.MAX_TURNS_INDEX) {
                val turns = tokens[i].toIntOrNull()
                if (turns == null || tokens[i] != turns.toString() || turns !in 1..CliCommand.MAX_MAX_TURNS) {
                    reject(GuardRule.COMMAND_NOT_ALLOWED)
                }
                continue
            }
            if (tokens[i] == template[i]) continue
            reject(
                when (i) {
                    CliCommand.PROMPT_INDEX -> GuardRule.COMMAND_TASK_TEXT
                    else -> GuardRule.COMMAND_UNKNOWN_ARGUMENT
                },
            )
        }
    }

    private fun parseLong(token: String): Opt {
        val body = token.substring(2)
        val eq = body.indexOf('=')
        return if (eq < 0) Opt(body, null) else Opt(body.substring(0, eq), body.substring(eq + 1))
    }

    private fun checkValue(o: Opt) {
        val v = o.value ?: return
        when (o.name) {
            "name" -> if (!DockerPatterns.CONTAINER_NAME.matches(v)) reject(GuardRule.FORBIDDEN_VALUE)
            "user" -> if (v != DockerConstants.USER) reject(GuardRule.FORBIDDEN_VALUE)
            "tmpfs" -> if (v != DockerConstants.TMPFS_TMP && v != DockerConstants.TMPFS_HOME) reject(GuardRule.FORBIDDEN_VALUE)
            "cap-drop" -> if (v != "ALL") reject(GuardRule.FORBIDDEN_VALUE)
            "security-opt" -> if (v != "no-new-privileges") reject(GuardRule.FORBIDDEN_VALUE)
            "pids-limit" -> if (!PIDS.matches(v) || v.toInt() !in 1..MAX_PIDS) reject(GuardRule.FORBIDDEN_VALUE)
            "memory", "memory-swap" -> if (!DockerPatterns.MEMORY.matches(v)) reject(GuardRule.FORBIDDEN_VALUE)
            "cpus" -> if (!DockerPatterns.CPUS.matches(v)) reject(GuardRule.FORBIDDEN_VALUE)
            "network" -> if (v !in settings.allowedNetworks || v in DockerPatterns.RESERVED_NETWORKS) reject(GuardRule.FORBIDDEN_VALUE)
            "mount" -> checkMount(v)
            "workdir" -> if (v != DockerConstants.WORKDIR) reject(GuardRule.FORBIDDEN_VALUE)
            "label" -> checkLabel(v)
            "stop-timeout" -> if (!STOP_TIMEOUT.matches(v) || v.toInt() !in 1..MAX_STOP_TIMEOUT) reject(GuardRule.FORBIDDEN_VALUE)
            "env" -> checkEnv(v)
            else -> reject(GuardRule.UNKNOWN_OPTION)
        }
    }

    /** Only `type=bind,source=<the work directory>,target=/work`. Any other option (readonly, a second source) is refused. */
    private fun checkMount(v: String) {
        val prefix = "type=bind,source="
        val suffix = ",target=${DockerConstants.WORKDIR}"
        if (!v.startsWith(prefix) || !v.endsWith(suffix) || v.length <= prefix.length + suffix.length) {
            reject(GuardRule.FORBIDDEN_VALUE)
        }
        val source = v.substring(prefix.length, v.length - suffix.length)
        val path: Path = try {
            Path.of(source)
        } catch (e: InvalidPathException) {
            reject(GuardRule.PATH_REJECTED)
        }
        try {
            WorkDirPolicy.validate(path, settings.workRoot)
        } catch (e: DockerSpecException) {
            reject(GuardRule.PATH_REJECTED)
        }
    }

    private fun checkLabel(v: String) {
        val eq = v.indexOf('=')
        if (eq < 0) reject(GuardRule.MALFORMED)
        val key = v.substring(0, eq)
        val value = v.substring(eq + 1)
        val ok = when (key) {
            DockerConstants.LABEL_AGENT_KEY -> value == DockerConstants.LABEL_AGENT_VALUE
            DockerConstants.LABEL_RUN_KEY -> DockerPatterns.ID.matches(value)
            else -> false
        }
        if (!ok) reject(GuardRule.FORBIDDEN_VALUE)
    }

    private fun checkEnv(v: String) {
        val eq = v.indexOf('=')
        if (eq < 0) reject(GuardRule.ENV_REJECTED) // a bare name would inherit the host variable
        val name = v.substring(0, eq)
        val value = v.substring(eq + 1)
        if (name !in DockerConstants.ENV_NAMES) reject(GuardRule.ENV_REJECTED)
        if (value.any { it.isISOControl() } || value.contains("sk-ant-", ignoreCase = true)) reject(GuardRule.ENV_REJECTED)
        when (name) {
            "ANTHROPIC_API_KEY" -> if (value != DockerConstants.DUMMY_API_KEY) reject(GuardRule.ENV_REJECTED)
            "HTTPS_PROXY", "HTTP_PROXY" -> if (!DockerPatterns.PROXY_URL.matches(value)) reject(GuardRule.ENV_REJECTED)
            "ANTHROPIC_BASE_URL" -> {
                val proxy = value.removeSuffix(DockerConstants.PROXY_PATH)
                if (proxy == value || !DockerPatterns.PROXY_URL.matches(proxy)) reject(GuardRule.ENV_REJECTED)
            }
            "NO_PROXY" -> if (value != DockerConstants.NO_PROXY) reject(GuardRule.ENV_REJECTED)
            "JAVA_TOOL_OPTIONS" -> if (!JAVA_TOOL_OPTIONS_FORMAT.matches(value)) reject(GuardRule.ENV_REJECTED)
        }
    }

    private fun checkDuplicates(opts: List<Opt>) {
        for ((name, count) in opts.groupingBy { it.name }.eachCount()) {
            if (count > 1 && name !in REPEATABLE) reject(GuardRule.DUPLICATE_OPTION)
        }
        for (name in REPEATABLE) {
            val keys = opts.filter { it.name == name }.map { entryKey(name, it.value.orEmpty()) }
            if (keys.size != keys.toSet().size) reject(GuardRule.DUPLICATE_OPTION)
        }
    }

    /** The identity of one repeatable entry: the whole tmpfs target, or the name of an env or label entry. */
    private fun entryKey(name: String, value: String): String = when (name) {
        "tmpfs" -> value
        else -> value.substringBefore('=')
    }

    private fun checkRequiredEntries(opts: List<Opt>) {
        val tmpfs = opts.filter { it.name == "tmpfs" }.mapNotNull { it.value }.toSet()
        if (tmpfs != setOf(DockerConstants.TMPFS_TMP, DockerConstants.TMPFS_HOME)) reject(GuardRule.MISSING_OPTION)
        val envNames = opts.filter { it.name == "env" }.mapNotNull { it.value?.substringBefore('=') }.toSet()
        if (envNames != DockerConstants.ENV_NAMES) reject(GuardRule.MISSING_OPTION)
        val labelKeys = opts.filter { it.name == "label" }.mapNotNull { it.value?.substringBefore('=') }.toSet()
        if (labelKeys != setOf(DockerConstants.LABEL_AGENT_KEY, DockerConstants.LABEL_RUN_KEY)) reject(GuardRule.MISSING_OPTION)
    }

    private fun checkCrossValues(opts: List<Opt>) {
        val single = opts.associate { it.name to it.value }
        if (single["memory-swap"] != single["memory"]) reject(GuardRule.FORBIDDEN_VALUE)
        val name = single["name"].orEmpty()
        val runId = opts.first { it.name == "label" && it.value?.startsWith("${DockerConstants.LABEL_RUN_KEY}=") == true }
            .value!!.substringAfter('=')
        if (!name.startsWith("${DockerConstants.CONTAINER_PREFIX}$runId-")) reject(GuardRule.FORBIDDEN_VALUE)
        val env = opts.filter { it.name == "env" }.associate { o ->
            val v = o.value.orEmpty()
            v.substringBefore('=') to v.substringAfter('=')
        }
        val proxy = env["HTTPS_PROXY"]
        if (proxy == null || env["HTTP_PROXY"] != proxy) reject(GuardRule.ENV_REJECTED)
        if (env["ANTHROPIC_BASE_URL"] != proxy + DockerConstants.PROXY_PATH) reject(GuardRule.ENV_REJECTED)
        val expectedJavaOptions = try {
            ProxyEnv.javaToolOptions(proxy)
        } catch (e: DockerSpecException) {
            reject(GuardRule.ENV_REJECTED)
        }
        if (env["JAVA_TOOL_OPTIONS"] != expectedJavaOptions) reject(GuardRule.ENV_REJECTED)
    }

    private fun reject(rule: GuardRule): Nothing = throw DockerArgsRejectedException(rule)

    private companion object {
        val BOOL_OPTIONS = setOf("rm", "read-only")
        val VALUE_OPTIONS = setOf(
            "name", "user", "tmpfs", "cap-drop", "security-opt", "pids-limit", "memory", "memory-swap", "cpus", "network",
            "mount", "workdir", "label", "stop-timeout", "env",
        )
        val REPEATABLE = setOf("tmpfs", "env", "label")
        val REQUIRED_SINGLE = listOf(
            "rm", "read-only", "name", "user", "cap-drop", "security-opt", "pids-limit", "memory", "memory-swap", "cpus",
            "network", "mount", "workdir", "stop-timeout",
        )

        /** Named explicitly so that the refusal reason is clear. Each is also outside the allowlist. */
        val FORBIDDEN_NAMES = setOf(
            "privileged", "cap-add", "device", "device-cgroup-rule", "gpus", "pid", "ipc", "net", "uts", "userns", "cgroupns",
            "cgroup-parent", "volume", "volumes-from", "env-file", "add-host", "dns", "dns-option", "dns-search", "entrypoint",
            "runtime", "sysctl", "pull", "mount-propagation",
        )

        const val MAX_PIDS = 4096
        const val MAX_STOP_TIMEOUT = 120
        const val CLAUDE_PROGRAM = "claude"
        val PIDS = Regex("[1-9][0-9]{0,3}")
        val STOP_TIMEOUT = Regex("[1-9][0-9]{0,2}")
        val JAVA_TOOL_OPTIONS_FORMAT = Regex(
            "-Dhttp\\.proxyHost=[a-z0-9-]+ -Dhttp\\.proxyPort=[0-9]+ -Dhttps\\.proxyHost=[a-z0-9-]+ -Dhttps\\.proxyPort=[0-9]+",
        )

        /** Characters that a shell would interpret. A command token may not contain any of them, or a control character. */
        val SHELL_CHARS: Set<Char> = setOf(';', '&', '|', '$', '`', '<', '>', '(', ')', '{', '}', '[', ']', '\\', '\'', '"', '*', '?', '!', '~', '#')
    }
}
