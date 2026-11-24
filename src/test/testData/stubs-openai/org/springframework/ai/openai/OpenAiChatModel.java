package org.springframework.ai.openai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

public abstract class OpenAiChatModel implements ChatModel {
    public static Builder builder() { return null; }

    public abstract static class Builder {
        public abstract Builder defaultOptions(ChatOptions.Builder<?> options);
        public abstract OpenAiChatModel build();
    }
}
