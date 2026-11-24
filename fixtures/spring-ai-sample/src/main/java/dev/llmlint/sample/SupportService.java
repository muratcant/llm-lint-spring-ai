package dev.llmlint.sample;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class SupportService {

    private final ChatClient chatClient;

    public SupportService(ChatClient supportChatClient) {
        this.chatClient = supportChatClient;
    }

    /** BUG (intentional): no output limit anywhere -> LLM Lint warns on call(). */
    public String answer(String conversationId, String question) {
        return chatClient.prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    /** BUG (intentional): same for streaming. */
    public Flux<String> answerStreaming(String question) {
        return chatClient.prompt(question)
                .stream()
                .content();
    }
}
