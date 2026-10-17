package net.kigawa.kivra.agent.workdir

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Where the base commit is fetched from. Only public, read-only sources exist. */
sealed interface RepoSource {
    /** A public GitHub repository. The URL is built from the validated owner and name only. There is no credential. */
    data class PublicGithub(val owner: String, val repo: String) : RepoSource {
        init {
            require(OWNER.matches(owner) && REPO.matches(repo)) { "repository name is invalid" }
        }

        fun url(): String = "https://github.com/$owner/$repo.git"

        private companion object {
            val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
            val REPO = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}")
        }
    }
}

/** A local repository. Used by tests only; it is internal and cannot be built outside this module. */
internal data class LocalPath(val path: Path) : RepoSource

/**
 * A host-side bare mirror that is fetched without any credential (runbook 6.8). Git runs with an empty environment, no global or
 * system configuration, no prompt, and no credential helper. Only `https` is allowed for a public repository (`protocol.allow=never`
 * first). The archive is read with `git archive`, which only reads objects.
 *
 * Errors name no URL, path or output: they are [WorkDirException] with [WorkDirReason.GIT_FAILED].
 */
class PublicGitMirror(
    private val gitBinary: Path,
    private val mirrorDir: Path,
    private val timeout: Duration = Duration.ofMinutes(10),
) {
    init {
        require(gitBinary.isAbsolute) { "gitBinary must be an absolute path" }
        require(mirrorDir.isAbsolute) { "mirrorDir must be an absolute path" }
    }

    /** Creates the bare mirror on first use and fetches all refs into it. */
    fun sync(source: RepoSource) {
        if (!Files.isDirectory(mirrorDir)) {
            runGit(source, listOf("init", "--bare", "--quiet", mirrorDir.toString()))
        }
        val url = when (source) {
            is RepoSource.PublicGithub -> source.url()
            is LocalPath -> source.path.toString()
        }
        runGit(source, listOf("--git-dir=$mirrorDir", "fetch", "--prune", "--quiet", "--no-tags", url, "+refs/*:refs/*"))
    }

    /** Streams the tar of [commit]. Closing the stream waits for git and fails if git failed. */
    fun openArchive(commit: String): InputStream {
        if (!COMMIT.matches(commit)) throw WorkDirException(WorkDirReason.GIT_FAILED)
        val builder = ProcessBuilder(command(listOf("--git-dir=$mirrorDir", "archive", "--format=tar", commit)))
        configure(builder)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        return GitArchiveStream(process, timeout)
    }

    private fun runGit(source: RepoSource, args: List<String>) {
        val transport = when (source) {
            is RepoSource.PublicGithub -> listOf("-c", "protocol.allow=never", "-c", "protocol.https.allow=always")
            is LocalPath -> listOf("-c", "protocol.allow=never", "-c", "protocol.file.allow=always")
        }
        val builder = ProcessBuilder(command(transport + args))
        configure(builder)
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw WorkDirException(WorkDirReason.GIT_FAILED)
        }
        if (process.exitValue() != 0) throw WorkDirException(WorkDirReason.GIT_FAILED)
    }

    /** The whole environment is replaced: nothing of the caller's environment reaches git. */
    private fun configure(builder: ProcessBuilder) {
        val env = builder.environment()
        env.clear()
        env.putAll(environment())
    }

    private fun command(args: List<String>): List<String> = listOf(gitBinary.toString(), "-c", "credential.helper=", "-c", "core.hooksPath=/dev/null") + args

    private fun environment(): Map<String, String> = mapOf(
        "PATH" to "/usr/bin:/bin",
        "HOME" to mirrorDir.toString(),
        "GIT_CONFIG_GLOBAL" to "/dev/null",
        "GIT_CONFIG_NOSYSTEM" to "1",
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_ASKPASS" to "/bin/false",
        "SSH_ASKPASS" to "/bin/false",
        "LC_ALL" to "C.UTF-8",
    )

    private companion object {
        val COMMIT = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}

/**
 * The stdout of `git archive`. The end of the stream is reported only after git has exited successfully, so a failed git is never
 * seen as a short archive. [close] drains what is left, waits for git, and fails if git exited non-zero.
 */
private class GitArchiveStream(private val process: Process, private val timeout: Duration) : InputStream() {
    private val input = process.inputStream
    private var finished = false

    override fun read(): Int {
        val b = input.read()
        return if (b < 0) finish() else b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        return if (n < 0) finish() else n
    }

    override fun close() {
        if (finished) return
        try {
            input.transferTo(OutputStream.nullOutputStream())
            input.close()
        } catch (e: IOException) {
            process.destroyForcibly()
            throw WorkDirException(WorkDirReason.GIT_FAILED)
        }
        finish()
    }

    /** Waits for git. Fails unless git exited with status 0. Returns -1 for the end of the stream. */
    private fun finish(): Int {
        if (!finished) {
            finished = true
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw WorkDirException(WorkDirReason.GIT_FAILED)
            }
            if (process.exitValue() != 0) throw WorkDirException(WorkDirReason.GIT_FAILED)
        }
        return -1
    }
}
