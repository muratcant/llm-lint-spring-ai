package org.springframework.ai.anthropic;

import org.springframework.ai.chat.prompt.ChatOptions;

public abstract class AnthropicChatOptions implements ChatOptions {
    public static final Integer DEFAULT_MAX_TOKENS = 4096;

    public static Builder builder() { return null; }

    public abstract static class Builder implements ChatOptions.Builder<Builder> {
    }
}
