package net.kigawa.kivra.agent

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.slf4j.LoggerFactory

/** Result of running an external command. [output] is stdout and stderr merged. */
data class CommandResult(val exitCode: Int, val output: String)

/** Runs an external command. Abstracted so scanners can be tested without a build tool. */
fun interface CommandRunner {
    fun run(command: List<String>, workDir: Path, timeout: Duration): CommandResult
}

/** Default [CommandRunner] backed by [ProcessBuilder]. A timeout kills the process and reports exit code -1. */
object ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, workDir: Path, timeout: Duration): CommandResult {
        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        val reader = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return CommandResult(-1, "")
        }
        return CommandResult(process.exitValue(), reader.get(5, TimeUnit.SECONDS))
    }
}

/** Parses Kotlin compiler error lines as printed by Gradle. */
object KotlinCompilerOutputParser {
    // e: file:///repo/src/Foo.kt:12:34 message   (Kotlin 1.9+ / 2.x)
    private val NEW_FORMAT = Regex("^e: (?:file://)?(.+?\\.kt):(\\d+):(\\d+)\\s+(.*)$")
    // e: /repo/src/Foo.kt: (12, 34): message     (older)
    private val OLD_FORMAT = Regex("^e: (?:file://)?(.+?\\.kt): \\((\\d+), (\\d+)\\): (.*)$")

    /** One entry per error line: (absolute path as printed, line, message). */
    data class Error(val path: String, val line: Int, val message: String)

    fun parse(output: String): List<Error> = output.lineSequence()
        .mapNotNull { raw ->
            val line = raw.trim()
            val m = NEW_FORMAT.find(line) ?: OLD_FORMAT.find(line) ?: return@mapNotNull null
            Error(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[4].trim())
        }
        .distinct()
        .toList()
}

/** Parses JUnit XML reports (`TEST-*.xml`) for failed and errored test cases. */
object JUnitXmlReportParser {
    data class Failure(val className: String, val testName: String, val message: String)

    fun parse(xml: String): List<Failure> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val cases = doc.getElementsByTagName("testcase")
        val failures = mutableListOf<Failure>()
        for (i in 0 until cases.length) {
            val case = cases.item(i) as Element
            val problem = (0 until case.childNodes.length)
                .map { case.childNodes.item(it) }
                .filterIsInstance<Element>()
                .firstOrNull { it.tagName == "failure" || it.tagName == "error" } ?: continue
            val message = problem.getAttribute("message").ifBlank { problem.textContent.lineSequence().firstOrNull().orEmpty() }
            failures += Failure(case.getAttribute("classname"), case.getAttribute("name"), message.trim())
        }
        return failures
    }
}

/**
 * Detects code issues in a checked-out repository at [repoRoot].
 *
 * - Compilation errors: runs [compileCommand] (for example `./gradlew compileKotlin --console=plain`) and
 *   parses the Kotlin compiler errors when it exits non-zero.
 * - Test failures: reads existing JUnit XML reports under `build/test-results`; it does not run the tests.
 *
 * File reads and reported paths are confined to [repoRoot]; anything outside is ignored.
 */
class ProcessCodeIssueScanner(
    repoRoot: Path,
    private val compileCommand: List<String>,
    private val runner: CommandRunner = ProcessCommandRunner,
    private val compileTimeout: Duration = Duration.ofMinutes(10),
    private val maxIssues: Int = 50,
    private val maxFileBytes: Long = 64 * 1024,
) : CodeIssueScanner {
    private val root: Path = repoRoot.toAbsolutePath().normalize()

    override fun getCompilationErrors(): List<CodeIssue> {
        val result = try {
            runner.run(compileCommand, root, compileTimeout)
        } catch (e: Exception) {
            logger.warn("code-scan: compile command failed to run exception={}", e.javaClass.simpleName)
            return emptyList()
        }
        if (result.exitCode == 0) return emptyList()
        return KotlinCompilerOutputParser.parse(result.output)
            .mapNotNull { err ->
                val rel = relativize(err.path) ?: return@mapNotNull null
                CodeIssue(
                    id = issueId("compile", rel, err.line, err.message),
                    type = IssueType.COMPILATION_ERROR,
                    message = err.message,
                    filePath = rel,
                    lineNumber = err.line,
                    relatedFiles = listOf(rel),
                )
            }
            .take(maxIssues)
    }

    override fun getTestFailures(): List<CodeIssue> {
        val reports = try {
            Files.walk(root, 8).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p) && p.fileName.toString().let { it.startsWith("TEST-") && it.endsWith(".xml") } &&
                        p.toString().contains("build/test-results")
                }.toList()
            }
        } catch (e: Exception) {
            logger.warn("code-scan: cannot list test reports exception={}", e.javaClass.simpleName)
            return emptyList()
        }
        val issues = mutableListOf<CodeIssue>()
        for (report in reports) {
            val failures = try {
                JUnitXmlReportParser.parse(Files.readString(report))
            } catch (e: Exception) {
                logger.warn("code-scan: cannot parse test report exception={}", e.javaClass.simpleName)
                continue
            }
            for (f in failures) {
                val source = findSourceFile(f.className)
                issues += CodeIssue(
                    id = issueId("test", f.className, 0, f.testName),
                    type = IssueType.TEST_FAILURE,
                    message = "${f.className}.${f.testName}: ${f.message}",
                    filePath = source,
                    relatedFiles = listOfNotNull(source),
                )
                if (issues.size >= maxIssues) return issues
            }
        }
        return issues.distinctBy { it.id }
    }

    override fun readFileContent(path: String): String {
        val resolved = root.resolve(path).normalize()
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) return ""
        return try {
            if (Files.size(resolved) > maxFileBytes) "" else Files.readString(resolved)
        } catch (_: Exception) {
            ""
        }
    }

    /** Repo-relative path of [printed] (absolute or relative), or null when outside the repo. */
    private fun relativize(printed: String): String? {
        val p = Path.of(printed)
        val abs = (if (p.isAbsolute) p else root.resolve(p)).normalize()
        return if (abs.startsWith(root)) root.relativize(abs).toString() else null
    }

    /** Best effort: `a.b.FooTest` -> the `src/**/FooTest.kt` file, or null. */
    private fun findSourceFile(className: String): String? {
        val simple = className.substringAfterLast('.').substringBefore('$')
        if (simple.isBlank()) return null
        return try {
            Files.walk(root, 10).use { stream ->
                stream.filter { p ->
                    p.fileName.toString() == "$simple.kt" && p.toString().contains("/src/") && !p.toString().contains("/build/")
                }.findFirst().map { root.relativize(it).toString() }.orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun issueId(kind: String, a: String, line: Int, b: String): String =
        "$kind-" + Integer.toHexString("$a:$line:$b".hashCode())

    companion object {
        private val logger = LoggerFactory.getLogger(ProcessCodeIssueScanner::class.java)
    }
}
