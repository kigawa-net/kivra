package net.kigawa.kivra.agent.docker

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The only host path a container may mount: the work directory of one run (ADR 0010 invariant 3, design section 3.3 T4).
 *
 * [checkSyntax] needs no file system. [validate] needs the work root (already resolved) and checks the file system: every component
 * below the root must be a real directory (no symbolic link), and the resolved path must still lie strictly under the root.
 * Messages are fixed; they never echo the path.
 */
internal object WorkDirPolicy {
    private const val MAX_LENGTH = 4096

    fun checkSyntax(path: Path) {
        val text = path.toString()
        if (text.isEmpty() || text.length > MAX_LENGTH) throw DockerSpecException("workDir has an invalid length")
        if (!path.isAbsolute) throw DockerSpecException("workDir must be an absolute path")
        // `:` and `,` are the separators of the docker mount syntax; a path that contains them could add options.
        if (text.any { it == ':' || it == ',' || it == '\\' || it.isISOControl() }) {
            throw DockerSpecException("workDir contains a character that is not allowed")
        }
        if (path.any { it.toString() == ".." || it.toString() == "." }) throw DockerSpecException("workDir must not contain '.' or '..'")
    }

    /**
     * Returns the resolved path, which is the one to mount. [root] must be the real (symlink-free) work root.
     */
    fun validate(path: Path, root: Path): Path {
        checkSyntax(path)
        if (!path.startsWith(root) || path == root) throw DockerSpecException("workDir must be a directory under the work root")
        var current = root
        for (name in root.relativize(path)) {
            current = current.resolve(name)
            if (Files.isSymbolicLink(current)) throw DockerSpecException("workDir must not contain a symbolic link")
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw DockerSpecException("workDir must be an existing directory")
        val real = try {
            path.toRealPath()
        } catch (e: java.io.IOException) {
            throw DockerSpecException("workDir cannot be resolved")
        }
        if (real != path || !real.startsWith(root) || real == root) {
            throw DockerSpecException("workDir resolves outside the work root")
        }
        return real
    }
}
