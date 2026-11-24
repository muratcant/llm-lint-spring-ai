package dev.llmlint.springai

/** Fully qualified names of the Spring AI 2.x API the inspections understand. */
object SpringAiNames {
    const val CHAT_CLIENT = "org.springframework.ai.chat.client.ChatClient"
    const val CHAT_CLIENT_BUILDER = "org.springframework.ai.chat.client.ChatClient.Builder"
    const val CHAT_CLIENT_REQUEST_SPEC = "org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec"
    const val CHAT_MODEL = "org.springframework.ai.chat.model.ChatModel"
    const val CHAT_OPTIONS = "org.springframework.ai.chat.prompt.ChatOptions"
    const val CHAT_OPTIONS_BUILDER = "org.springframework.ai.chat.prompt.ChatOptions.Builder"
    const val PROMPT = "org.springframework.ai.chat.prompt.Prompt"

    /** Package of every Spring AI class; provider option builders live below it. */
    const val SPRING_AI_PACKAGE_PREFIX = "org.springframework.ai."

    const val SPRING_BEAN = "org.springframework.context.annotation.Bean"
    const val SPRING_PRIMARY = "org.springframework.context.annotation.Primary"
    const val SPRING_QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier"

    // Chat memory: the 1.x/2.0 API (deprecated for removal in 2.1) ...
    const val MESSAGE_WINDOW_CHAT_MEMORY_BUILDER = "org.springframework.ai.chat.memory.MessageWindowChatMemory.Builder"

    // ... and its 2.1 replacement, session memory (spring-ai-session, spring-ai-session-advisor).
    const val EVENT_FILTER = "org.springframework.ai.session.EventFilter"
    const val EVENT_FILTER_BUILDER = "org.springframework.ai.session.EventFilter.Builder"
    const val SESSION_MEMORY_ADVISOR = "org.springframework.ai.session.advisor.SessionMemoryAdvisor"
    const val SESSION_MEMORY_ADVISOR_BUILDER = "org.springframework.ai.session.advisor.SessionMemoryAdvisor.Builder"
    const val SESSION_ADVISORS = "org.springframework.ai.session.advisor.SessionAdvisors"
    const val SESSION_ADVISORS_BUILDER = "org.springframework.ai.session.advisor.SessionAdvisors.Builder"
    const val SESSION_TURN_ADVISOR = "org.springframework.ai.session.advisor.SessionTurnAdvisor"
    const val SESSION_TURN_ADVISOR_BUILDER = "org.springframework.ai.session.advisor.SessionTurnAdvisor.Builder"

    /** Context key of a per-request EventFilter (`a.param(EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(10))`). */
    const val EVENT_FILTER_CONTEXT_KEY = "EVENT_FILTER_CONTEXT_KEY"

    /**
     * Builder setters that put a cap on the generated output, across providers:
     * maxTokens (generic, Anthropic, ...), maxCompletionTokens (OpenAI reasoning models),
     * maxOutputTokens (Google GenAI), numPredict (Ollama).
     */
    val OUTPUT_LIMIT_SETTERS = setOf("maxTokens", "maxCompletionTokens", "maxOutputTokens", "numPredict")
}
