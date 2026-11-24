package org.springframework.ai.anthropic;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

public abstract class AnthropicChatModel implements ChatModel {
    public static Builder builder() { return null; }

    public abstract static class Builder {
        public abstract Builder defaultOptions(ChatOptions.Builder<?> options);
        public abstract AnthropicChatModel build();
    }
}
