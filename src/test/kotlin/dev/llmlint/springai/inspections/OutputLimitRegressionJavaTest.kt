package dev.llmlint.springai.inspections

/**
 * Regression tests for analysis bugs found in the 0.1.0 audit (conservative mode, OpenAI on the classpath).
 * Each test names the bug it pins down.
 */
class OutputLimitRegressionJavaTest : SpringAiInspectionTestCase() {

    // ------------------------------------------------ request options replace client defaults

    /** Bug: `.options(x)` replaces the client's defaultOptions customizer, so its maxTokens is lost. */
    fun testRequestOptionsReplaceClientDefaults() = checkFile(
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
                return chatClient.prompt().user(question)
                        .options(ChatOptions.builder().temperature(0.2))
                        .${warnReplaced()}()
                        .content();
            }
        }
        """,
    )

    /** Request options that set their own limit are fine, whatever the client defaults are. */
    fun testRequestOptionsWithLimitOverClientDefaults() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                this.chatClient = builder.defaultOptions(ChatOptions.builder().temperature(0.1)).build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question)
                        .options(ChatOptions.builder().maxTokens(256))
                        .call()
                        .content();
            }
        }
        """,
    )

    /** prompt(Prompt) options are combined into the customizer that a later .options(...) replaces. */
    fun testRequestOptionsReplacePromptOptions() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;
        import org.springframework.ai.chat.prompt.Prompt;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                Prompt prompt = new Prompt(question, ChatOptions.builder().maxTokens(10).build());
                return builder.build().prompt(prompt)
                        .options(ChatOptions.builder().temperature(0.2))
                        .${warn()}()
                        .content();
            }
        }
        """,
    )

    /** Model-level options are merged with combineWith and survive request options. */
    fun testModelOptionsSurviveRequestOptions() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;
        import org.springframework.ai.openai.OpenAiChatModel;
        import org.springframework.ai.openai.OpenAiChatOptions;

        class Svc {
            private final ChatClient chatClient = ChatClient.create(
                    OpenAiChatModel.builder().defaultOptions(OpenAiChatOptions.builder().maxTokens(200)).build());

            String ask(String question) {
                return chatClient.prompt().user(question)
                        .options(ChatOptions.builder().temperature(0.2))
                        .call()
                        .content();
            }
        }
        """,
    )

    /** The last `.options(...)` in the chain wins. */
    fun testLastOptionsCallWins() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                return builder.build().prompt().user(question)
                        .options(ChatOptions.builder().maxTokens(256))
                        .options(ChatOptions.builder().temperature(0.2))
                        .${warn()}()
                        .content();
            }
        }
        """,
    )

    // ------------------------------------------------ multiple beans

    private fun twoBeans(primaryOnUnlimited: Boolean = false) = myFixture.addFileToProject(
        "AiConfig.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        import org.springframework.context.annotation.Primary;

        @Configuration
        class AiConfig {
            @Bean
            ChatClient limitedClient(ChatClient.Builder builder) {
                return builder.defaultOptions(ChatOptions.builder().maxTokens(800)).build();
            }

            @Bean ${if (primaryOnUnlimited) "@Primary" else ""}
            ChatClient supportClient(ChatClient.Builder builder) {
                return builder.defaultSystem("You are a support agent").build();
            }
        }
        """.trimIndent(),
    )

    /** Bug: one limited bean made every injected ChatClient look safe. Spring picks the bean by parameter name. */
    fun testBeanSelectedByParameterName() {
        twoBeans()
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

    fun testBeanSelectedByQualifier() {
        twoBeans()
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.chat.client.ChatClient;
            import org.springframework.beans.factory.annotation.Qualifier;

            class Svc {
                private final ChatClient limited;
                private final ChatClient support;

                Svc(@Qualifier("limitedClient") ChatClient a, @Qualifier("supportClient") ChatClient b) {
                    this.limited = a;
                    this.support = b;
                }

                String brief(String question) {
                    return limited.prompt().user(question).call().content();
                }

                String full(String question) {
                    return support.prompt().user(question).${warn()}().content();
                }
            }
            """,
        )
    }

    fun testPrimaryBeanIsUsedWhenNameDoesNotMatch() {
        twoBeans(primaryOnUnlimited = true)
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.chat.client.ChatClient;

            class Svc {
                private final ChatClient chatClient;

                Svc(ChatClient client) {
                    this.chatClient = client;
                }

                String ask(String question) {
                    return chatClient.prompt().user(question).${warn()}().content();
                }
            }
            """,
        )
    }

    /**
     * Spring Framework 7.1 (DefaultListableBeanFactory): a @Primary bean wins even over a bean whose name matches
     * the parameter (`hasPrimaryConflict` disables the name shortcut, then `determineAutowireCandidate` checks
     * @Primary first).
     */
    fun testPrimaryBeanWinsOverNameMatch() {
        twoBeans(primaryOnUnlimited = true)
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.chat.client.ChatClient;

            class Svc {
                private final ChatClient chatClient;

                Svc(ChatClient limitedClient) {
                    this.chatClient = limitedClient;
                }

                String ask(String question) {
                    return chatClient.prompt().user(question).${warn()}().content();
                }
            }
            """,
        )
    }

    /** `@Bean("name")` and `@Bean({"name", "alias"})` replace the method name as bean names. */
    fun testBeanSelectedByDeclaredNameOrAlias() {
        myFixture.addFileToProject(
            "AiConfig.java",
            """
            import org.springframework.ai.chat.client.ChatClient;
            import org.springframework.ai.chat.prompt.ChatOptions;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            class AiConfig {
                @Bean(name = "brief")
                ChatClient limited(ChatClient.Builder builder) {
                    return builder.defaultOptions(ChatOptions.builder().maxTokens(800)).build();
                }

                @Bean({"full", "support"})
                ChatClient unlimited(ChatClient.Builder builder) {
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
                private final ChatClient brief;
                private final ChatClient support;

                Svc(ChatClient brief, ChatClient support) {
                    this.brief = brief;
                    this.support = support;
                }

                String summary(String question) {
                    return brief.prompt().user(question).call().content();
                }

                String answer(String question) {
                    return support.prompt().user(question).${warn()}().content();
                }
            }
            """,
        )
    }

    /** Several beans could be injected and they disagree: not proven either way, so not reported. */
    fun testAmbiguousBeansAreNotReported() {
        twoBeans()
        checkFile(
            "Svc.java",
            """
            import org.springframework.ai.chat.client.ChatClient;

            class Svc {
                private final ChatClient chatClient;

                Svc(ChatClient client) {
                    this.chatClient = client;
                }

                String ask(String question) {
                    return chatClient.prompt().user(question).call().content();
                }
            }
            """,
        )
    }

    // ------------------------------------------------ alternative paths

    /** Bug: a ternary with one limited branch was treated as safe. */
    fun testConditionalClientIsPartial() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder, boolean brief) {
                this.chatClient = brief
                        ? builder.defaultOptions(ChatOptions.builder().maxTokens(100)).build()
                        : builder.build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question).${warnPartial()}().content();
            }
        }
        """,
    )

    fun testAssignmentsInBranchesArePartial() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder, boolean brief) {
                if (brief) {
                    this.chatClient = builder.defaultOptions(ChatOptions.builder().maxTokens(100)).build();
                } else {
                    this.chatClient = builder.build();
                }
            }

            String ask(String question) {
                return chatClient.prompt().user(question).${warnPartial()}().content();
            }
        }
        """,
    )

    /** Bug: `spec.options(...)` inside an `if` counted as always applied. */
    fun testConditionalRequestOptionsArePartial() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question, boolean brief) {
                ChatClient.ChatClientRequestSpec spec = builder.build().prompt().user(question);
                if (brief) {
                    spec.options(ChatOptions.builder().maxTokens(64));
                }
                return spec.${warnPartial()}().content();
            }
        }
        """,
    )

    fun testHelperWithTwoReturnsIsPartial() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question, boolean brief) {
                return builder.build().prompt().user(question).options(options(brief)).${warnPartial()}().content();
            }

            private static ChatOptions.Builder<?> options(boolean brief) {
                if (brief) {
                    return ChatOptions.builder().maxTokens(64);
                }
                return ChatOptions.builder().temperature(0.5);
            }
        }
        """,
    )

    fun testBothBranchesLimitedIsSafe() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question, boolean brief) {
                return builder.build().prompt().user(question)
                        .options(brief ? ChatOptions.builder().maxTokens(64) : ChatOptions.builder().maxTokens(1024))
                        .call()
                        .content();
            }
        }
        """,
    )

    /** The same helper reached from both branches gives the same answer the second time (no fake unsafe path). */
    fun testSameHelperInBothBranchesIsSafe() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder, boolean brief) {
                this.chatClient = brief
                        ? limited(builder).defaultSystem("Be brief").build()
                        : limited(builder).build();
            }

            private static ChatClient.Builder limited(ChatClient.Builder builder) {
                return builder.defaultOptions(ChatOptions.builder().maxTokens(100));
            }

            String ask(String question) {
                return chatClient.prompt().user(question).call().content();
            }
        }
        """,
    )

    /** Options set on the spec before the call, outside any branch, apply. */
    fun testUnconditionalRequestOptionsOnSeparateLineCount() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question, boolean verbose) {
                ChatClient.ChatClientRequestSpec spec = builder.build().prompt().user(question);
                spec.options(ChatOptions.builder().maxTokens(64));
                if (verbose) {
                    return spec.call().content();
                }
                return spec.call().content();
            }
        }
        """,
    )

    /** Options set after the call don't apply to it. */
    fun testRequestOptionsAfterTheCallDoNotCount() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient.Builder builder, String question) {
                ChatClient.ChatClientRequestSpec spec = builder.build().prompt().user(question);
                String first = spec.${warn()}().content();
                spec.options(ChatOptions.builder().maxTokens(64));
                return first + spec.call().content();
            }
        }
        """,
    )

    /**
     * Known limitation: assignments are treated as alternatives, so a reassignment that replaces an
     * unlimited client by a limited one is reported as partial.
     */
    fun testSequentialReassignmentIsReportedAsPartial() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            private ChatClient chatClient;

            Svc(ChatClient.Builder builder) {
                chatClient = builder.build();
                chatClient = builder.defaultOptions(ChatOptions.builder().maxTokens(100)).build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question).${warnPartial()}().content();
            }
        }
        """,
    )

    // ------------------------------------------------ setter validation

    /** Bug: any method named maxTokens counted, e.g. a getter of the app's own settings. */
    fun testUnrelatedMaxTokensMethodDoesNotCount() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            static final class Limits {
                int maxTokens() { return 100; }
            }

            private final ChatClient chatClient;

            Svc(ChatClient.Builder builder, Limits limits) {
                this.chatClient = builder.defaultSystem("Answer in at most " + limits.maxTokens() + " tokens").build();
            }

            String ask(String question) {
                return chatClient.prompt().user(question).${warn()}().content();
            }
        }
        """,
    )

    /** A computed value is an explicit choice of the developer: counted as a limit. */
    fun testComputedLimitCounts() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;
        import org.springframework.ai.chat.prompt.ChatOptions;

        class Svc {
            String ask(ChatClient chatClient, String question, int budget) {
                return chatClient.prompt().user(question).options(ChatOptions.builder().maxTokens(budget)).call().content();
            }
        }
        """,
    )

    fun testRecursiveReferencesTerminate() = checkFile(
        "Svc.java",
        """
        import org.springframework.ai.chat.client.ChatClient;

        class Svc {
            private ChatClient a;
            private ChatClient b;

            void swap() {
                a = b;
                b = a;
            }

            String ask(String question) {
                return a.prompt().user(question).call().content();
            }
        }
        """,
    )
}
