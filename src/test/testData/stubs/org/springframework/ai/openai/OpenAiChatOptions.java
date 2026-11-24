package org.springframework.ai.openai;

import org.springframework.ai.chat.prompt.ChatOptions;

public abstract class OpenAiChatOptions implements ChatOptions {
    public static Builder builder() { return null; }

    public abstract static class Builder implements ChatOptions.Builder<Builder> {
        public abstract Builder maxCompletionTokens(Integer maxCompletionTokens);
    }
}
