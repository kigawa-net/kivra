package net.kigawa.kivra.agent

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Another kivra-agent already holds the work directory. */
class AlreadyRunningException : IllegalStateException("kivra-agent is already running")

/**
 * A lock on `<workDir>/.kivra-agent.lock` that keeps a second agent from running on the same work directory (design section 9,
 * PR 5). It is an OS file lock, so it is released when the process dies. A second lock in the same JVM fails the same way
 * (OverlappingFileLockException), and so does a lock held by another process.
 */
class WorkDirLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        const val LOCK_FILE_NAME = ".kivra-agent.lock"

        fun acquire(workDir: Path): WorkDirLock {
            val channel = FileChannel.open(
                workDir.resolve(LOCK_FILE_NAME),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
            if (lock == null) {
                channel.close()
                throw AlreadyRunningException()
            }
            return WorkDirLock(channel, lock)
        }
    }
}

/** Checks made before anything starts. A failure is a [ConfigException] whose message names a variable, never a value. */
object StartupChecks {
    fun checkWorkDir(workDir: Path) {
        if (!Files.isDirectory(workDir) || !Files.isWritable(workDir)) {
            throw ConfigException("${AgentConfig.ENV_WORK_DIR} must be an existing writable directory")
        }
    }

    /** The secret file must be a readable regular file that group and others cannot read (fail-closed). */
    fun checkSecretFile(secretFile: Path) {
        val name = AgentConfig.ENV_CLIENT_SECRET_FILE
        if (!Files.isRegularFile(secretFile) || !Files.isReadable(secretFile)) {
            throw ConfigException("$name must be a readable regular file")
        }
        val permissions = try {
            Files.getPosixFilePermissions(secretFile)
        } catch (e: UnsupportedOperationException) {
            throw ConfigException("$name must be on a filesystem with POSIX permissions")
        } catch (e: IOException) {
            throw ConfigException("$name must be a readable regular file")
        }
        if (permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
            throw ConfigException("$name must not be readable by group or others (use mode 0600)")
        }
    }
}
