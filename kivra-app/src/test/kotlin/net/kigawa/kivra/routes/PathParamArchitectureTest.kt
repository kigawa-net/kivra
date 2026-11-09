package net.kigawa.kivra.routes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A path parameter is read only through [pathParam] (the route's own captures), never through `call.parameters`. That is the query first and
 * the path after, so a `?name=` with the same name hides the path's value (`/api/projects/A/members?projectId=B` would answer for B). A new
 * route that reads `call.parameters[...]` fails the build here.
 *
 * It reads the source, like the other architecture tests; it runs from the kivra-app directory. Comment lines are not code and are skipped.
 */
class PathParamArchitectureTest {
    private val routes = File("src/main/kotlin/net/kigawa/kivra/routes")

    /** `call.parameters` in any form, and a bare `parameters[` / `parameters.get...` (an extension that reads the same merged map). */
    private val merged = Regex("""\bcall\.parameters\b|(?<![\w.])parameters\[|(?<![\w.])parameters\.(get|getOrFail|getAll)\(""")

    @Test
    fun `routes read path parameters only through pathParam, never through the merged call parameters`() {
        assertTrue(routes.isDirectory, "run from the kivra-app directory: ${routes.absolutePath}")
        val offenders = routes.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.trim()
                !code.startsWith("*") && !code.startsWith("/*") && !code.startsWith("//") && merged.containsMatchIn(line)
            }.map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }.toList()
        assertEquals(emptyList(), offenders, "use pathParam(\"name\") (PathParams.kt): call.parameters lets a same-named query shadow the path")
    }
}
