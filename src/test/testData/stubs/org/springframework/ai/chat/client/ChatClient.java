package org.springframework.ai.chat.client;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

public interface ChatClient {
    static ChatClient create(ChatModel chatModel) { return null; }
    static Builder builder(ChatModel chatModel) { return null; }

    ChatClientRequestSpec prompt();
    ChatClientRequestSpec prompt(String content);
    ChatClientRequestSpec prompt(Prompt prompt);
    Builder mutate();

    interface ChatClientRequestSpec {
        <B extends ChatOptions.Builder<?>> ChatClientRequestSpec options(B customizer);
        ChatClientRequestSpec system(String text);
        ChatClientRequestSpec user(String text);
        CallResponseSpec call();
        StreamResponseSpec stream();
    }

    interface CallResponseSpec {
        String content();
    }

    interface StreamResponseSpec {
        Object content();
    }

    interface Builder {
        Builder defaultOptions(ChatOptions.Builder customizer);
        Builder defaultSystem(String text);
        Builder clone();
        ChatClient build();
    }
}
