package dev.llmlint.springai.inspections

/** Same inspection through UAST on Kotlin sources. */
class UnboundedChatMemoryKotlinTest : SpringAiInspectionTestCase() {

    fun testMaxMessagesIntMaxValue() = checkFile(
        "Memory.kt",
        """
        import org.springframework.ai.chat.memory.MessageWindowChatMemory

        class Memory {
            fun chatMemory(): MessageWindowChatMemory =
                MessageWindowChatMemory.builder().maxMessages(${warnWindow(Int.MAX_VALUE.toLong(), "Int.MAX_VALUE")}).build()

            fun small(): MessageWindowChatMemory =
                MessageWindowChatMemory.builder().maxMessages(20).build()
        }
        """,
    )

    fun testSessionAdvisorsWithoutWindow() = checkFile(
        "Memory.kt",
        """
        import org.springframework.ai.chat.client.advisor.api.Advisor
        import org.springframework.ai.session.SessionService
        import org.springframework.ai.session.advisor.SessionAdvisors

        class Memory(private val sessions: SessionService) {
            fun advisors(): List<Advisor> = SessionAdvisors.${warnSession()}(sessions).build()
        }
        """,
    )

    fun testSessionAdvisorsWithWindow() = checkFile(
        "Memory.kt",
        """
        import org.springframework.ai.chat.client.advisor.api.Advisor
        import org.springframework.ai.session.EventFilter
        import org.springframework.ai.session.SessionService
        import org.springframework.ai.session.advisor.SessionAdvisors
        import org.springframework.ai.session.compaction.TurnCountTrigger
        import org.springframework.ai.session.compaction.WindowCompactionStrategy

        class Memory(private val sessions: SessionService) {
            fun windowed(): List<Advisor> =
                SessionAdvisors.builder(sessions)
                    .memory { it.eventFilter(EventFilter.lastN(20)) }
                    .build()

            fun compactedInApply(): List<Advisor> =
                SessionAdvisors.builder(sessions)
                    .apply { compaction(TurnCountTrigger(20), WindowCompactionStrategy.builder().build()) }
                    .build()
        }
        """,
    )
}
