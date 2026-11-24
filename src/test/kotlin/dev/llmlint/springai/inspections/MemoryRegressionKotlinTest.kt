package dev.llmlint.springai.inspections

/** Kotlin counterparts of [MemoryRegressionJavaTest]: unrelated code must not silence the warning. */
class MemoryRegressionKotlinTest : SpringAiInspectionTestCase() {

    fun testUnrelatedLastNInMemoryCustomizerDoesNotCount() = checkFile(
        "Memory.kt",
        """
        import org.springframework.ai.chat.client.advisor.api.Advisor
        import org.springframework.ai.session.EventFilter
        import org.springframework.ai.session.SessionService
        import org.springframework.ai.session.advisor.SessionAdvisors

        object History {
            fun lastN(n: Int): Int = n
        }

        class Memory(private val sessions: SessionService) {
            fun advisors(): List<Advisor> =
                SessionAdvisors.${warnSession()}(sessions)
                    .memory {
                        History.lastN(5)
                        it.eventFilter(EventFilter.all())
                    }
                    .build()
        }
        """,
    )

    fun testWindowedFilterBuilderInApplyCounts() = checkFile(
        "Memory.kt",
        """
        import org.springframework.ai.session.EventFilter
        import org.springframework.ai.session.SessionService
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor

        class Memory(private val sessions: SessionService) {
            fun advisor(): SessionMemoryAdvisor =
                SessionMemoryAdvisor.builder(sessions)
                    .apply { eventFilter(EventFilter.builder().lastN(30).build()) }
                    .build()
        }
        """,
    )

    private fun turnAndMemory(turnService: String, memoryMarker: String) = checkFile(
        "Memory.kt",
        """
        import org.springframework.ai.chat.client.advisor.api.Advisor
        import org.springframework.ai.session.SessionService
        import org.springframework.ai.session.advisor.SessionMemoryAdvisor
        import org.springframework.ai.session.advisor.SessionTurnAdvisor
        import org.springframework.ai.session.compaction.TurnCountTrigger
        import org.springframework.ai.session.compaction.WindowCompactionStrategy

        class Memory(private val archive: SessionService, private val live: SessionService) {
            fun advisors(): List<Advisor> = listOf(
                SessionTurnAdvisor.builder($turnService)
                    .compactionTrigger(TurnCountTrigger(20))
                    .compactionStrategy(WindowCompactionStrategy.builder().build())
                    .build(),
                SessionMemoryAdvisor.$memoryMarker(live).build(),
            )
        }
        """,
    )

    fun testCompactionOnAnotherSessionServiceDoesNotSuppress() = turnAndMemory("archive", warnSession())

    fun testCompactionOnTheSameSessionServiceSuppresses() = turnAndMemory("live", "builder")

    private fun perRequestFilter(filter: String, memoryMarker: String) {
        myFixture.addFileToProject(
            "ChatService.kt",
            """
            import org.springframework.ai.session.EventFilter
            import org.springframework.ai.session.advisor.SessionMemoryAdvisor

            class ChatService {
                fun context(): Map<String, Any> = mapOf(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY to $filter)
            }
            """.trimIndent(),
        )
        checkFile(
            "Memory.kt",
            """
            import org.springframework.ai.session.SessionService
            import org.springframework.ai.session.advisor.SessionMemoryAdvisor

            class Memory(private val sessions: SessionService) {
                fun advisor(): SessionMemoryAdvisor = SessionMemoryAdvisor.$memoryMarker(sessions).build()
            }
            """,
        )
    }

    fun testPerRequestUnboundedFilterDoesNotSuppress() = perRequestFilter("EventFilter.all()", warnSession())

    fun testPerRequestWindowedFilterSuppresses() = perRequestFilter("EventFilter.lastN(10)", "builder")
}
