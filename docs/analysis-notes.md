# Analysis notes

What the inspections rely on, how they decide, and what was deliberately left out. Written after the
correctness work of November 2026; keep it up to date when the analysis or the supported Spring AI versions change.

## Spring AI behaviour the analysis relies on

Checked in the sources, not only in the docs.

| Fact | Where | Checked against |
|---|---|---|
| `ChatClientRequestSpec.options(customizer)` **replaces** the request's options customizer (`this.optionsCustomizer = customizer`). The customizer starts as a copy of the client's `defaultOptions`, so those are dropped | `DefaultChatClient.DefaultChatClientRequestSpec.options` | 2.0.0, 2.0.3, 2.1.0 |
| `prompt(Prompt)` combines the prompt's options into that customizer; a later `.options(...)` drops them too | `DefaultChatClient.prompt(Prompt)` | 2.1.0 |
| The model's own options are merged underneath with `combineWith`, so they survive request options | `DefaultChatClientUtils` (`getChatModel().getOptions().mutate().combineWith(...)`) | 2.0.0, 2.0.3, 2.1.0 |
| `ChatOptions.Builder.maxTokens(@Nullable Integer)`; provider builders (`OpenAiChatOptions.Builder`) are subtypes of `ChatOptions.Builder` | `ChatOptions`, `OpenAiChatOptions` | 2.1.0 |
| Retry defaults: 10 retries (`max-attempts` goes to `maxRetries`), initial interval 2 s, multiplier 5, max interval 3 min | `SpringAiRetryProperties`, `SpringAiRetryAutoConfiguration`, `RetryUtils` | 2.1.0 |
| Tool calling is capped by default: 40 calls per tool, 150 in total per turn | `DefaultToolCallingManager.DEFAULT_MAX_CALLS_PER_TOOL` / `DEFAULT_MAX_TOTAL_TOOL_CALLS` | 2.1.0 |
| `SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY = "chat_memory_event_filter"`; `EventFilter.lastN(int)` and `EventFilter.Builder.lastN(Integer)` | `SessionMemoryAdvisor`, `EventFilter` | 2.1.0 |
| Bean choice: `@Qualifier` filters, then a single `@Primary` wins (also over a name match), then the parameter/field name | `DefaultListableBeanFactory.determineAutowireCandidate`, `hasPrimaryConflict` | Spring Framework 7.1.0 |

Provider default output limits are in `src/main/resources/provider-defaults.json`, each with its source.

## How the output-limit analysis decides

The analysis gives each call one of four results (`Coverage`):

| Result | Meaning | Reported? |
|---|---|---|
| `SAFE` | every path the analysis can see sets a limit | no |
| `UNSAFE` | no path sets a limit | yes |
| `MIXED` | some paths set one, at least one definitely doesn't | yes, as *not limited on every path* |
| `UNKNOWN` | a value comes from code the analysis can't see | no |

- **Alternatives** (branches of `if`/`?:`/`when`/elvis, several `return`s, several assignments, bean candidates that
  can't be narrowed) are combined with `join`: SAFE + UNSAFE is MIXED, UNKNOWN + UNSAFE is MIXED, SAFE + UNKNOWN is
  UNKNOWN.
- **Layers** that apply together (request options over client defaults over model options over configuration) are
  combined with `layer`: one SAFE layer is enough.
- Each element (variable, helper method, bean) is analysed once per call and the result reused. A reference cycle
  gives no information, and a call whose only information is a cycle is UNKNOWN.
- Configuration is a layer under the code. A setting counts only for the providers the call may run on (the model
  class the code shows, otherwise the providers on the module's classpath). A setting of a provider missing from
  `provider-defaults.json` may apply, so it makes the answer UNKNOWN instead of being ignored.
- Profiles: `spring.profiles.active` written in a file is **not trusted**, because it is usually overridden at
  runtime. A value set only in a profile file or an `on-profile` document is an alternative to the base value; when
  they differ the answer is UNKNOWN.

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Trust `spring.profiles.active` from files | No | Overridden by environment or command line in most deployments; trusting it gives false alarms |
| `maxTokens(0)` / `maxTokens(null)` in code | Not checked | The provider rejects 0, so there is no cost risk, and "no output limit" would be the wrong message. Config values are checked (`0`, `-1` are not limits) |
| Quick fix that adds `.options(ChatOptions.builder().maxTokens(n))` | Not offered | It would drop the client's `defaultOptions` (model, temperature) and break working code. Adding `maxTokens` to an existing request options builder is safe and may come later |
| Strict mode (show UNKNOWN as weak warnings) | Later | Needs reasons for each UNKNOWN first; doubles the test matrix |

## Inspections considered and not planned

| Idea | Why not |
|---|---|
| Side-effecting `@Tool` without an idempotency key | Whether a tool has side effects is semantic; static analysis would guess and produce false alarms |
| Tool-calling loop without a step limit | Spring AI 2.1 caps tool calls by default (40 per tool, 150 per turn). Flagging an explicit `unlimitedCallsPerTool()` would flag a deliberate choice |
| Retry settings (`spring.ai.retry.*`) | The defaults are bounded (10 retries, 3-minute ceiling). Failed requests are mostly not billed, and any threshold would be an opinion |
| Deprecated or retired model ids | A data file that goes stale quickly, for little benefit |

## Known limitations

See the README, section *Known limitations*.

## Verification status

- Unit and light fixture tests: `./gradlew test` (Java and Kotlin, Kotlin in K2 mode).
- Plugin Verifier: see the README roadmap for the last run.
- Not yet measured: highlighting time and false alarms on real, large Spring AI projects.
