package net.kigawa.kivra.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessCodeIssueScannerTest {

    private fun tempRepo(): Path = Files.createTempDirectory("kivra-scan")

    @Test
    fun `parses new and old kotlin compiler formats`() {
        val out = """
            > Task :compileKotlin FAILED
            e: file:///repo/src/main/kotlin/Foo.kt:12:34 Unresolved reference: bar
            e: /repo/src/main/kotlin/Baz.kt: (7, 3): Type mismatch
            w: file:///repo/src/main/kotlin/Foo.kt:1:1 warning only
            e: file:///repo/src/main/kotlin/Foo.kt:12:34 Unresolved reference: bar
        """.trimIndent()
        val errors = KotlinCompilerOutputParser.parse(out)
        assertEquals(2, errors.size)
        assertEquals(KotlinCompilerOutputParser.Error("/repo/src/main/kotlin/Foo.kt", 12, "Unresolved reference: bar"), errors[0])
        assertEquals(7, errors[1].line)
    }

    @Test
    fun `parses junit failures and errors only`() {
        val xml = """
            <testsuite name="T">
              <testcase classname="a.b.FooTest" name="ok"/>
              <testcase classname="a.b.FooTest" name="bad"><failure message="expected 1 but was 2">trace</failure></testcase>
              <testcase classname="a.b.FooTest" name="boom"><error>NPE
              at x</error></testcase>
            </testsuite>
        """.trimIndent()
        val failures = JUnitXmlReportParser.parse(xml)
        assertEquals(listOf("bad", "boom"), failures.map { it.testName })
        assertEquals("expected 1 but was 2", failures[0].message)
        assertEquals("NPE", failures[1].message)
    }

    @Test
    fun `junit parser rejects doctype`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><testsuite/>"""
        try {
            JUnitXmlReportParser.parse(xml)
            throw AssertionError("expected failure")
        } catch (_: org.xml.sax.SAXException) {
        }
    }

    @Test
    fun `compilation errors are reported relative to the repo and outside paths dropped`() {
        val root = tempRepo()
        val out = "e: file://$root/src/Foo.kt:3:1 boom\ne: file:///elsewhere/Evil.kt:1:1 nope"
        val scanner = ProcessCodeIssueScanner(root, listOf("build"), { _, _, _ -> CommandResult(1, out) })
        val issues = scanner.getCompilationErrors()
        assertEquals(1, issues.size)
        assertEquals("src/Foo.kt", issues[0].filePath)
        assertEquals(3, issues[0].lineNumber)
        assertEquals(IssueType.COMPILATION_ERROR, issues[0].type)
        assertEquals(listOf("src/Foo.kt"), issues[0].relatedFiles)
    }

    @Test
    fun `successful build and runner failure yield no issues`() {
        val root = tempRepo()
        assertTrue(ProcessCodeIssueScanner(root, listOf("b"), { _, _, _ -> CommandResult(0, "e: file://$root/A.kt:1:1 x") }).getCompilationErrors().isEmpty())
        assertTrue(ProcessCodeIssueScanner(root, listOf("b"), { _, _, _ -> error("no gradle") }).getCompilationErrors().isEmpty())
    }

    @Test
    fun `test failures are read from reports and mapped to source`() {
        val root = tempRepo()
        Files.createDirectories(root.resolve("m/src/test/kotlin/a/b"))
        Files.writeString(root.resolve("m/src/test/kotlin/a/b/FooTest.kt"), "class FooTest")
        Files.createDirectories(root.resolve("m/build/test-results/test"))
        Files.writeString(
            root.resolve("m/build/test-results/test/TEST-a.b.FooTest.xml"),
            """<testsuite><testcase classname="a.b.FooTest" name="bad"><failure message="m"/></testcase></testsuite>""",
        )
        val issues = ProcessCodeIssueScanner(root, listOf("b")).getTestFailures()
        assertEquals(1, issues.size)
        assertEquals(IssueType.TEST_FAILURE, issues[0].type)
        assertEquals("m/src/test/kotlin/a/b/FooTest.kt", issues[0].filePath)
    }

    @Test
    fun `readFileContent is confined to the repo and size limited`() {
        val root = tempRepo()
        Files.writeString(root.resolve("a.kt"), "hello")
        Files.writeString(root.resolve("big.kt"), "x".repeat(100))
        val scanner = ProcessCodeIssueScanner(root, listOf("b"), maxFileBytes = 50)
        assertEquals("hello", scanner.readFileContent("a.kt"))
        assertEquals("", scanner.readFileContent("big.kt"))
        assertEquals("", scanner.readFileContent("../etc/passwd"))
        assertEquals("", scanner.readFileContent("/etc/passwd"))
        assertEquals("", scanner.readFileContent("missing.kt"))
    }
}
