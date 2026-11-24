package dev.llmlint.springai.inspections

class MissingOutputLimitJavaTest : SpringAiInspectionTestCase() {

    // ---------------------------------------------------------------- positives

    fun testClientFromInjectedBuilderWithoutLimit() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                this.chatClient = builder.build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question).${warn()}().content();
            }
        }
        """,
    )

    fun testOptionsWithoutMaxTokens() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                return builder.build().prompt()
                        .user(question)
                        .options(ChatOptions.builder().temperature(0.2))
                        .${warn()}()
                        .content();
            }
        }
        """,
    )

    fun testStream() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                chatClient = builder.defaultSystem("Be brief").build();
            }

            Object ask(String question) {
                return chatClient.prompt(question).${warn("stream")}().content();
            }
        }
        """,
    )

    fun testInjectedClientWhoseBeanHasNoLimit() {
        myFixture.addFileToProject(
            "AiConfig.java",
            """
            import org.springframework.ai.chat.client.ChatClient;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            class AiConfig {
                @Bean
                ChatClient supportClient(ChatClient.Builder builder) {
                    return builder.defaultSystem("You are a support agent").build();
                }
            }
            """.trimIndent(),
        )
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.chat.client.ChatClient;

            class Svc {
                private final ChatClient chatClient;

                Svc(ChatClient supportClient) {
                    this.chatClient = supportClient;
                }

                String ask(String question) {
                    return chatClient.prompt().user(question).${warn()}().content();
                }
            }
            """,
        )
    }

    fun testCreateFromAutoConfiguredModel() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.model.ChatModel;

        class Svc {
            String ask(ChatModel chatModel, String question) {
                return ChatClient.create(chatModel).prompt().user(question).${warn()}().content();
            }
        }
        """,
    )

    fun testRequestSpecInVariable() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                ChatClient.ChatClientRequestSpec spec = builder.build().prompt().user(question);
                spec.system("Be brief");
                return spec.${warn()}().content();
            }
        }
        """,
    )

    // ---------------------------------------------------------------- negatives

    fun testOptionsWithMaxTokens() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient chatClient, String question) {
                return chatClient.prompt()
                        .user(question)
                        .options(ChatOptions.builder().maxTokens(256))
                        .call()
                        .content();
            }
        }
        """,
    )

    fun testProviderOptionsWithMaxCompletionTokens() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.openai.OpenAiChatOptions;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                this.chatClient = builder.build();
            }

            String ask(String question) {
                return chatClient.prompt()
                        .user(question)
                        .options(OpenAiChatOptions.builder().maxCompletionTokens(300))
                        .call()
                        .content();
            }
        }
        """,
    )

    fun testDefaultOptionsInConstructor() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                this.chatClient = builder.defaultOptions(ChatOptions.builder().maxTokens(500)).build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question).call().content();
            }
        }
        """,
    )

    fun testDefaultOptionsFromHelperMethod() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.model.ChatModel;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatModel model) {
                ChatClient.Builder builder = ChatClient.builder(model).defaultOptions(limits());
                chatClient = builder.build();
            }

            private static ChatOptions.Builder<?> limits() {
                return ChatOptions.builder().maxTokens(100);
            }

            String ask(String question) {
                return chatClient.prompt().user(question).call().content();
            }
        }
        """,
    )

    fun testInjectedClientWhoseBeanHasLimit() {
        myFixture.addFileToProject(
            "AiConfig.java",
            """
            import org.springframework.ai.chat.client.ChatClient;
            import org.springframework.ai.chat.prompt.ChatOptions;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            class AiConfig {
                @Bean
                ChatClient supportClient(ChatClient.Builder builder) {
                    return builder.defaultOptions(ChatOptions.builder().maxTokens(800)).build();
                }
            }
            """.trimIndent(),
        )
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.chat.client.ChatClient;

            class Svc {
                private final ChatClient chatClient;

                Svc(ChatClient supportClient) {
                    this.chatClient = supportClient;
                }

                String ask(String question) {
                    return chatClient.prompt().user(question).call().content();
                }
            }
            """,
        )
    }

    fun testInjectedClientWithoutVisibleBeanIsNotReported() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient chatClient) {
                this.chatClient = chatClient;
            }

            String ask(String question) {
                return chatClient.prompt().user(question).call().content();
            }
        }
        """,
    )

    fun testOptionsFromParameterAreNotReported() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, ChatOptions.Builder<?> options, String question) {
                return builder.build().prompt().user(question).options(options).call().content();
            }
        }
        """,
    )

    fun testModelBuiltWithLimit() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.openai.OpenAiChatModel;
        import org.springframework.ai.openai.OpenAiChatOptions;

        class Svc {
            private final ChatClient chatClient = ChatClient.create(
                    OpenAiChatModel.builder()
                            .defaultOptions(OpenAiChatOptions.builder().maxTokens(200))
                            .build());

            String ask(String question) {
                return chatClient.prompt().user(question).call().content();
            }
        }
        """,
    )

    fun testRequestSpecVariableWithOptionsOnSeparateLine() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                ChatClient.ChatClientRequestSpec spec = builder.build().prompt().user(question);
                spec.options(ChatOptions.builder().maxTokens(64));
                return spec.call().content();
            }
        }
        """,
    )

    fun testPromptCarryingOptions() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;
        import org.springframework.ai.chat.prompt.Prompt;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                Prompt prompt = new Prompt(question, ChatOptions.builder().maxTokens(10).build());
                return builder.build().prompt(prompt).call().content();
            }
        }
        """,
    )

    fun testUnrelatedCallMethod() = checkFile(
        "Svc.java",
        """
        import java.util.concurrent.Callable;

        class Svc {
            String run(Callable<String> task) throws Exception {
                return task.call();
            }
        }
        """,
    )
}
