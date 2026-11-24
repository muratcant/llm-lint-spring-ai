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
| Configuration | `spring.ai.<provider>.chat.options.max-tokens` (or `max-completion-tokens`, `max-output-tokens`, `num-predict`) in `application*.yml` / `application*.properties`, for the provider the call runs on, with a positive value |
| Provider default | Anthropic: Spring AI applies 4096 output tokens by default, so a call that can only use Anthropic is not reported (see [Provider defaults](#provider-defaults)) |

```java
// Reported
chatClient.prompt().user(question).call().content();

// Fine
chatClient.prompt().user(question)
        .options(ChatOptions.builder().maxTokens(512))
        .call().content();
```

How the analysis decides:

- **Request options replace client defaults.** In Spring AI 2.0 and 2.1, `.options(...)` on a request replaces the
  client's `defaultOptions(...)` (and the options of a `Prompt`). A limit set only in `defaultOptions` is lost as soon
  as a request calls `.options(...)` without its own `maxTokens`, and the call is reported with a message that says
  so. Only the last `.options(...)` of a request counts. The model's own options and the configuration still apply.
- **Every path must have a limit.** When the client or the options are chosen in a branch (`if`/`else`, `? :`,
  Kotlin `when` and `?:`, several `return`s or assignments) and one branch has no limit, the call is reported as
  *not limited on every path*.
- **Only Spring AI setters count.** `maxTokens(...)` and the others must belong to a Spring AI options builder; a
  method of your own with the same name does not count.
- **Several beans.** With several `ChatClient` (or `ChatModel`) beans, the inspection follows Spring's choice:
  `@Qualifier`, then `@Primary`, then the bean whose name matches the parameter or field name. If that does not
  settle on one bean and the beans disagree, nothing is reported.
- **Configuration.** A limit for another provider does not count (an Anthropic limit does not cover a call that can
  only run on OpenAI). `0` and Ollama's `num-predict: -1` mean no limit. A limit set only in a profile
  (`application-prod.yml`, a document with `spring.config.activate.on-profile`) or through `${X}` without a default
  may or may not apply at runtime, so the call is not reported. Configuration under test roots does not count for
  production code.

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

Only `lastN` of `EventFilter` / `EventFilter.Builder` counts as a window. A compacting `SessionTurnAdvisor` in the
same file silences the warning only when it uses the same `SessionService`, and a per-request filter
(`SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY`) only when it is windowed.

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
[README](fixtures/spring-ai-sample/README.md) lists every bug and which inspection covers it. Two of them
(a side-effecting `@Tool` without an idempotency key, and a high `spring.ai.retry.max-attempts`) have no inspection;
[docs/analysis-notes.md](docs/analysis-notes.md) explains why.

## Project layout

```
src/main/kotlin/dev/llmlint/springai/
  inspections/   the inspections, the analysis (OutputLimitAnalyzer, Coverage, SpringBeans)
                 and the shared UAST helpers (UastFlow, CallSearch)
  config/        reads application*.yml / .properties without the YAML plugin, provider defaults
src/main/resources/
  META-INF/plugin.xml
  inspectionDescriptions/   the text shown in the inspection settings
src/test/
  kotlin/        BasePlatformTestCase tests, Java and Kotlin
  testData/stubs Spring AI API stubs used by the tests
fixtures/spring-ai-sample/  the sample project
docs/analysis-notes.md      verified Spring AI behaviour, analysis rules, decisions and limitations
```

Tests mark the expected warnings in the code, for example `<warning descr="...">call</warning>()`.
A test fails on any missing or extra warning, and on code that does not compile.

## Known limitations

- The analysis follows values through variables, fields, helper methods and `@Bean` methods of the project, up to
  a fixed depth. It does not follow collections, reflection or beans defined in libraries; those count as unknown,
  and unknown means no warning.
- Assignments to the same field are treated as alternatives, so `x = unlimited; x = limited;` in a row is reported
  as *not limited on every path*.
- Request options set on a request spec variable (`spec.options(...)` on its own line) are counted, but they do not
  drop the client's `defaultOptions`, as they would at runtime.
- A configured limit is also counted for a `ChatModel` built in code, although Spring binds the properties only to
  the auto-configured model.
- `@Priority`, `@Fallback` and default-candidate bean rules are not modelled.
- Speed and false alarms on real, large projects have not been measured yet.

See [docs/analysis-notes.md](docs/analysis-notes.md) for the details.

## Roadmap

Before the first Marketplace release:

- [x] License and change notes
- [ ] Plugin icon, vendor e-mail and web address in `plugin.xml`
- [x] Run the Plugin Verifier (`./gradlew verifyPlugin`) across 2025.1 – 2026.3: *Compatible* on all six IDEs
      (2025.1.7.2, 2025.2.6, 2025.3, 2026.1, 2026.2, 2026.3) on 2026-11-24, no deprecated or internal API usage
- [ ] CI on GitHub Actions: workflows are in `.github/workflows` (tests on every push and pull request,
      Plugin Verifier only when started by hand), not run on GitHub yet
- [ ] Try the inspections on real Spring AI projects and measure false alarms and speed
- [ ] Quick-fix: add `maxTokens(...)` to a request's existing `.options(...)` builder (adding a new `.options(...)`
      would drop the client's `defaultOptions`, so that is not offered)
- [ ] Signing and publishing setup

Considered and not planned for now (reasons in [docs/analysis-notes.md](docs/analysis-notes.md)):

- Side-effecting `@Tool` without an idempotency key
- Retry settings (`spring.ai.retry.*`)
- Tool-calling loop without a step limit
- Deprecated or retired model ids

## License

[Apache License 2.0](LICENSE).
