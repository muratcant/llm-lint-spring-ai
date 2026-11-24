package org.springframework.ai.chat.prompt;

public interface ChatOptions {
    Integer getMaxTokens();

    static Builder<?> builder() { return null; }

    interface Builder<B extends Builder<B>> {
        B model(String model);
        B maxTokens(Integer maxTokens);
        B temperature(Double temperature);
        ChatOptions build();
    }
}
