# spring-ai-sample (fixture)

A small Spring Boot 4.2 + Spring AI 2.1 app with **intentional** cost and reliability bugs,
used to try LLM Lint by hand (`./gradlew runIde` in the plugin project opens it).

| # | Bug | Where | Inspection |
|---|-----|-------|------------|
| 1 | `ChatClient` call without an output limit | `SupportService.answer`, `SupportService.answerStreaming`, `TicketAssistant.draftReply` | Missing output limit (done) |
| 2a | Unbounded chat memory, deprecated API (`maxMessages(Integer.MAX_VALUE)`) | `ChatConfig.chatMemory` | Unbounded chat memory (done) |
| 2b | Unbounded session memory, Spring AI 2.1 API (no `EventFilter.lastN` / compaction) | `SessionMemoryFactory.unboundedSession` | Unbounded chat memory (done) |
| 3 | `@Tool` that writes to the DB with no idempotency key | `OrderTools.placeOrder` | not planned: whether a tool has side effects can't be told reliably from code |
| 4 | High `spring.ai.retry.max-attempts` (25 retries; the default 3-minute `backoff.max-interval` still caps each wait) | `application.yml` | not planned: Spring AI's retry defaults are already bounded |

See [`docs/analysis-notes.md`](../../docs/analysis-notes.md) for why rows 3 and 4 have no inspection.

Correct counterparts that must **not** be reported: `SummaryService` (both methods),
`TicketAssistant.title` and `SessionMemoryFactory.windowedSession`.

Compile check: `./gradlew compileKotlin compileJava` (no API key needed).
