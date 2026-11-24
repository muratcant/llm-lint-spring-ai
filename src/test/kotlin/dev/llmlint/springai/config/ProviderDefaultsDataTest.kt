package dev.llmlint.springai.config

import junit.framework.TestCase

/** Guards provider-defaults.json: every row is complete, and only documented values are present. */
class ProviderDefaultsDataTest : TestCase() {

    fun testEveryRowHasItsMetadata() {
        val rows = ProviderDefaults.all
        assertTrue("provider-defaults.json is empty or missing", rows.isNotEmpty())
        for (row in rows) {
            assertTrue(row.provider, row.provider.isNotBlank())
            assertTrue(row.provider, row.chatModelClass.startsWith("org.springframework.ai."))
            assertTrue(row.provider, row.source.orEmpty().startsWith("https://docs.spring.io/spring-ai/"))
            assertFalse(row.provider, row.verifiedFor.isNullOrBlank())
            assertFalse(row.provider, row.property.isNullOrBlank())
        }
    }

    fun testAnthropicDefaultIs4096() {
        val anthropic = ProviderDefaults.all.single { it.provider == "Anthropic" }
        assertEquals("org.springframework.ai.anthropic.AnthropicChatModel", anthropic.chatModelClass)
        assertEquals(4096, anthropic.defaultMaxTokens)
    }

    fun testVerdict() {
        val anthropic = ProviderDefaults.all.single { it.provider == "Anthropic" }
        val openAi = ProviderDefaults.all.single { it.provider == "OpenAI" }
        val azure = ProviderDefaults.all.single { it.provider == "Azure OpenAI" }

        assertEquals(ProviderVerdict.NoKnownProvider, ProviderDefaults.verdict(emptyList()))
        assertEquals(ProviderVerdict.AllDefaulted(listOf(anthropic)), ProviderDefaults.verdict(listOf(anthropic)))
        assertEquals(ProviderVerdict.NoDefault, ProviderDefaults.verdict(listOf(openAi, azure)))
        assertEquals(ProviderVerdict.Mixed, ProviderDefaults.verdict(listOf(anthropic, openAi)))
    }
}
