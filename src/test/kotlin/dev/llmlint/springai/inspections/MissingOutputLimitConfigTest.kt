package dev.llmlint.springai.inspections

/** The third source of a limit: Spring Boot configuration files. */
class MissingOutputLimitConfigTest : SpringAiInspectionTestCase() {

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

    fun testNestedYaml() {
        addConfig(
            "application.yml",
            """
            spring:
              ai:
                openai:
                  api-key: ${'$'}{OPENAI_API_KEY}
                  chat:
                    options:
                      model: gpt-5-mini
                      max-tokens: 500
            """,
        )
        service(expectWarning = false)
    }

    fun testDottedYamlKeyAndProfileFile() {
        addConfig(
            "application-prod.yaml",
            """
            spring.ai.anthropic.chat.options.maxTokens: 1024
            """,
        )
        service(expectWarning = false)
    }

    fun testProperties() {
        addConfig(
            "application.properties",
            """
            spring.ai.ollama.chat.options.num-predict=256
            """,
        )
        service(expectWarning = false)
    }

    fun testOpenAiMaxCompletionTokens() {
        addConfig(
            "application.yml",
            """
            spring:
              ai:
                openai:
                  chat:
                    options:
                      max-completion-tokens: 800
            """,
        )
        service(expectWarning = false)
    }

    fun testYamlWithoutLimit() {
        addConfig(
            "application.yml",
            """
            spring:
              ai:
                openai:
                  chat:
                    options:
                      model: gpt-5-mini
                      temperature: 0.2
                retry:
                  max-attempts: 3
            """,
        )
        service(expectWarning = true)
    }

    fun testCommentedOutLimit() {
        addConfig(
            "application.yml",
            """
            spring:
              ai:
                openai:
                  chat:
                    options:
                      model: gpt-5-mini
                      # max-tokens: 500
            """,
        )
        service(expectWarning = true)
    }

    fun testLimitUnderAnotherKeyDoesNotCount() {
        addConfig(
            "application.yml",
            """
            app:
              spring:
                ai:
                  openai:
                    chat:
                      options:
                        max-tokens: 500
            """,
        )
        service(expectWarning = true)
    }

    fun testOtherFileNamesAreIgnored() {
        addConfig(
            "prompts.yml",
            """
            spring:
              ai:
                openai:
                  chat:
                    options:
                      max-tokens: 500
            """,
        )
        service(expectWarning = true)
    }
}
