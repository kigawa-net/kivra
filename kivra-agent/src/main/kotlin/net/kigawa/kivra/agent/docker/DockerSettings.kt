package net.kigawa.kivra.agent.docker

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A value is invalid. The message names the field only, never the value: a path, an id or a secret must not reach the journal
 * through an error.
 */
class DockerSpecException(message: String) : IllegalArgumentException(message)

/**
 * Fixed values shared by the builder and the guard (ADR 0010 invariants 1-7, design #187 section 3.3). Several must match
 * `agent-runtime/image/Dockerfile` (uid 10001) and `agent-runtime/network/run-agent.sh`. They are constants, not settings, so a
 * configuration file cannot widen the container.
 */
object DockerConstants {
    const val AGENT_UID = 10001
    const val AGENT_GID = 10001
    const val USER = "$AGENT_UID:$AGENT_GID"
    const val WORKDIR = "/work"
    const val CONTAINER_PREFIX = "kivra-run-"
    const val LABEL_AGENT_KEY = "kivra-agent"
    const val LABEL_AGENT_VALUE = "1"
    const val LABEL_RUN_KEY = "kivra-run"
    const val TMPFS_TMP = "/tmp:rw,nosuid,size=512m"
    const val TMPFS_HOME = "/home/agent:rw,nosuid,mode=0700,uid=$AGENT_UID,gid=$AGENT_GID,size=2g"
    const val DEFAULT_MEMORY = "6g"
    const val DEFAULT_CPUS = "4"
    const val DEFAULT_PIDS_LIMIT = 512
    const val DEFAULT_STOP_TIMEOUT_SECONDS = 10

    /** The API key the container sees. It is a dummy, not a credential; the proxy adds the real authorization. */
    const val DUMMY_API_KEY = "kivra-agent-dummy-not-a-credential"
    const val NO_PROXY = "localhost,127.0.0.1"
    const val PROXY_PATH = "/anthropic"

    /** The only environment names a container may receive. Anything else, including a host variable, is refused. */
    val ENV_NAMES: Set<String> = setOf(
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_API_KEY",
        "HTTPS_PROXY",
        "HTTP_PROXY",
        "NO_PROXY",
        "JAVA_TOOL_OPTIONS",
    )
}

/**
 * The `JAVA_TOOL_OPTIONS` value that points the JVMs in the container (for example `./gradlew test` run by the CLI) at the egress
 * proxy. It is built only from the host and port of the proxy URL, in one fixed form, so no other text can be put in it. The guard
 * compares the value with this form exactly.
 */
internal object ProxyEnv {
    private val PROXY = Regex("http://([a-z0-9][a-z0-9-]{0,62}):([0-9]{1,5})")

    fun javaToolOptions(proxyUrl: String): String {
        val match = PROXY.matchEntire(proxyUrl) ?: throw DockerSpecException("proxyUrl must be http://host:port")
        val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw DockerSpecException("proxy port is out of range")
        val host = match.groupValues[1]
        return "-Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttps.proxyHost=$host -Dhttps.proxyPort=$port"
    }
}

/** Patterns for every value that reaches the docker command line. Matching is whole-string (`matches`), so a newline never passes. */
internal object DockerPatterns {
    /** runId and attemptId: a letter or digit first, then letters, digits and hyphens. No `:`, `,`, `=`, spaces or shell characters. */
    val ID = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}")
    val IMAGE = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?")
    val NETWORK = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    val PROXY_URL = Regex("http://[a-z0-9][a-z0-9-]{0,62}:[0-9]{1,5}")
    val MEMORY = Regex("[1-9][0-9]{0,4}[mg]")
    val CPUS = Regex("(?!0+(?:\\.0+)?$)[0-9]{1,2}(?:\\.[0-9]{1,2})?")
    val DOCKER_HOST = Regex("unix:///[A-Za-z0-9._/-]{1,255}")
    val CONTAINER_ID = Regex("[0-9a-f]{12,64}")
    val CONTAINER_NAME = Regex("kivra-run-([A-Za-z0-9][A-Za-z0-9-]{0,63})-([A-Za-z0-9][A-Za-z0-9-]{0,63})")

    /** Networks that are never accepted by name: host and container namespaces, none, and the default bridge. */
    val RESERVED_NETWORKS = setOf("host", "none", "bridge", "default")
}

/**
 * The host-side settings of the docker runner. Immutable and validated once. [toString] shows no value.
 *
 * - [dockerBinary]: the absolute path of the docker CLI.
 * - [dockerHost]: the value of `DOCKER_HOST` for the rootless socket. It is passed to the docker CLI process only, never to a
 *   container, and never as an argument.
 * - [workRoot]: the work root. Every work directory must be a strict child of it after symlinks are resolved. It must not be
 *   the user's home, an ancestor of it, or a system directory.
 * - [allowedNetworks]: internal networks (`docker network create --internal`). The operator creates them; this code does not
 *   check the `--internal` flag at run time.
 * - [allowedImages]: the image names that may run.
 */
class DockerSettings(
    val dockerBinary: Path,
    val dockerHost: String?,
    workRoot: Path,
    allowedNetworks: Set<String>,
    allowedImages: Set<String>,
) {
    val workRoot: Path = checkWorkRoot(workRoot)
    val allowedNetworks: Set<String> = checkNetworks(allowedNetworks)
    val allowedImages: Set<String> = checkImages(allowedImages)

    init {
        if (!dockerBinary.isAbsolute) throw DockerSpecException("dockerBinary must be an absolute path")
        if (dockerHost != null && (!DockerPatterns.DOCKER_HOST.matches(dockerHost) || dockerHost.contains(".."))) {
            throw DockerSpecException("dockerHost must be a unix socket URL (unix:///...)")
        }
    }

    override fun toString(): String = "DockerSettings(***)"

    private companion object {
        /** Trees a work root must never be placed under. */
        val FORBIDDEN_ROOT_PREFIXES = listOf("/etc", "/proc", "/sys", "/dev", "/run", "/var/run", "/boot", "/root", "/usr", "/bin", "/sbin", "/lib", "/lib64")

        fun checkWorkRoot(root: Path): Path {
            if (!root.isAbsolute) throw DockerSpecException("workRoot must be an absolute path")
            val real = try {
                root.toRealPath()
            } catch (e: IOException) {
                throw DockerSpecException("workRoot must be an existing directory")
            }
            if (!Files.isDirectory(real)) throw DockerSpecException("workRoot must be an existing directory")
            if (real.nameCount < 2) throw DockerSpecException("workRoot is too broad")
            if (FORBIDDEN_ROOT_PREFIXES.any { real.startsWith(Path.of(it)) }) throw DockerSpecException("workRoot is a system directory")
            val home = runCatching { Path.of(System.getProperty("user.home")).toRealPath() }.getOrNull()
            if (home != null && (home == real || home.startsWith(real))) {
                throw DockerSpecException("workRoot must not be the user's home or contain it")
            }
            return real
        }

        fun checkNetworks(networks: Set<String>): Set<String> {
            if (networks.isEmpty()) throw DockerSpecException("allowedNetworks must not be empty")
            for (name in networks) {
                if (!DockerPatterns.NETWORK.matches(name) || name in DockerPatterns.RESERVED_NETWORKS) {
                    throw DockerSpecException("allowedNetworks contains an invalid or reserved network name")
                }
            }
            return networks.toSet()
        }

        fun checkImages(images: Set<String>): Set<String> {
            if (images.isEmpty()) throw DockerSpecException("allowedImages must not be empty")
            for (image in images) {
                if (image.length > 255 || !DockerPatterns.IMAGE.matches(image)) throw DockerSpecException("allowedImages contains an invalid image")
            }
            return images.toSet()
        }
    }
}
