package dev.llmlint.springai.inspections

/** Missing output limit vs. the provider default limits in provider-defaults.json (Java). */
class ProviderDefaultsJavaTest : SpringAiInspectionTestCase() {

    // Each test chooses which providers are on the classpath.
    override val providerStubs: List<String> = emptyList()

    /** A ChatClient from the auto-configured builder: the code does not show which model it uses. */
    private fun autoConfiguredClient(method: String) = """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                this.chatClient = builder.build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question).$method().content();
            }
        }
        """

    fun testAnthropicOnlyIsNotReported() {
        addProviderStubs("anthropic")
        checkFile("Svc.java", autoConfiguredClient("call"))
    }

    fun testOpenAiOnlyIsReported() {
        addProviderStubs("openai")
        checkFile("Svc.java", autoConfiguredClient(warn()))
    }

    fun testBothProvidersWithUnknownModelAreNotReported() {
        addProviderStubs("anthropic")
        addProviderStubs("openai")
        checkFile("Svc.java", autoConfiguredClient("call"))
    }

    fun testBothProvidersWithOpenAiModelInCodeIsReported() {
        addProviderStubs("anthropic")
        addProviderStubs("openai")
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.chat.client.ChatClient;
            import org.springframework.ai.openai.OpenAiChatModel;

            class Svc {
                String ask(OpenAiChatModel model, String question) {
                    return ChatClient.create(model).prompt().user(question).${warn()}().content();
                }
            }
            """,
        )
    }

    fun testBothProvidersWithAnthropicModelInCodeIsNotReported() {
        addProviderStubs("anthropic")
        addProviderStubs("openai")
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.anthropic.AnthropicChatModel;
            import org.springframework.ai.chat.client.ChatClient;

            class Svc {
                String ask(AnthropicChatModel model, String question) {
                    return ChatClient.builder(model).build().prompt().user(question).call().content();
                }
            }
            """,
        )
    }

    fun testOptionOnReportsAnthropicDefaultAsWeakWarning() {
        addProviderStubs("anthropic")
        enableReportProviderDefaults()
        checkFileWithWeakWarnings("Svc.java", autoConfiguredClient(weakProviderDefault("Anthropic", 4096)))
    }

    fun testOptionOnStaysSilentWithExplicitLimit() {
        addProviderStubs("anthropic")
        enableReportProviderDefaults()
        checkFileWithWeakWarnings(
            "Svc.java",
            """
            import org.springframework.ai.anthropic.AnthropicChatOptions;
            import org.springframework.ai.chat.client.ChatClient;

            class Svc {
                String ask(ChatClient.Builder builder, String question) {
                    return builder.build().prompt()
                            .user(question)
                            .options(AnthropicChatOptions.builder().maxTokens(1024))
                            .call()
                            .content();
                }
            }
            """,
        )
    }

    fun testOptionOnStaysSilentWhenProvidersAreMixed() {
        addProviderStubs("anthropic")
        addProviderStubs("openai")
        enableReportProviderDefaults()
        checkFileWithWeakWarnings("Svc.java", autoConfiguredClient("call"))
    }

    private fun enableReportProviderDefaults() {
        myFixture.disableInspections(MissingOutputLimitInspection())
        myFixture.enableInspections(MissingOutputLimitInspection().apply { reportProviderDefaults = true })
    }
}
