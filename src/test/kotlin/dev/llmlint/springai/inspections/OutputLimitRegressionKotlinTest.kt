package dev.llmlint.springai.inspections

/** Kotlin counterparts of [OutputLimitRegressionJavaTest] (conservative mode, OpenAI on the classpath). */
class OutputLimitRegressionKotlinTest : SpringAiInspectionTestCase() {

    fun testRequestOptionsReplaceClientDefaults() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(builder: ChatClient.Builder) {
            private val chatClient: ChatClient = builder.defaultOptions(ChatOptions.builder().maxTokens(500)).build()

            fun ask(question: String): String? =
                chatClient.prompt().user(question)
                    .options(ChatOptions.builder().temperature(0.2))
                    .${warnReplaced()}()
                    .content()
        }
        """,
    )

    fun testLastOptionsCallWins() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(private val builder: ChatClient.Builder) {
            fun ask(question: String): String? =
                builder.build().prompt().user(question)
                    .options(ChatOptions.builder().maxTokens(256))
                    .options(ChatOptions.builder().temperature(0.2))
                    .${warn()}()
                    .content()
        }
        """,
    )

    /** The app's own maxTokens() is not a Spring AI options setter. */
    fun testUnrelatedMaxTokensMethodDoesNotCount() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient

        class Limits {
            fun maxTokens(): Int = 100
        }

        class Svc(builder: ChatClient.Builder, limits: Limits) {
            private val chatClient: ChatClient =
                builder.defaultSystem("Answer in at most " + limits.maxTokens() + " tokens").build()

            fun ask(question: String): String? =
                chatClient.prompt().user(question).${warn()}().content()
        }
        """,
    )

    // ------------------------------------------------ alternative paths

    fun testIfExpressionWithOneLimitedBranchIsPartial() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(builder: ChatClient.Builder, brief: Boolean) {
            private val chatClient: ChatClient =
                if (brief) builder.defaultOptions(ChatOptions.builder().maxTokens(100)).build() else builder.build()

            fun ask(question: String): String? =
                chatClient.prompt().user(question).${warnPartial()}().content()
        }
        """,
    )

    fun testWhenWithOneLimitedBranchIsPartial() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(private val builder: ChatClient.Builder) {
            fun ask(question: String, mode: Int): String? =
                builder.build().prompt().user(question).options(options(mode)).${warnPartial()}().content()

            private fun options(mode: Int): ChatOptions.Builder<*> = when (mode) {
                0 -> ChatOptions.builder().maxTokens(64)
                else -> ChatOptions.builder().temperature(0.5)
            }
        }
        """,
    )

    fun testWhenWithAllBranchesLimitedIsSafe() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(private val builder: ChatClient.Builder) {
            fun ask(question: String, mode: Int): String? =
                builder.build().prompt().user(question).options(options(mode)).call().content()

            private fun options(mode: Int): ChatOptions.Builder<*> = when (mode) {
                0 -> ChatOptions.builder().maxTokens(64)
                else -> ChatOptions.builder().maxTokens(512)
            }
        }
        """,
    )

    /** `a ?: b`: both sides are alternatives; a limited left side does not cover an unlimited right side. */
    fun testElvisWithUnlimitedFallbackIsPartial() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(private val builder: ChatClient.Builder) {
            private var preset: ChatOptions.Builder<*>? = ChatOptions.builder().maxTokens(32)

            fun ask(question: String): String? =
                builder.build().prompt().user(question)
                    .options(preset ?: ChatOptions.builder().temperature(0.1))
                    .${warnPartial()}()
                    .content()
        }
        """,
    )

    /** The same helper reached from both branches gives the same answer the second time. */
    fun testSameHelperInBothBranchesIsSafe() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(builder: ChatClient.Builder, brief: Boolean) {
            private val chatClient: ChatClient =
                if (brief) limited(builder).defaultSystem("Be brief").build() else limited(builder).build()

            private fun limited(builder: ChatClient.Builder): ChatClient.Builder =
                builder.defaultOptions(ChatOptions.builder().maxTokens(100))

            fun ask(question: String): String? =
                chatClient.prompt().user(question).call().content()
        }
        """,
    )

    fun testConditionalRequestOptionsArePartial() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(private val builder: ChatClient.Builder) {
            fun ask(question: String, brief: Boolean): String? {
                val spec = builder.build().prompt().user(question)
                if (brief) {
                    spec.options(ChatOptions.builder().maxTokens(64))
                }
                return spec.${warnPartial()}().content()
            }
        }
        """,
    )

    // ------------------------------------------------ multiple beans

    private fun twoBeans() = myFixture.addFileToProject(
        "AiConfig.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions
        import org.springframework.context.annotation.Bean
        import org.springframework.context.annotation.Configuration

        @Configuration
        class AiConfig {
            @Bean
            fun limitedClient(builder: ChatClient.Builder): ChatClient =
                builder.defaultOptions(ChatOptions.builder().maxTokens(800)).build()

            @Bean
            fun supportClient(builder: ChatClient.Builder): ChatClient =
                builder.defaultSystem("You are a support agent").build()
        }
        """.trimIndent(),
    )

    /** Spring picks the bean whose name matches the constructor property. */
    fun testBeanSelectedByConstructorPropertyName() {
        twoBeans()
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient

            class Support(private val supportClient: ChatClient) {
                fun ask(question: String): String? =
                    supportClient.prompt().user(question).${warn()}().content()
            }

            class Brief(private val limitedClient: ChatClient) {
                fun ask(question: String): String? =
                    limitedClient.prompt().user(question).call().content()
            }
            """,
        )
    }

    fun testBeanSelectedByQualifier() {
        twoBeans()
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient
            import org.springframework.beans.factory.annotation.Qualifier

            class Support(@Qualifier("supportClient") private val client: ChatClient) {
                fun ask(question: String): String? =
                    client.prompt().user(question).${warn()}().content()
            }

            class Brief(@Qualifier("limitedClient") private val client: ChatClient) {
                fun ask(question: String): String? =
                    client.prompt().user(question).call().content()
            }
            """,
        )
    }

    /** No name or qualifier match and no @Primary: Spring's choice is unclear, so nothing is reported. */
    fun testAmbiguousBeansAreNotReported() {
        twoBeans()
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient

            class Svc(private val client: ChatClient) {
                fun ask(question: String): String? =
                    client.prompt().user(question).call().content()
            }
            """,
        )
    }

    // ------------------------------------------------ configuration

    /** Only OpenAI is on the classpath: an Anthropic limit in the configuration does not apply. */
    fun testConfigLimitForAnotherProviderDoesNotCount() {
        addConfig("application.yml", "spring.ai.anthropic.chat.options.max-tokens: 1024")
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient

            class Svc(private val builder: ChatClient.Builder) {
                fun ask(question: String): String? =
                    builder.build().prompt().user(question).${warn()}().content()
            }
            """,
        )
    }

    fun testConfigLimitForTheProviderCounts() {
        addConfig("application.yml", "spring.ai.openai.chat.options.max-tokens: 1024")
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient

            class Svc(private val builder: ChatClient.Builder) {
                fun ask(question: String): String? =
                    builder.build().prompt().user(question).call().content()
            }
            """,
        )
    }

    /** A provider builder setter (OpenAiChatOptions.Builder) counts. */
    fun testProviderSetterInRequestOptionsCounts() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions
        import org.springframework.ai.openai.OpenAiChatOptions

        class Svc(builder: ChatClient.Builder) {
            private val chatClient: ChatClient = builder.defaultOptions(ChatOptions.builder().temperature(0.1)).build()

            fun ask(question: String): String? =
                chatClient.prompt().user(question)
                    .options(OpenAiChatOptions.builder().maxCompletionTokens(800))
                    .call()
                    .content()
        }
        """,
    )
}
