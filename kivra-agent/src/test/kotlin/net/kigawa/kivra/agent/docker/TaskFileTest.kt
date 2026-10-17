package net.kigawa.kivra.agent.docker

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskFileTest {
    private val fixture = DockerFixture()

    private fun read(path: java.nio.file.Path): JsonObject = Json.parseToJsonElement(Files.readString(path)).jsonObject

    @Test
    fun taskFileHasTheContractedPathAndFields() {
        val path = TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "Fix the parser", "Body text", truncated = false)
        assertEquals(fixture.workDir.resolve(".kivra").resolve("task.json"), path)
        assertEquals(".kivra/task.json", TaskFile.RELATIVE_PATH)
        val doc = read(path)
        assertEquals("1", doc["schemaVersion"]!!.jsonPrimitive.contentOrNull)
        assertEquals("UNTRUSTED", doc["trust"]!!.jsonPrimitive.contentOrNull)
        assertEquals(TEST_RUN_ID, doc["runId"]!!.jsonPrimitive.contentOrNull)
        assertEquals(TEST_ATTEMPT_ID, doc["attemptId"]!!.jsonPrimitive.contentOrNull)
        assertEquals("Fix the parser", doc["title"]!!.jsonPrimitive.contentOrNull)
        assertEquals("Body text", doc["body"]!!.jsonPrimitive.contentOrNull)
        assertEquals(false, doc["truncated"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(setOf("schemaVersion", "trust", "runId", "attemptId", "title", "body", "truncated"), doc.keys)
    }

    @Test
    fun jsonSpecialCharactersAreEscapedAndTheContentRoundTrips() {
        val body = "line1\n\"quoted\" \\ </script> \u0007 日本語"
        val path = TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "t", body, truncated = true)
        assertEquals(body, read(path)["body"]!!.jsonPrimitive.contentOrNull)
        assertEquals(true, read(path)["truncated"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun limitsAndIdsAreChecked() {
        assertFailsWith<DockerSpecException> {
            TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "x".repeat(TaskFile.MAX_TITLE_CHARS + 1), "", false)
        }
        assertFailsWith<DockerSpecException> {
            TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "t", "x".repeat(TaskFile.MAX_BODY_BYTES + 1), false)
        }
        // Multi-byte text counts in bytes for the body.
        assertFailsWith<DockerSpecException> {
            TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "t", "あ".repeat(TaskFile.MAX_BODY_BYTES / 3 + 1), false)
        }
        assertFailsWith<DockerSpecException> { TaskFile.write(fixture.workDir, "bad id", TEST_ATTEMPT_ID, "t", "b", false) }
        assertFailsWith<DockerSpecException> { TaskFile.write(fixture.workDir, TEST_RUN_ID, "../x", "t", "b", false) }
        assertFailsWith<DockerSpecException> {
            TaskFile.write(fixture.workDir.resolve("missing"), TEST_RUN_ID, TEST_ATTEMPT_ID, "t", "b", false)
        }
    }

    @Test
    fun symbolicLinksAtTheDirectoryOrTheFileAreRefused() {
        val outside = Files.createDirectories(fixture.root.resolve("outside"))
        Files.createSymbolicLink(fixture.workDir.resolve(".kivra"), outside)
        assertFailsWith<DockerSpecException> { TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "t", "b", false) }
        assertTrue(!Files.exists(outside.resolve("task.json")), "nothing written through the link")
    }

    @Test
    fun symbolicLinkAsTheTaskFileIsRefused() {
        val dir = Files.createDirectories(fixture.workDir.resolve(".kivra"))
        val target = fixture.root.resolve("elsewhere.json")
        Files.writeString(target, "{}")
        Files.createSymbolicLink(dir.resolve("task.json"), target)
        assertFailsWith<DockerSpecException> { TaskFile.write(fixture.workDir, TEST_RUN_ID, TEST_ATTEMPT_ID, "t", "b", false) }
        assertEquals("{}", Files.readString(target))
    }
}
