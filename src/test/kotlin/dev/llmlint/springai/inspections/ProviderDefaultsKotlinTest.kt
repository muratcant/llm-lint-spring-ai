package dev.llmlint.springai.inspections

/** Missing output limit vs. the provider default limits in provider-defaults.json (Kotlin). */
class ProviderDefaultsKotlinTest : SpringAiInspectionTestCase() {

    // Each test chooses which providers are on the classpath.
    override val providerStubs: List<String> = emptyList()

    private fun autoConfiguredClient(method: String) = """
        import org.springframework.ai.chat.client.ChatClient

        class Svc(builder: ChatClient.Builder) {
            private val chatClient: ChatClient = builder.build()

            fun ask(question: String): String? =
                chatClient.prompt().user(question).$method().content()
        }
        """

    fun testAnthropicOnlyIsNotReported() {
        addProviderStubs("anthropic")
        checkFile("Svc.kt", autoConfiguredClient("call"))
    }

    fun testOpenAiOnlyIsReported() {
        addProviderStubs("openai")
        checkFile("Svc.kt", autoConfiguredClient(warn()))
    }

    fun testBothProvidersWithUnknownModelAreNotReported() {
        addProviderStubs("anthropic")
        addProviderStubs("openai")
        checkFile("Svc.kt", autoConfiguredClient("call"))
    }

    fun testOptionOnReportsAnthropicDefaultAsWeakWarning() {
        addProviderStubs("anthropic")
        myFixture.disableInspections(MissingOutputLimitInspection())
        myFixture.enableInspections(MissingOutputLimitInspection().apply { reportProviderDefaults = true })
        checkFileWithWeakWarnings("Svc.kt", autoConfiguredClient(weakProviderDefault("Anthropic", 4096)))
    }
}
