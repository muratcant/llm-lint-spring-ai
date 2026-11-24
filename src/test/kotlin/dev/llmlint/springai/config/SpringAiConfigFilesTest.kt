package dev.llmlint.springai.config

import junit.framework.TestCase

class SpringAiConfigFilesTest : TestCase() {

    fun testNestedAndDottedKeys() {
        val entries = SpringAiConfigFiles.yamlEntries(
            """
            spring:
              ai.openai:
                api-key: "abc # not a comment"
                chat:
                  options:
                    max-tokens: 500 # trailing comment
              retry:
                max-attempts: 3
            server.port: 8080
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "spring.ai.openai.api-key" to "\"abc # not a comment\"",
                "spring.ai.openai.chat.options.max-tokens" to "500",
                "spring.retry.max-attempts" to "3",
                "server.port" to "8080",
            ),
            entries,
        )
    }

    fun testDocumentSeparatorResetsNesting() {
        val entries = SpringAiConfigFiles.yamlEntries(
            """
            spring:
              profiles: dev
            ---
            spring:
              ai:
                retry:
                  max-attempts: 2
            """.trimIndent(),
        )
        assertEquals(
            listOf("spring.profiles" to "dev", "spring.ai.retry.max-attempts" to "2"),
            entries,
        )
    }

    fun testListsAreSkipped() {
        val entries = SpringAiConfigFiles.yamlEntries(
            """
            spring:
              ai:
                retry:
                  on-http-codes:
                    - 429
                    - 503
                  max-attempts: 4
            """.trimIndent(),
        )
        assertEquals(listOf("spring.ai.retry.max-attempts" to "4"), entries)
    }
}
