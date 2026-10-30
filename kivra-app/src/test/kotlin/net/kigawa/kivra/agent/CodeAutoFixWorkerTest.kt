package net.kigawa.kivra.agent

import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.DeveloperRequest
import net.kigawa.kivra.developer.DeveloperResult
import net.kigawa.kivra.developer.DeveloperUsage
import net.kigawa.kivra.modelgateway.Usage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CodeAutoFixWorker tests.
 */
class CodeAutoFixWorkerTest {

    @Test
    fun `tick returns zero counts when no issues`() = runBlocking {
        // Given
        val developer = FakeDeveloper()
        val scanner = NoOpCodeIssueScanner
        val config = CodeAutoFixConfig()
        val worker = CodeAutoFixWorker(developer, scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(0, pass.issuesScanned)
        assertEquals(0, pass.fixesAttempted)
        assertEquals(0, pass.fixesSucceeded)
        assertEquals(0, pass.fixesFailed)
        assertEquals(0, developer.proposeCount)
    }

    @Test
    fun `tick processes compilation errors`() = runBlocking {
        // Given
        val developer = FakeDeveloper()
        val scanner = FakeCodeIssueScanner(
            errors = listOf(
                CodeIssue(
                    id = "comp-1",
                    type = IssueType.COMPILATION_ERROR,
                    message = "Unresolved reference: foo",
                    filePath = "src/main/kotlin/Example.kt",
                    lineNumber = 10,
                    relatedFiles = listOf("src/main/kotlin/Example.kt"),
                ),
            ),
            failures = emptyList(),
        )
        val config = CodeAutoFixConfig()
        val worker = CodeAutoFixWorker(developer, scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(1, pass.issuesScanned)
        assertEquals(1, pass.fixesAttempted)
        assertEquals(1, pass.fixesSucceeded)
        assertEquals(0, pass.fixesFailed)
        assertEquals(1, developer.proposeCount)
    }

    @Test
    fun `tick processes test failures`() = runBlocking {
        // Given
        val developer = FakeDeveloper()
        val scanner = FakeCodeIssueScanner(
            errors = emptyList(),
            failures = listOf(
                CodeIssue(
                    id = "test-1",
                    type = IssueType.TEST_FAILURE,
                    message = "Test failed: ExampleTest",
                    filePath = "src/test/kotlin/ExampleTest.kt",
                    relatedFiles = listOf("src/test/kotlin/ExampleTest.kt"),
                ),
            ),
        )
        val config = CodeAutoFixConfig()
        val worker = CodeAutoFixWorker(developer, scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(1, pass.issuesScanned)
        assertEquals(1, pass.fixesAttempted)
        assertEquals(1, pass.fixesSucceeded)
        assertEquals(0, pass.fixesFailed)
        assertEquals(1, developer.proposeCount)
    }

    @Test
    fun `tick respects maxIssuesPerTick limit`() = runBlocking {
        // Given
        val developer = FakeDeveloper()
        val scanner = FakeCodeIssueScanner(
            errors = listOf(
                CodeIssue(id = "1", type = IssueType.COMPILATION_ERROR, message = "Error 1"),
                CodeIssue(id = "2", type = IssueType.COMPILATION_ERROR, message = "Error 2"),
                CodeIssue(id = "3", type = IssueType.COMPILATION_ERROR, message = "Error 3"),
                CodeIssue(id = "4", type = IssueType.COMPILATION_ERROR, message = "Error 4"),
            ),
            failures = emptyList(),
        )
        val config = CodeAutoFixConfig(maxIssuesPerTick = 2)
        val worker = CodeAutoFixWorker(developer, scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(2, pass.issuesScanned)
        assertEquals(2, pass.fixesAttempted)
        assertEquals(2, developer.proposeCount)
    }

    @Test
    fun `tick counts failed fixes when developer refuses`() = runBlocking {
        // Given
        val developer = FakeDeveloper(refuseAll = true)
        val scanner = FakeCodeIssueScanner(
            errors = listOf(
                CodeIssue(id = "1", type = IssueType.COMPILATION_ERROR, message = "Error 1"),
            ),
            failures = emptyList(),
        )
        val config = CodeAutoFixConfig()
        val worker = CodeAutoFixWorker(developer, scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(1, pass.issuesScanned)
        assertEquals(1, pass.fixesAttempted)
        assertEquals(0, pass.fixesSucceeded)
        assertEquals(1, pass.fixesFailed)
    }

    @Test
    fun `tick skips compilation errors when disabled`() = runBlocking {
        // Given
        val developer = FakeDeveloper()
        val scanner = FakeCodeIssueScanner(
            errors = listOf(
                CodeIssue(id = "1", type = IssueType.COMPILATION_ERROR, message = "Error 1"),
            ),
            failures = emptyList(),
        )
        val config = CodeAutoFixConfig(scanCompilationErrors = false)
        val worker = CodeAutoFixWorker(developer, scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(0, pass.issuesScanned)
        assertEquals(0, pass.fixesAttempted)
        assertEquals(0, developer.proposeCount)
    }

    @Test
    fun `tick skips test failures when disabled`() = runBlocking {
        // Given
        val developer = FakeDeveloper()
        val scanner = FakeCodeIssueScanner(
            errors = emptyList(),
            failures = listOf(
                CodeIssue(id = "1", type = IssueType.TEST_FAILURE, message = "Test failed"),
            ),
        )
        val config = CodeAutoFixConfig(scanTestFailures = false)
        val worker = CodeAutoFixWorker(developer, scanner, config)

        // When
        val pass = worker.tick()

        // Then
        assertEquals(0, pass.issuesScanned)
        assertEquals(0, pass.fixesAttempted)
        assertEquals(0, developer.proposeCount)
    }
}

/** Fake DeveloperPort for testing. */
class FakeDeveloper(
    val refuseAll: Boolean = false,
) : DeveloperPort {
    var proposeCount = 0

    override suspend fun propose(request: DeveloperRequest): DeveloperResult {
        proposeCount++
        return if (refuseAll) {
            DeveloperResult.Refused(net.kigawa.kivra.developer.DeveloperRefusal.INVALID_OUTPUT)
        } else {
            DeveloperResult.Proposed(
                patch = net.kigawa.kivra.core.patch.PatchSet(
                    files = emptyList(),
                    summary = "Auto-fix patch",
                ),
                usage = DeveloperUsage(1, Usage(0, 0, 0), emptyList()),
            )
        }
    }
}

/** Fake CodeIssueScanner for testing. */
class FakeCodeIssueScanner(
    private val errors: List<CodeIssue> = emptyList(),
    private val failures: List<CodeIssue> = emptyList(),
) : CodeIssueScanner {
    override fun getCompilationErrors(): List<CodeIssue> = errors
    override fun getTestFailures(): List<CodeIssue> = failures
    override fun readFileContent(path: String): String = "// Fake content for $path"
}
