package dev.llmlint.springai.inspections

import com.intellij.testFramework.PsiTestUtil

/**
 * Regression tests for configuration resolution (profiles, providers, values), conservative mode.
 * OpenAI is the only provider on the classpath unless a test adds another one.
 */
class ConfigResolutionRegressionTest : SpringAiInspectionTestCase() {

    private fun service(expectWarning: Boolean) = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                this.chatClient = builder.build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question).${if (expectWarning) warn() else "call"}().content();
            }
        }
        """,
    )

    /** Bug: a limit for another provider (Anthropic) silenced calls that can only run on OpenAI. */
    fun testLimitForAnotherProviderDoesNotCount() {
        addConfig(
            "application.yml",
            """
            spring:
              ai:
                anthropic:
                  chat:
                    options:
                      max-tokens: 1024
            """,
        )
        service(expectWarning = true)
    }

    /** A limit set by a provider missing from provider-defaults.json may apply to the call: not reported. */
    fun testLimitForUnmappedProviderIsNotReported() {
        addConfig(
            "application.yml",
            """
            spring.ai.mistralai.chat.options.max-tokens: 1024
            """,
        )
        service(expectWarning = false)
    }

    /**
     * `spring.profiles.active` in a file is usually overridden at runtime (environment, command line), so it is
     * not trusted: a limit set only in a profile file may apply, and the call is not reported.
     */
    fun testDeclaredActiveProfileIsNotTrusted() {
        addConfig(
            "application.yml",
            """
            spring:
              profiles:
                active: prod
            """,
        )
        addConfig(
            "application-test.yml",
            """
            spring.ai.openai.chat.options.max-tokens: 300
            """,
        )
        service(expectWarning = false)
    }

    fun testLimitInActiveProfileCounts() {
        addConfig(
            "application.yml",
            """
            spring:
              profiles:
                active: prod
            """,
        )
        addConfig(
            "application-prod.yml",
            """
            spring.ai.openai.chat.options.max-tokens: 300
            """,
        )
        service(expectWarning = false)
    }

    /** Profile-only limit with unknown active profiles: uncertain, so not reported in conservative mode. */
    fun testLimitOnlyInProfileWithUnknownActiveProfileIsNotReported() {
        addConfig(
            "application-prod.yml",
            """
            spring.ai.openai.chat.options.max-tokens: 300
            """,
        )
        service(expectWarning = false)
    }

    /** An `on-profile` document is profile-specific (see SpringAiConfigFilesTest): not reported, not proven safe. */
    fun testMultiDocumentProfileSectionIsNotReported() {
        addConfig(
            "application.yml",
            """
            spring:
              profiles:
                active: prod
            ---
            spring:
              config:
                activate:
                  on-profile: test
              ai:
                openai:
                  chat:
                    options:
                      max-tokens: 300
            """,
        )
        service(expectWarning = false)
    }

    /** A base limit and a profile that turns it off: either may be active, so not reported. */
    fun testProfileThatRemovesTheBaseLimitIsNotReported() {
        addConfig("application.yml", "spring.ai.openai.chat.options.max-tokens: 300")
        addConfig("application-dev.yml", "spring.ai.openai.chat.options.max-tokens: 0")
        service(expectWarning = false)
    }

    /** Bug: configuration under a test root silenced production code. */
    fun testTestResourcesDoNotCountForProductionCode() {
        val testRoot = myFixture.tempDirFixture.findOrCreateDir("testResources")
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        try {
            addConfig("testResources/application.yml", "spring.ai.openai.chat.options.max-tokens: 300")
            service(expectWarning = true)
        } finally {
            PsiTestUtil.removeSourceRoot(module, testRoot)
        }
    }

    /** The same test configuration does count for test code (and proves test roots are searched at all). */
    fun testTestResourcesCountForTestCode() {
        val testRoot = myFixture.tempDirFixture.findOrCreateDir("testResources")
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        try {
            addConfig("testResources/application.yml", "spring.ai.openai.chat.options.max-tokens: 300")
            val test = myFixture.addFileToProject(
                "testResources/SvcTest.java",
                """
                import org.springframework.ai.chat.client.ChatClient;

                class SvcTest {
                    String ask(ChatClient.Builder builder, String question) {
                        return builder.build().prompt().user(question).call().content();
                    }
                }
                """.trimIndent(),
            )
            myFixture.configureFromExistingVirtualFile(test.virtualFile)
            myFixture.checkHighlighting(true, false, false)
        } finally {
            PsiTestUtil.removeSourceRoot(module, testRoot)
        }
    }

    /** Bug: 0 (or Ollama's -1) was taken as a limit. */
    fun testNonPositiveValueIsNotALimit() {
        addConfig(
            "application.properties",
            """
            spring.ai.openai.chat.options.max-tokens=0
            """,
        )
        service(expectWarning = true)
    }

    fun testPlaceholderWithDefaultCounts() {
        addConfig(
            "application.yml",
            """
            spring.ai.openai.chat.options.max-tokens: ${'$'}{MAX_TOKENS:512}
            """,
        )
        service(expectWarning = false)
    }

    /** `${'$'}{X}` without a default: the value is unknown, so not reported in conservative mode. */
    fun testPlaceholderWithoutDefaultIsNotReported() {
        addConfig(
            "application.yml",
            """
            spring.ai.openai.chat.options.max-tokens: ${'$'}{MAX_TOKENS}
            """,
        )
        service(expectWarning = false)
    }

    /** OpenAI also accepts the shortcut without `.options`, as in the 2.1 reference docs. */
    fun testDocumentedShortcutKeyCounts() {
        addConfig(
            "application.properties",
            """
            spring.ai.openai.chat.max-completion-tokens=800
            """,
        )
        service(expectWarning = false)
    }
}

/** Ollama is the only provider: its documented default `num-predict: -1` means no limit. */
class OllamaConfigResolutionTest : SpringAiInspectionTestCase() {

    override val providerStubs: List<String> = listOf("ollama")

    private fun service(expectWarning: Boolean) = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                return builder.build().prompt().user(question).${if (expectWarning) warn() else "call"}().content();
            }
        }
        """,
    )

    fun testMinusOneMeansNoLimit() {
        addConfig("application.yml", "spring.ai.ollama.chat.options.num-predict: -1")
        service(expectWarning = true)
    }

    fun testPositiveNumPredictCounts() {
        addConfig("application.yml", "spring.ai.ollama.chat.options.num-predict: 512")
        service(expectWarning = false)
    }
}
