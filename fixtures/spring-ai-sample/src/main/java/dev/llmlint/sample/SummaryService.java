package dev.llmlint.sample;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

/** Correct usage: LLM Lint must stay silent in this file. */
@Service
public class SummaryService {

    private final ChatClient chatClient;
    private final ChatClient classifier;

    public SummaryService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
        // OK: the client-level default carries a limit.
        this.classifier = builder.clone()
                .defaultOptions(ChatOptions.builder().maxTokens(16))
                .build();
    }

    /** OK: limit set on the request. */
    public String summarize(String text) {
        return chatClient.prompt()
                .user("Summarize in three sentences:\n" + text)
                .options(OpenAiChatOptions.builder().maxCompletionTokens(300))
                .call()
                .content();
    }

    /** OK: limit comes from defaultOptions(...) above. */
    public String sentiment(String text) {
        return classifier.prompt()
                .user("Answer POSITIVE or NEGATIVE: " + text)
                .call()
                .content();
    }
}
