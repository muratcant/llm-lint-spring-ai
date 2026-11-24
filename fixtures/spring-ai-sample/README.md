# spring-ai-sample (fixture)

A small Spring Boot 4.2 + Spring AI 2.1 app with **intentional** cost and reliability bugs,
used to try LLM Lint by hand (`./gradlew runIde` in the plugin project opens it).

| # | Bug | Where | Inspection |
|---|-----|-------|------------|
| 1 | `ChatClient` call without an output limit | `SupportService.answer`, `SupportService.answerStreaming`, `TicketAssistant.draftReply` | Missing output limit (done) |
| 2a | Unbounded chat memory, deprecated API (`maxMessages(Integer.MAX_VALUE)`) | `ChatConfig.chatMemory` | Unbounded chat memory (done) |
| 2b | Unbounded session memory, Spring AI 2.1 API (no `EventFilter.lastN` / compaction) | `SessionMemoryFactory.unboundedSession` | Unbounded chat memory (done) |
| 3 | `@Tool` that writes to the DB with no idempotency key | `OrderTools.placeOrder` | planned |
| 4 | High `spring.ai.retry.max-attempts` (25) and no `backoff.max-interval` | `application.yml` | planned |

Correct counterparts that must **not** be reported: `SummaryService` (both methods),
`TicketAssistant.title` and `SessionMemoryFactory.windowedSession`.

Compile check: `./gradlew compileKotlin compileJava` (no API key needed).
