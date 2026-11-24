package dev.llmlint.sample

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.stereotype.Service

@Service
class TicketAssistant(builder: ChatClient.Builder) {

    private val chatClient: ChatClient = builder.build()

    /** BUG (intentional): Kotlin call without an output limit -> LLM Lint warns on call(). */
    fun draftReply(ticket: String): String? =
        chatClient.prompt()
            .user("Draft a reply to this ticket:\n$ticket")
            .call()
            .content()

    /** OK: limit set on the request. */
    fun title(ticket: String): String? =
        chatClient.prompt()
            .user("Give this ticket a short title:\n$ticket")
            .options(ChatOptions.builder().maxTokens(20))
            .call()
            .content()
}
