package dev.llmlint.springai.config

import dev.llmlint.springai.inspections.Coverage
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

    fun testValues() {
        assertEquals(Coverage.SAFE, SpringAiConfigFiles.valueCoverage("512"))
        assertEquals(Coverage.SAFE, SpringAiConfigFiles.valueCoverage("\"512\""))
        assertEquals(Coverage.UNSAFE, SpringAiConfigFiles.valueCoverage("0"))
        assertEquals(Coverage.UNSAFE, SpringAiConfigFiles.valueCoverage("-1"))
        assertEquals(Coverage.SAFE, SpringAiConfigFiles.valueCoverage("\${MAX_TOKENS:512}"))
        assertEquals(Coverage.SAFE, SpringAiConfigFiles.valueCoverage("\${A:\${B:64}}"))
        assertEquals(Coverage.UNSAFE, SpringAiConfigFiles.valueCoverage("\${MAX_TOKENS:-1}"))
        assertEquals(Coverage.UNKNOWN, SpringAiConfigFiles.valueCoverage("\${MAX_TOKENS}"))
        assertEquals(Coverage.UNKNOWN, SpringAiConfigFiles.valueCoverage("lots"))
        assertNull(SpringAiConfigFiles.valueCoverage("~"))
        assertNull(SpringAiConfigFiles.valueCoverage(""))
    }

    fun testProfileFilesAndDocumentsAreProfileSpecific() {
        val yaml = SpringAiConfigFiles.settingsIn(
            "application.yml",
            """
            spring.ai.openai.chat.options.max-tokens: 300
            ---
            spring:
              config:
                activate:
                  on-profile: test
              ai.anthropic.chat.max-tokens: 0
            """.trimIndent(),
            inTestSources = false,
        )
        assertEquals(
            listOf(
                SpringAiConfigFiles.ConfigLimit("spring.ai.openai.chat", Coverage.SAFE, profileSpecific = false, inTestSources = false),
                SpringAiConfigFiles.ConfigLimit("spring.ai.anthropic.chat", Coverage.UNSAFE, profileSpecific = true, inTestSources = false),
            ),
            yaml,
        )

        val properties = SpringAiConfigFiles.settingsIn(
            "application.properties",
            """
            spring.ai.openai.chat.max-completion-tokens=800
            #---
            spring.config.activate.on-profile=dev
            spring.ai.openai.chat.options.max-tokens=0
            """.trimIndent(),
            inTestSources = false,
        )
        assertEquals(listOf(false, true), properties.map { it.profileSpecific })

        val profileFile = SpringAiConfigFiles.settingsIn("application-prod.yml", "spring.ai.ollama.chat.options.numPredict: 256", false)
        assertEquals(listOf(true), profileFile.map { it.profileSpecific })
        assertEquals(listOf("spring.ai.ollama.chat"), profileFile.map { it.providerPrefix })
    }

    fun testResolve() {
        fun limit(prefix: String, value: Coverage, profile: Boolean = false) =
            SpringAiConfigFiles.ConfigLimit("spring.ai.$prefix.chat", value, profile, false)
        val openAi = listOf("spring.ai.openai.chat")
        val both = listOf("spring.ai.openai.chat", "spring.ai.anthropic.chat")

        assertEquals(Coverage.UNSAFE, SpringAiConfigFiles.resolve(emptyList(), openAi))
        assertEquals(Coverage.SAFE, SpringAiConfigFiles.resolve(listOf(limit("openai", Coverage.SAFE)), openAi))
        // Another provider's limit does not count; an unmapped provider's limit might.
        assertEquals(Coverage.UNSAFE, SpringAiConfigFiles.resolve(listOf(limit("anthropic", Coverage.SAFE)), openAi))
        assertEquals(Coverage.UNKNOWN, SpringAiConfigFiles.resolve(listOf(limit("mistralai", Coverage.SAFE)), openAi))
        // Every candidate provider must be covered.
        assertEquals(Coverage.UNKNOWN, SpringAiConfigFiles.resolve(listOf(limit("openai", Coverage.SAFE)), both))
        assertEquals(
            Coverage.SAFE,
            SpringAiConfigFiles.resolve(listOf(limit("openai", Coverage.SAFE), limit("anthropic", Coverage.SAFE)), both),
        )
        // Profiles may or may not be active.
        assertEquals(Coverage.UNKNOWN, SpringAiConfigFiles.resolve(listOf(limit("openai", Coverage.SAFE, profile = true)), openAi))
        assertEquals(
            Coverage.SAFE,
            SpringAiConfigFiles.resolve(listOf(limit("openai", Coverage.SAFE), limit("openai", Coverage.SAFE, profile = true)), openAi),
        )
        // Provider unknown: any provider's limit is enough (behaviour before provider matching).
        assertEquals(Coverage.SAFE, SpringAiConfigFiles.resolve(listOf(limit("anthropic", Coverage.SAFE)), emptyList()))
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
