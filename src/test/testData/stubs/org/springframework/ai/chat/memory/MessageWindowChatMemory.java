package org.springframework.ai.chat.memory;

public final class MessageWindowChatMemory implements ChatMemory {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        public Builder maxMessages(int maxMessages) { return this; }
        public MessageWindowChatMemory build() { return new MessageWindowChatMemory(); }
    }
}
