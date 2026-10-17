package net.kigawa.kivra.agent.docker

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The task file contract (runbook 6.7). The host writes it into the work directory before the run; the CLI reads it. The task text
 * never goes into the arguments or the environment.
 *
 * - Path: `<workDir>/.kivra/task.json` (inside the mounted work directory, so the container sees `/work/.kivra/task.json`).
 * - Content: one JSON object, UTF-8, with `schemaVersion` (1), `trust` (always `UNTRUSTED`), `runId`, `attemptId`, `title`, `body`,
 *   and `truncated`. Title is at most [MAX_TITLE_CHARS] characters and body at most [MAX_BODY_BYTES] bytes. Larger input is refused
 *   (the caller truncates first and sets `truncated`).
 * - The host never reads the file back. The change set is made from the work directory later (PR 8), and `.kivra/` is excluded there.
 *
 * Errors name only the field, never the content.
 */
object TaskFile {
    const val DIRECTORY = ".kivra"
    const val FILE_NAME = "task.json"
    const val RELATIVE_PATH = "$DIRECTORY/$FILE_NAME"
    const val SCHEMA_VERSION = 1
    const val TRUST = "UNTRUSTED"
    const val MAX_TITLE_CHARS = 2000
    const val MAX_BODY_BYTES = 64 * 1024
    private const val DIRECTORY_MODE = "rwxrwxrwx"
    private const val FILE_MODE = "rw-r--r--"

    /** Writes the task file and returns its path. Refuses symbolic links at the directory and the file. */
    fun write(workDir: Path, runId: String, attemptId: String, title: String, body: String, truncated: Boolean): Path {
        if (!DockerPatterns.ID.matches(runId)) throw DockerSpecException("runId is invalid")
        if (!DockerPatterns.ID.matches(attemptId)) throw DockerSpecException("attemptId is invalid")
        if (title.length > MAX_TITLE_CHARS) throw DockerSpecException("title is too long")
        if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_BODY_BYTES) throw DockerSpecException("body is too long")
        if (!Files.isDirectory(workDir, LinkOption.NOFOLLOW_LINKS)) {
            throw DockerSpecException("workDir must be an existing directory")
        }
        val dir = workDir.resolve(DIRECTORY)
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw DockerSpecException("task directory is not a directory")
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir)
        // The container runs as another uid (runbook 6.8), so the modes are set explicitly. A umask (systemd UMask=0077) must not decide them.
        requirePosix(workDir)
        setMode(dir, DIRECTORY_MODE)
        val file = dir.resolve(FILE_NAME)
        if (Files.isSymbolicLink(file)) throw DockerSpecException("task file must not be a symbolic link")

        Files.writeString(file, document(runId, attemptId, title, body, truncated).toString(), StandardCharsets.UTF_8)
        setMode(file, FILE_MODE)
        return file
    }

    private fun requirePosix(workDir: Path) {
        if ("posix" !in workDir.fileSystem.supportedFileAttributeViews()) {
            throw DockerSpecException("the file system does not support POSIX permissions")
        }
    }

    private fun setMode(path: Path, mode: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        } catch (e: IOException) {
            throw DockerSpecException("task file permissions cannot be set")
        } catch (e: UnsupportedOperationException) {
            throw DockerSpecException("the file system does not support POSIX permissions")
        }
    }

    /** The JSON object that [write] stores. */
    internal fun document(runId: String, attemptId: String, title: String, body: String, truncated: Boolean): JsonObject =
        buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("trust", TRUST)
            put("runId", runId)
            put("attemptId", attemptId)
            put("title", title)
            put("body", body)
            put("truncated", truncated)
        }
}
