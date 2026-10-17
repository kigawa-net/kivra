package net.kigawa.kivra.agent.workdir

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

internal const val TEST_RUN = "01M545K02Q6YB4M5ZHAM5792CS"
internal const val TEST_ATTEMPT = "att-1"
internal val GIT_BIN: Path = Path.of("/usr/bin/git")

/** Runs git with an empty environment and no global configuration. Fails the test when git fails. */
internal fun git(dir: Path, vararg args: String) {
    val process = ProcessBuilder(
        listOf(GIT_BIN.toString(), "-c", "user.name=test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") +
            args.toList(),
    )
        .directory(dir.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply { environment().clear(); environment()["PATH"] = "/usr/bin:/bin"; environment()["HOME"] = dir.toString(); environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"; environment()["GIT_CONFIG_NOSYSTEM"] = "1" }
        .start()
    check(process.waitFor() == 0) { "git failed in test setup" }
}

internal class LocalRepo(val repo: Path, val sha: String)

/**
 * A local repository with [files] committed. A value of `null` in [executables] is not used; the set names the paths that get the
 * executable bit before the commit.
 */
internal fun makeRepo(files: Map<String, String>, executables: Set<String> = emptySet(), extra: (Path) -> Unit = {}): LocalRepo {
    val repo = Files.createTempDirectory("kivra-src").toRealPath()
    git(repo, "init", "-q", "-b", "main", ".")
    for ((rel, text) in files) {
        val p = repo.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray(StandardCharsets.UTF_8))
        if (rel in executables) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
    extra(repo)
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "init")
    val sha = ProcessBuilder(GIT_BIN.toString(), "rev-parse", "HEAD").directory(repo.toFile()).start().let {
        val out = it.inputStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
        check(it.waitFor() == 0)
        out
    }
    return LocalRepo(repo, sha)
}

/** Runs the real git path: a mirror of [repo], then `git archive` of [sha], streamed to [consume]. */
internal fun <T> archiveOf(repo: LocalRepo, mirrorRoot: Path, sha: String = repo.sha, consume: (InputStream) -> T): T {
    val mirror = PublicGitMirror(GIT_BIN, mirrorRoot.resolve("mirror.git"), Duration.ofMinutes(2))
    mirror.sync(LocalPath(repo.repo))
    return mirror.openArchive(sha).use(consume)
}

/** A fresh work root (mode 0700, as createTempDirectory makes it). */
internal fun newWorkRoot(): Path = Files.createTempDirectory("kivra-wr").toRealPath()

/** One entry of a handcrafted tar. [type] is the tar type flag. */
internal class TarSpec(
    val name: String,
    val type: Char = '0',
    val content: ByteArray = ByteArray(0),
    val mode: Int = 420,
    val linkName: String = "",
)

/** Builds a tar stream with ustar headers. Names longer than 100 bytes get a pax `path` record, as git writes them. */
internal fun tarOf(entries: List<TarSpec>, badChecksumAt: Int = -1, truncateAt: Int = -1): ByteArray {
    val out = ByteArrayOutputStream()
    entries.forEachIndexed { index, e ->
        val nameBytes = e.name.toByteArray(StandardCharsets.UTF_8)
        if (nameBytes.size > 100) {
            val record = paxRecord("path", e.name)
            out.write(header("PaxHeader", 'x', record.size.toLong(), 420, "", bad = false))
            out.write(record)
            out.write(ByteArray(padding(record.size.toLong()).toInt()))
        }
        val shortName = if (nameBytes.size > 100) nameBytes.copyOf(100).toString(StandardCharsets.UTF_8) else e.name
        out.write(header(shortName, e.type, e.content.size.toLong(), e.mode, e.linkName, bad = index == badChecksumAt))
        out.write(e.content)
        out.write(ByteArray(padding(e.content.size.toLong()).toInt()))
    }
    out.write(ByteArray(1024))
    val bytes = out.toByteArray()
    return if (truncateAt in 0 until bytes.size) bytes.copyOf(truncateAt) else bytes
}

private fun padding(size: Long): Long = (512 - size % 512) % 512

private fun paxRecord(key: String, value: String): ByteArray {
    val body = " $key=$value\n"
    var length = body.toByteArray(StandardCharsets.UTF_8).size + 1
    while (length.toString().length + body.toByteArray(StandardCharsets.UTF_8).size != length) length++
    return (length.toString() + body).toByteArray(StandardCharsets.UTF_8)
}

private fun header(name: String, type: Char, size: Long, mode: Int, linkName: String, bad: Boolean): ByteArray {
    val h = ByteArray(512)
    fun put(offset: Int, length: Int, text: String) {
        val b = text.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(b, 0, h, offset, minOf(b.size, length))
    }
    put(0, 100, name)
    put(100, 8, "%07o".format(mode) + "\u0000")
    put(108, 8, "0000000\u0000")
    put(116, 8, "0000000\u0000")
    put(124, 12, "%011o".format(size) + "\u0000")
    put(136, 12, "00000000000\u0000")
    for (i in 148 until 156) h[i] = ' '.code.toByte()
    h[156] = type.code.toByte()
    put(157, 100, linkName)
    put(257, 6, "ustar\u0000")
    put(263, 2, "00")
    var sum = 0L
    for (b in h) sum += b.toLong() and 0xFF
    if (bad) sum += 1
    put(148, 8, "%06o".format(sum) + "\u0000 ")
    return h
}

/** Reads a whole file as UTF-8. */
internal fun Path.text(): String = Files.readString(this, StandardCharsets.UTF_8)
