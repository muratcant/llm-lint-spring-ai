package dev.llmlint.sample;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

    /**
     * BUG (intentional): unbounded chat memory. Every turn re-sends the whole history,
     * so a 50-turn support chat sends hundreds of thousands of input tokens.
     */
    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(Integer.MAX_VALUE)
                .build();
    }

    /**
     * BUG (intentional): no defaultOptions(...) with maxTokens, and application.yml has none either.
     */
    @Bean
    ChatClient supportChatClient(ChatClient.Builder builder, ChatMemory chatMemory, OrderTools orderTools) {
        return builder
                .defaultSystem("You are a helpful support agent for an online shop.")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(orderTools)
                .build();
    }
}
