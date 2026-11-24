package dev.llmlint.springai.inspections

/** Regression tests: unrelated code must not silence an unbounded session memory warning (conservative mode). */
class MemoryRegressionJavaTest : SpringAiInspectionTestCase() {

    /** Bug: any use of EVENT_FILTER_CONTEXT_KEY in the project, even with an unbounded filter, silenced the warning. */
    fun testPerRequestUnboundedFilterDoesNotSuppress() {
        myFixture.addFileToProject(
            "ChatService.java",
            """
            import java.util.Map;
            import org.springframework.ai.session.EventFilter;
            import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

            class ChatService {
                Map<String, Object> context() {
                    return Map.of(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.all());
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
                    return SessionMemoryAdvisor.${warnSession()}(sessions).build();
                }
            }
            """,
        )
    }

    /** Bug: a compacting turn advisor for another SessionService in the same file silenced the warning. */
    fun testCompactionOnAnotherSessionServiceDoesNotSuppress() = checkFile(
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
            List<Advisor> advisors(SessionService archive, SessionService live) {
                return List.of(
                        SessionTurnAdvisor.builder(archive)
                                .compactionTrigger(new TurnCountTrigger(20))
                                .compactionStrategy(WindowCompactionStrategy.builder().build())
                                .build(),
                        SessionMemoryAdvisor.${warnSession()}(live).build());
            }
        }
        """,
    )

    /** Bug: any call named lastN inside the memory customizer counted, even an unrelated one. */
    fun testUnrelatedLastNInMemoryCustomizerDoesNotCount() = checkFile(
        "Memory.java",
        """
        import java.util.List;
        import org.springframework.ai.chat.client.advisor.api.Advisor;
        import org.springframework.ai.session.EventFilter;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionAdvisors;

        class Memory {
            static final class History {
                static int lastN(int n) { return n; }
            }

            List<Advisor> advisors(SessionService sessions) {
                return SessionAdvisors.${warnSession()}(sessions)
                        .memory(m -> {
                            History.lastN(5);
                            m.eventFilter(EventFilter.all());
                        })
                        .build();
            }
        }
        """,
    )

    fun testWindowedFilterInCustomizerBlockCounts() = checkFile(
        "Memory.java",
        """
        import java.util.List;
        import org.springframework.ai.chat.client.advisor.api.Advisor;
        import org.springframework.ai.session.EventFilter;
        import org.springframework.ai.session.SessionService;
        import org.springframework.ai.session.advisor.SessionAdvisors;

        class Memory {
            List<Advisor> advisors(SessionService sessions) {
                return SessionAdvisors.builder(sessions)
                        .memory(m -> {
                            m.order(5);
                            m.eventFilter(EventFilter.builder().lastN(30).excludeSynthetic(true).build());
                        })
                        .build();
            }
        }
        """,
    )
}
