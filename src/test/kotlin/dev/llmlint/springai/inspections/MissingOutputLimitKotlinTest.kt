package dev.llmlint.springai.inspections

/** Same inspection through UAST on Kotlin sources. */
class MissingOutputLimitKotlinTest : SpringAiInspectionTestCase() {

    fun testClientFromInjectedBuilderWithoutLimit() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient

        class Svc(builder: ChatClient.Builder) {
            private val chatClient: ChatClient = builder.build()

            fun ask(question: String): String? =
                chatClient.prompt().user(question).${warn()}().content()
        }
        """,
    )

    fun testOptionsWithoutMaxTokens() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(private val builder: ChatClient.Builder) {
            fun ask(question: String): String? =
                builder.build().prompt()
                    .user(question)
                    .options(ChatOptions.builder().temperature(0.2))
                    .${warn()}()
                    .content()
        }
        """,
    )

    fun testBeanFunctionWithoutLimit() {
        myFixture.addFileToProject(
            "AiConfig.kt",
            """
            import org.springframework.ai.chat.client.ChatClient
            import org.springframework.context.annotation.Bean
            import org.springframework.context.annotation.Configuration

            @Configuration
            class AiConfig {
                @Bean
                fun supportClient(builder: ChatClient.Builder): ChatClient =
                    builder.defaultSystem("You are a support agent").build()
            }
            """.trimIndent(),
        )
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient

            class Svc(private val supportClient: ChatClient) {
                fun ask(question: String): String? =
                    supportClient.prompt().user(question).${warn()}().content()
            }
            """,
        )
    }

    fun testOptionsWithMaxTokens() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(builder: ChatClient.Builder) {
            private val chatClient = builder.build()

            fun ask(question: String): String? =
                chatClient.prompt()
                    .user(question)
                    .options(ChatOptions.builder().maxTokens(20))
                    .call()
                    .content()
        }
        """,
    )

    fun testDefaultOptionsInPropertyInitializer() = checkFile(
        "Svc.kt",
        """
        import org.springframework.ai.chat.client.ChatClient
        import org.springframework.ai.chat.prompt.ChatOptions

        class Svc(builder: ChatClient.Builder) {
            private val chatClient = builder
                .defaultOptions(ChatOptions.builder().maxTokens(300))
                .build()

            fun ask(question: String): String? =
                chatClient.prompt().user(question).call().content()
        }
        """,
    )

    fun testBeanFunctionWithLimit() {
        myFixture.addFileToProject(
            "AiConfig.kt",
            """
            import org.springframework.ai.chat.client.ChatClient
            import org.springframework.ai.chat.prompt.ChatOptions
            import org.springframework.context.annotation.Bean
            import org.springframework.context.annotation.Configuration

            @Configuration
            class AiConfig {
                @Bean
                fun supportClient(builder: ChatClient.Builder): ChatClient {
                    return builder.defaultOptions(ChatOptions.builder().maxTokens(600)).build()
                }
            }
            """.trimIndent(),
        )
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient

            class Svc(private val supportClient: ChatClient) {
                fun ask(question: String): String? =
                    supportClient.prompt().user(question).call().content()
            }
            """,
        )
    }

    fun testYamlLimitAppliesToKotlin() {
        addConfig(
            "application.yml",
            """
            spring:
              ai:
                openai:
                  chat:
                    options:
                      max-tokens: 500
            """,
        )
        checkFile(
            "Svc.kt",
            """
            import org.springframework.ai.chat.client.ChatClient

            class Svc(builder: ChatClient.Builder) {
                private val chatClient = builder.build()

                fun ask(question: String): String? =
                    chatClient.prompt().user(question).call().content()
            }
            """,
        )
    }
}
