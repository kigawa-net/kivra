package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertTrue

class PlannerPromptTest {
    @Test
    fun theSystemPromptKeepsTheTaskSizeRule() {
        val system = PlannerPrompt.system()
        assertTrue(system.contains("TASK SIZE"))
        assertTrue(system.contains("do not read or rewrite a whole large file (about 50 KB or 1,000 lines or more)"))
        assertTrue(system.contains("split into several smaller tasks"))
        assertTrue(system.contains("Keep the number of files that one task touches small."))
    }

    @Test
    fun theSystemPromptKeepsTheSchemaRules() {
        val system = PlannerPrompt.system()
        assertTrue(system.contains("\"sizeEstimate\":\"S\",\"dependsOn\":[]}]}"))
        assertTrue(system.contains("Use only these keys. Unknown keys are rejected. Do not add any other key."))
        assertTrue(system.contains("Do not plan changes to CI workflows, build files, dependency declarations, database migrations, ADRs, or credentials."))
    }

    @Test
    fun theRepoFilePromptForbidsRecreatingListedFiles() {
        val with = PlannerPrompt.system(repoFiles = true)
        assertTrue(with.contains("Never write a task that creates or rewrites such a file from scratch"))
        assertTrue(with.contains("You cannot see the file content."))
        assertTrue(!PlannerPrompt.system().contains("from scratch"))
    }
}
