# LLM Lint for Spring AI

An IntelliJ IDEA plugin that finds **cost and reliability mistakes in Spring AI 2.x code** while you type,
before they show up on your LLM invoice or in production.

> **Status: early MVP, not published yet.** Two inspections work and are tested. The plugin has only been
> tried in IntelliJ IDEA 2025.1 and on the sample project in this repository. See [Roadmap](#roadmap).

## Why

Most LLM cost tools work at runtime: gateways, budgets, dashboards. They tell you what happened.
LLM Lint works in the IDE, on the code itself, and points at the line that will cost you money:

- a chat call with no output token limit, so the model decides how long (and how expensive) each answer is;
- chat memory that re-sends the whole conversation on every call, so input tokens grow with every turn.

## Inspections

Both inspections are built on UAST, so they work the same way for **Java and Kotlin** (including Kotlin K2 mode).
You can find them under *Settings → Editor → Inspections → LLM Lint (Spring AI)*.

All inspections are tuned to avoid false alarms. When a value comes from code they cannot see (a method
parameter, a bean from a library), they assume it is fine and stay silent.

### Missing output limit

Reports `ChatClient` calls (`.call()` / `.stream()`) that have no output token limit.

The inspection stays silent when a limit is set in any of these places:

| Where | Example |
|-------|---------|
| The request | `.options(ChatOptions.builder().maxTokens(512))`, also `maxCompletionTokens`, `maxOutputTokens`, `numPredict` |
| The client | `ChatClient.builder(model).defaultOptions(...)`, in a field, a constructor, a helper method or a `@Bean` method |
| The model | a `ChatModel` built in code with default options |
| Configuration | `spring.ai.<provider>.chat.options.max-tokens` (or `max-completion-tokens`, `max-output-tokens`, `num-predict`) in `application*.yml` / `application*.properties` |
| Provider default | Anthropic: Spring AI applies 4096 output tokens by default, so a call that can only use Anthropic is not reported (see [Provider defaults](#provider-defaults)) |

```java
// Reported
chatClient.prompt().user(question).call().content();

// Fine
chatClient.prompt().user(question)
        .options(ChatOptions.builder().maxTokens(512))
        .call().content();
```

#### Provider defaults

Some providers cap the answer even when nothing is configured. These defaults live in a data file,
[`provider-defaults.json`](src/main/resources/provider-defaults.json), not in the code. Each entry lists the
provider's `ChatModel` class, its default output limit (or `null`), the property name, the documentation URL and
the Spring AI version it was checked against. Only values confirmed in the official Spring AI documentation are
filled in; today that is Anthropic (4096 tokens). When no limit is set in code or config, the inspection looks at
which provider `ChatModel` classes are on the module's classpath, or at the model class the code uses, if it can
see one. If every candidate provider has a default, the call is not reported. If some have a default and some do
not, and the code does not show which model is used, the call is not reported either. If none has a default, the
call is reported as before. An option, *Also report calls that rely on the default output limit of the provider*
(off by default), shows these calls as weak warnings, for example
"This call relies on the Anthropic default output limit (4096 tokens). Is that what you need?".

### Unbounded chat memory

Reports chat memory that sends (almost) the whole conversation to the model on every call.

- **Session memory (Spring AI 2.1+).** `SessionMemoryAdvisor.builder(..)` or `SessionAdvisors.builder(..)` with
  neither `eventFilter(EventFilter.lastN(n))` nor `compaction(trigger, strategy)`. Session memory replays the
  whole active history by default, unlike the older window memory.
- **Message window memory (deprecated since 2.1).** `MessageWindowChatMemory.builder().maxMessages(n)` where `n` is
  a constant at or above a threshold. The threshold is 200 by default and can be changed in the inspection options.
  Without `maxMessages`, the window is 20 messages and nothing is reported.

```java
// Reported
List<Advisor> advisors = SessionAdvisors.builder(sessionService).build();

// Fine
List<Advisor> advisors = SessionAdvisors.builder(sessionService)
        .memory(memory -> memory.eventFilter(EventFilter.lastN(20)))
        .build();
```

## Compatibility

| | |
|---|---|
| IDE | IntelliJ IDEA 2025.1 – 2026.3: since-build `251`, until-build `263.*` (see `gradle.properties`). Built and tested against 2025.1.7 |
| Required plugins | Java (`com.intellij.java`), Kotlin (`org.jetbrains.kotlin`) |
| Spring AI | 2.x. Checked against the 2.1.0 API |

## Build and run

You need JDK 21. Gradle comes with the wrapper.

Run the tests:

```bash
./gradlew test
```

Start a sandbox IntelliJ IDEA with the plugin installed. It opens the sample project with the files that
show each inspection:

```bash
./gradlew runIde
```

Build the plugin ZIP (output in `build/distributions/`):

```bash
./gradlew buildPlugin
```

To install it by hand, use *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick the ZIP.

## Sample project

[`fixtures/spring-ai-sample`](fixtures/spring-ai-sample) is a small Spring Boot 4.2 + Spring AI 2.1 app with
**intentional** mistakes, plus correct versions that must not be reported. Its
[README](fixtures/spring-ai-sample/README.md) lists every bug and which inspection covers it. Two of the bugs
(a side-effecting `@Tool` without an idempotency key, and a high `spring.ai.retry.max-attempts`) are there for
inspections that are not written yet.

## Project layout

```
src/main/kotlin/dev/llmlint/springai/
  inspections/   the inspections and the shared UAST helpers (UastFlow, CallSearch)
  config/        reads application*.yml / .properties without the YAML plugin
src/main/resources/
  META-INF/plugin.xml
  inspectionDescriptions/   the text shown in the inspection settings
src/test/
  kotlin/        BasePlatformTestCase tests, Java and Kotlin
  testData/stubs Spring AI API stubs used by the tests
fixtures/spring-ai-sample/  the sample project
```

Tests mark the expected warnings in the code, for example `<warning descr="...">call</warning>()`.
A test fails on any missing or extra warning, and on code that does not compile.

## Roadmap

Before the first Marketplace release:

- [x] License and change notes
- [ ] Plugin icon, vendor e-mail and web address in `plugin.xml`
- [ ] Run the Plugin Verifier (`./gradlew verifyPlugin`) across 2025.1 – 2026.3
- [ ] CI on GitHub Actions: tests and Plugin Verifier
- [ ] Try the inspections on real Spring AI projects and measure false alarms and speed
- [ ] Quick-fixes: add `.options(...maxTokens(...))`, add `EventFilter.lastN(...)`
- [ ] Signing and publishing setup

Inspections planned next:

- [ ] Side-effecting `@Tool` without an idempotency key while retries are on
- [ ] Too many retries (`spring.ai.retry.max-attempts`) without a backoff ceiling
- [ ] Deprecated or retired model ids
- [ ] Tool-calling loop without a step limit

## License

[Apache License 2.0](LICENSE).
