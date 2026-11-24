package dev.llmlint.springai.inspections

class UnboundedChatMemoryJavaTest : SpringAiInspectionTestCase() {

    // ------------------------------------------------ message window memory (deprecated API)

    fun testMaxMessagesIntegerMaxValue() = checkFile(
        "Memory.java",
        """
        import org.springframework.ai.chat.memory.ChatMemory;
        import org.springframework.ai.chat.memory.MessageWindowChatMemory;

        class Memory {
            ChatMemory chatMemory() {
                return MessageWindowChatMemory.builder().maxMessages(${warnWindow(Int.MAX_VALUE.toLong(), "Integer.MAX_VALUE")}).build();
            }
        }
        """,
    )

    fun testMaxMessagesLargeConstant() = checkFile(
        "Memory.java",
        """
        import org.springframework.ai.chat.memory.MessageWindowChatMemory;

        class Memory {
            static final int HISTORY = 500;

            MessageWindowChatMemory chatMemory() {
                return MessageWindowChatMemory.builder().maxMessages(${warnWindow(500, "HISTORY")}).build();
            }
        }
        """,
    )

    fun testDefaultAndSmallWindowsAreFine() = checkFile(
        "Memory.java",
        """
        import org.springframework.ai.chat.memory.MessageWindowChatMemory;

        class Memory {
            MessageWindowChatMemory byDefault() {
                return MessageWindowChatMemory.builder().build();
            }

            MessageWindowChatMemory small() {
                return MessageWindowChatMemory.builder().maxMessages(30).build();
            }

            MessageWindowChatMemory configured(int size) {
                return MessageWindowChatMemory.builder().maxMessages(size).build();
            }
        }
        """,
    )

    fun testThresholdOption() {
        myFixture.disableInspections(UnboundedChatMemoryInspection())
        myFixture.enableInspections(UnboundedChatMemoryInspection().apply { maxMessagesThreshold = 1000 })
        checkFile(
            "Memory.java",
            """
            import org.springframework.ai.chat.memory.MessageWindowChatMemory;

            class Memory {
                MessageWindowChatMemory fine() {
                    return MessageWindowChatMemory.builder().maxMessages(500).build();
                }

                MessageWindowChatMemory huge() {
                    return MessageWindowChatMemory.builder().maxMessages(${warnWindow(5000, "5000")}).build();
                }
            }
            """,
        )
    }

    // ------------------------------------------------ session memory (Spring AI 2.1)

    fun testSessionMemoryAdvisorWithoutFilter() = checkFile(
        "Memory.java",
        """
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

        class Memory {
            SessionMemoryAdvisor advisor(SessionService sessions) {
                return SessionMemoryAdvisor.${warnSession()}(sessions).order(10).build();
            }
        }
        """,
    )

    fun testSessionMemoryAdvisorWithUnwindowedFilter() = checkFile(
        "Memory.java",
        """
        import org.springframework.ai.session.EventFilter;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

        class Memory {
            SessionMemoryAdvisor advisor(SessionService sessions) {
                return SessionMemoryAdvisor.${warnSession()}(sessions)
                        .eventFilter(EventFilter.builder().excludeSynthetic(true).build())
                        .build();
            }
        }
        """,
    )

    fun testSessionAdvisorsWithoutWindowOrCompaction() = checkFile(
        "Memory.java",
        """
        import java.util.List;
        import org.springframework.ai.chat.client.advisor.api.Advisor;
        import org.springframework.ai.session.EventFilter;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionAdvisors;

        class Memory {
            List<Advisor> plain(SessionService sessions) {
                return SessionAdvisors.${warnSession()}(sessions).build();
            }

            List<Advisor> activeOnly(SessionService sessions) {
                return SessionAdvisors.${warnSession()}(sessions)
                        .memory(m -> m.eventFilter(EventFilter.active()))
                        .build();
            }
        }
        """,
    )

    fun testWindowedSessionMemoryIsFine() = checkFile(
        "Memory.java",
        """
        import java.util.List;
        import org.springframework.ai.chat.client.advisor.api.Advisor;
        import org.springframework.ai.session.EventFilter;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionAdvisors;
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
        import org.springframework.ai.session.compaction.TurnCountTrigger;
        import org.springframework.ai.session.compaction.WindowCompactionStrategy;

        class Memory {
            private static final EventFilter LAST_TWENTY = EventFilter.builder().lastN(20).build();

            SessionMemoryAdvisor lastN(SessionService sessions) {
                return SessionMemoryAdvisor.builder(sessions).eventFilter(EventFilter.lastN(20)).build();
            }

            SessionMemoryAdvisor constantFilter(SessionService sessions) {
                return SessionMemoryAdvisor.builder(sessions).eventFilter(LAST_TWENTY).build();
            }

            List<Advisor> compacted(SessionService sessions) {
                return SessionAdvisors.builder(sessions)
                        .compaction(new TurnCountTrigger(20), WindowCompactionStrategy.builder().lastTurns(10).build())
                        .build();
            }

            List<Advisor> windowedMemory(SessionService sessions) {
                return SessionAdvisors.builder(sessions)
                        .memory(m -> m.eventFilter(EventFilter.lastN(10)))
                        .build();
            }
        }
        """,
    )

    fun testFilterFromOutsideIsNotReported() = checkFile(
        "Memory.java",
        """
        import java.util.List;
        import java.util.function.Consumer;
        import org.springframework.ai.chat.client.advisor.api.Advisor;
        import org.springframework.ai.session.EventFilter;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionAdvisors;
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

        class Memory {
            SessionMemoryAdvisor advisor(SessionService sessions, EventFilter filter) {
                return SessionMemoryAdvisor.builder(sessions).eventFilter(filter).build();
            }

            List<Advisor> advisors(SessionService sessions, Consumer<SessionMemoryAdvisor.Builder> memory) {
                return SessionAdvisors.builder(sessions).memory(memory).build();
            }
        }
        """,
    )

    fun testBuilderInLocalVariable() = checkFile(
        "Memory.java",
        """
        import org.springframework.ai.session.EventFilter;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

        class Memory {
            SessionMemoryAdvisor windowed(SessionService sessions) {
                SessionMemoryAdvisor.Builder builder = SessionMemoryAdvisor.builder(sessions);
                builder.eventFilter(EventFilter.lastN(5));
                return builder.build();
            }

            SessionMemoryAdvisor unbounded(SessionService sessions) {
                SessionMemoryAdvisor.Builder builder = SessionMemoryAdvisor.${warnSession()}(sessions);
                builder.order(1);
                return builder.build();
            }
        }
        """,
    )

    fun testPerRequestFilterInProjectSuppresses() {
        myFixture.addFileToProject(
            "ChatService.java",
            """
            import java.util.Map;
            import org.springframework.ai.session.EventFilter;
            import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

            class ChatService {
                Map<String, Object> context() {
                    return Map.of(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(10));
                }
            }
            """.trimIndent(),
        )
        checkFile(
            "Memory.java",
            """
            import org.springframework.ai.session.SessionService;
            import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

            class Memory {
                SessionMemoryAdvisor advisor(SessionService sessions) {
                    return SessionMemoryAdvisor.builder(sessions).build();
                }
            }
            """,
        )
    }

    fun testTurnAdvisorWithCompactionInSameFileSuppresses() = checkFile(
        "Memory.java",
        """
        import java.util.List;
        import org.springframework.ai.chat.client.advisor.api.Advisor;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
        import org.springframework.ai.session.advisor.SessionTurnAdvisor;
        import org.springframework.ai.session.compaction.TurnCountTrigger;
        import org.springframework.ai.session.compaction.WindowCompactionStrategy;

        class Memory {
            List<Advisor> advisors(SessionService sessions) {
                return List.of(
                        SessionTurnAdvisor.builder(sessions)
                                .compactionTrigger(new TurnCountTrigger(20))
                                .compactionStrategy(WindowCompactionStrategy.builder().build())
                                .build(),
                        SessionMemoryAdvisor.builder(sessions).build());
            }
        }
        """,
    )
}
