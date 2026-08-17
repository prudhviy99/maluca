# AI Incident Triage: Evaluation and Regression Testing

The project has deterministic tests that run without an LLM and one explicit
model-in-the-loop task for local Ollama. These layers answer different
questions and must not be conflated.

## Test-task wiring

The root Gradle build excludes JUnit tag `llm` from ordinary `Test` tasks. The
dedicated `maluca-triage:llmTest` task is exempt from that exclusion and
includes only tag `llm`.

```bash
# All ordinary module tests; never needs Ollama for the tagged evaluation
./gradlew test

# Triage deterministic suite only
./gradlew :maluca-triage:test

# Explicit local-model regression; requires a reachable Ollama model
./gradlew :maluca-triage:llmTest
```

The migration test uses Testcontainers and is annotated
`disabledWithoutDocker=true`. Without a usable Docker daemon, that test is
skipped rather than proving the migration. Other deterministic triage tests use
mocks, temporary files, or an in-memory `SimpleVectorStore` and do not need
PostgreSQL, pgvector, Ollama, MCP, Redis, or the proxy.

## Deterministic triage suite

The current suite covers these behaviors:

| Test class | Cases | What it proves |
|---|---:|---|
| `IncidentBriefFactoryTest` | 3 | Untrusted-data delimiters/instructions; deterministic selected fields and top contributions; per-sample and whole-brief character ceilings under adversarial strings. |
| `IncidentTriageCompletionTest` | 4 | A stale lease cannot write a report; an owned lease stores a valid report and completes; a fallback returns to retry state without a proposal; a valid model patch is stored with exact report-generation and policy-baseline provenance. |
| `TriageRetryPolicyTest` | 2 | Deterministic exponential delays and terminal `TRIAGE_FAILED` behavior at the configured attempt limit. |
| `IncidentRepositoryLeaseTest` | 2 | Real PostgreSQL claim eligibility, attempt increments, backoff, expired-lease fencing, terminal poison handling, explicit manual retry, and heartbeat updates that do not churn a reviewed incident version. |
| `IncidentLifecycleServiceTest` | 3 | Exact-version audited dismissal of an open `TRIAGE_FAILED` row; stale version, wrong state, and unsafe reasons fail before mutation. |
| `TriageValidationGateTest` | 6 | Grounded exact citations and paired fact/value evidence are accepted; invented, cross-field-laundered evidence and forged citations are rejected; honest `UNKNOWN` can omit citation and patch. |
| `DecisionIngestServiceTest` | 3 | Client-key HMAC pseudonymization and path truncation; oversized batch and unsupported action rejection; bounded policy fields and finite contribution validation. |
| `AnomalyRuleEvaluatorTest` | 6 | Redis-rule priority, challenge/block rule, mitigation-share floors, zero-baseline absolute floor, distributed volume rule, and ordinary-window non-trigger. |
| `TriageMigrationTest` | 1 | V1–V5 apply; pgvector/pgcrypto and six tables exist; `vector(768)`, approval/lease/provenance columns are present; a real repository proposal is selectable only for its exact current valid report generation and is quarantined after generation change. |
| `PolicyFileServiceTest` | 10 | Atomic apply/reload/verification, stale baseline rejection, exact target-digest computation without mutation, collision-safe backups, effective list mutation, final CIDR overlap rejection, ordinary rollback, compensation, and indeterminate rollback failure. |
| `PolicyRemediationServiceTest` | 7 | Proposal-time live document validation; exact proposal/digest/baseline/version approval; wrong-incident rejection; post-apply compensation; target/baseline/third-digest indeterminate reconciliation. |
| `PolicyApplyLockTest` | 2 | The cluster apply guard pins acquire/operation/release to one JDBC connection and refuses a competing apply before its operation runs. |
| `PrometheusRedisSignalClientTest` | 2 | The bounded global Redis counter-increase query is decoded, while an unavailable/malformed Prometheus result remains unknown rather than normal. |
| `MarkdownReportRendererTest` | 1 | Model-controlled HTML, link/image syntax, and headings are escaped in the Markdown projection. |
| `PolicyPatchValidatorTest` | 6 | Route scope/empty-patch checks, strict bands, algorithm-specific fields, and literal IPv4/IPv6 versus invalid/hostname network entries. |
| `RetrievalRegressionTest` | 7 parameterized scenarios | Each incident label/query retrieves its expected runbook source in the top three of the offline marker index. |
| `RunbookChunkerTest` | 4 | Every trusted runbook produces the exact five ordered headings; IDs and hashes are stable; empty, duplicate-ID, and oversized content fails before embedding. |
| `RunbookIngestionServiceTest` | 7 | Empty/permanent startup failure, last-good transient readiness, stale-model readiness rejection, model-identity re-embedding, transactional upsert-before-delete, and rollback retention. |
| `RunbookReadinessTest` | 2 | Retrieval starts fail-closed, becomes healthy only for a trusted corpus, and returns out of service after an unrecovered refresh failure. |
| `RunbookSearchServiceTest` | 2 | Current trusted model metadata maps to cited results; stale-model retrieval rows fail closed. |
| `AgentToolProviderTest` / `TriageAgentDeadlineTest` | 2 | Exact callback allowlisting, scoped call budget, out-of-scope rejection, and total orchestration cancellation. |
| `AiConfigurationTest` / `SchedulingConfigurationTest` / `UpstreamConfigurationTest` | 5 | Production thinking is disabled, scheduled jobs can progress concurrently, and proxy admin calls use bounded no-redirect HTTP behavior. |
| `TriageHttpAuthorizationTest` | 3 | Internal credentials cannot dismiss/reconcile, unauthenticated mutations fail closed, and the operator bearer reaches both terminal lifecycle APIs. |

Counts should be treated as an inventory, not a quality score. When tests are
added, this table should be updated with the behavior rather than only the new
number.

## What offline retrieval evaluation actually measures

`RetrievalRegressionTest` builds a `SimpleVectorStore` at test time from the
seven packaged runbooks. Its custom `ScenarioEmbeddingModel` has seven vector
dimensions, one per normalized class marker:

```text
burst_flood
distributed_flood
path_scan
credential_stuffing
low_and_slow
redis_degradation
false_positive_wave
```

The test query includes one marker and passes when any of the top three results
has the expected source. This is deterministic and useful for catching missing
resources, broken chunking/metadata, changed scenario-marker routing, and gross
corpus routing mistakes. The marker strings are test-local and are not derived
from the `Classification` enum.

It does **not** evaluate:

- `nomic-embed-text` vectors;
- pgvector cosine/HNSW behavior;
- the production similarity floor of 0.45;
- committed precomputed production embeddings;
- semantic ambiguity without the class label; or
- retrieval from the production focused aggregate-signal query.

Accordingly, a passing offline retrieval test is a schema/corpus routing gate,
not evidence of real embedding recall.

## Validation evaluation

`TriageValidationGateTest` directly exercises the Java safety gate with one
known retrieved chunk and a test incident. Accepted cases prove exact
chunk ID/source/heading matching, exact field/value grounding, one-digit scalar
grounding, and bounded nested-map grounding. Rejection cases cover unpaired
substrings, wrong parent maps, an absent value, and a chunk absent from the
retrieved set. The `UNKNOWN` case proves the designed fallback can be valid
without citations. `TriageAgentSafetyTest` proves ungrounded evidence and an
invalid optional patch can be removed only before the complete gate is re-run,
leaving an otherwise grounded diagnosis intact.

The test does not presently exercise every validator branch. Notable untested
gate cases include summary length, evidence count/length, duplicate citation,
citation metadata mismatch with an existing ID, null entries, and every policy
patch combination. Patch-specific unit tests cover some of the latter at the
validator layer.

## Detector evaluation

`AnomalyRuleEvaluatorTest` is a pure rule test over synthetic
`WindowAggregate` records. It establishes the evaluator's fixed rule priority
and numeric floor semantics without a database or clock.

Beyond the dedicated PostgreSQL lease test, the suite does not currently exercise:

- SQL current/baseline aggregation;
- transaction-scoped advisory locking across replicas;
- the partial unique active-incident index under concurrent polling;
- `openOrTouch` concurrency, resolution, close/reopen, or every lifecycle
  version change (heartbeat no-version-churn is covered);
- computed-versus-executed action integration; or
- scheduled timing.

`PrometheusRedisSignalClientTest` covers the bounded global Redis query adapter;
`AnomalyRuleEvaluatorTest` covers the same threshold when supplied on a
synthetic or decision-derived window. The suite does not yet integration-test
the detector's construction/resolution of the synthetic `__maluca_redis__`
incident or the decision-reason fallback while Prometheus is unavailable. The
migration test checks supporting index/table creation only at a broad schema
level.

## Proxy export evaluation

The triage path also depends on focused tests in `maluca-proxy`:

| Test class | Implemented coverage |
|---|---|
| `DecisionEventFactoryTest` | Stable event ID/time, identity/policy/tier/method/path/action/reason/contribution/trace mapping; dry-run computed versus executed action; unmatched pass-cookie allow shape. |
| `DecisionSinkTest` | Disabled no-op, authenticated threshold delivery, deterministic drop-oldest overflow, 5xx/408/429 retry, permanent-4xx drop accounting and later-batch progress, and graceful partial-batch shutdown flush. |
| `MitigationWebFilterDecisionExportTest` | Three early-branch regressions: pass-cookie bypass exports explicit `ALLOW`; OBSERVE denylist and DRY_RUN fail-closed Redis retain computed `BLOCK` but execute/export `ALLOW`. |
| `PolicyAdminControllerTest` | Structured active policy fields required by apply verification and the admin-token guard. |
| `PolicyRegistryTest` | Loading/resolution/reload coverage includes policy identity wire bounds, duplicate-name rejection with last-good preservation, non-increasing effective bands, and invalid algorithm-specific rate values. |

The CI load-test additionally compares warmed, fixed-rate proxy p99 with the
sink disabled versus enabled against an unreachable loopback receiver, asserts
zero client transport errors, zero HTTP 5xx responses, and a bounded p99 delta,
and proves a real sink delivery failure through its Prometheus counter. This
remains a smoke guard,
not a controlled capacity benchmark. The suite does not prove sustained
overflow under concurrent producers or exercise
the real triage endpoint and PostgreSQL end to end.

## MCP evaluation

`maluca-mcp` has its own ordinary tests for bounded JSON clients, triage-client
URI/query construction and dedicated apply credentials, provider separation,
fixed-token HTTP security, human-tool method authorization, indeterminate
mutation failures, tool callback schemas, patch validation, PromQL policy, and
general tool-input validation. They run under `./gradlew :maluca-mcp:test`;
upstream services remain mocked.

`ApplyMcpAuthorizationIntegrationTest` starts the Spring HTTP context and uses
real synchronous MCP clients against both Streamable HTTP endpoints. It proves
that `/mcp` never advertises or invokes apply for the agent bearer and that the
separately authenticated `/operator/mcp` server advertises only
`approve_and_apply` and successfully dispatches all exact approval-binding
fields. Other tool tests use direct callbacks or MockMvc. A complete release
check should still exercise real triage/proxy/Prometheus upstreams.

## Model-in-the-loop evaluation

`OllamaRegressionTest` is tagged `llm` and constructs an Ollama chat model
directly rather than starting Spring Boot. Defaults come from
`src/test/resources/evals/baseline.json`:

```json
{
  "promptVersion": "v4",
  "defaultModel": "qwen3:14b",
  "embeddingModel": "nomic-embed-text",
  "fixtureCount": 7,
  "repetitions": 2,
  "minimumPassRate": 0.70
}
```

The runner uses temperature 0, seed 42, an 8,192-token context, and disables
thinking. It supports:

| Environment variable | Fallback | Use |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama origin. |
| `OLLAMA_CHAT_MODEL` | baseline `defaultModel` | Model under evaluation. |
| `MALUCA_EVAL_REPETITIONS` | baseline `repetitions` (`2`) | Runs per fixture. |
| `OLLAMA_CONTEXT_SIZE` | `8192` | Context size, bounded to 1–131,072. |
| `OLLAMA_SEED` | `42` | Deterministic seed. |
| `OLLAMA_INFERENCE_TIMEOUT` | `90s` | Per-call connect/read bound, capped at 15 minutes. |
| `MALUCA_EVAL_TIMEOUT` | `30m` | Whole preemptive suite bound, capped at four hours and required to exceed inference timeout. |

Example:

```bash
ollama pull gemma4:e4b
ollama list

OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_CHAT_MODEL=gemma4:e4b \
MALUCA_EVAL_REPETITIONS=2 \
./gradlew :maluca-triage:llmTest
```

Latest scored local result (2026-08-13): `gemma4:e4b` with prompt version `v4`
passed 12/14 evaluations (0.857 pass rate; required minimum 0.700) in
approximately 56 seconds. All classifications and required citations matched;
the two misses were credential-stuffing cases whose invalid optional limiter
patches were safely removed, so they did not satisfy the fixture's required
remediation score. The Gradle task deliberately disables up-to-date reuse
because the Ollama model, environment, and runtime are external mutable inputs.

The test has seven fixtures named after the traffic/chaos scenarios:

- `burst.py` → `BURST_FLOOD`
- `distributed.py` → `DISTRIBUTED_FLOOD`
- `scan.py` → `PATH_SCAN`
- `credstuff.py` → `CREDENTIAL_STUFFING`
- `lowslow.py` → `LOW_AND_SLOW`
- `kill_redis.sh` → `REDIS_DEGRADATION`
- `normal-forced-window` → `FALSE_POSITIVE_WAVE`

For each fixture, retrieval is mocked to return exactly one expected chunk at
similarity 0.95 and the agent tool list is mocked empty. The same Java output
converter, system prompt, repair loop, and validation gate used by production
then process the model response.

A run passes only when all four are true:

1. the result passes the Java gate;
2. classification exactly equals the fixture label; and
3. citations include the expected chunk ID; and
4. the patch is forbidden/optional/required as specified, stays on the exact
   policy/route, and—when required—sets at least one expected remediation field.

Pass rate is successful runs divided by `fixture count × repetitions`. With the
committed seven fixtures and two repetitions there are 14 runs, so the 0.70
threshold requires at least 10 passes (10/14 ≈ 0.714).

Failures report scenario, repetition, classification, valid flag, and final
validation errors. The test does not retry a failed **fixture run** beyond the
agent's normal internal repair attempt.

## Fixture provenance and limits

`evals/incidents.json` contains hand-authored compact briefs and short expected
runbook excerpts. The scenario names correspond to repository traffic scripts,
but these JSON entries are not captured raw decision batches or detector
snapshots produced by executing those scripts. Some fields, such as
`authenticationFailures`, `knownGoodClients`, or a 30-minute current window,
are illustrative evidence not emitted by the current 60-second decision-only
detector.

The live-model test therefore measures structured classification/citation
behavior on curated prompts. It does not prove end-to-end traffic detection or
that every fixture can be generated from the running stack.

It also does not test:

- Ollama embeddings or pgvector retrieval;
- MCP discovery or tool calls;
- policy proposal persistence (patch shape/scope expectations are scored);
- semantic optimality of a syntactically allowed remediation;
- summary factuality beyond paired evidence and length gates;
- performance, token usage, GPU residency, or latency; or
- adversarial prompt injection beyond the separate static brief test.

The test asserts prompt version `v4`, nonblank model identities, the exact
fixture-file length, positive repetitions, threshold shape, and that committed
measurement fields are `null`. The baseline is therefore an acceptance
threshold, not a fabricated observed result. It does not automatically prove
that those model identities match a separately edited application YAML file.

## Regression workflow

For a runbook, prompt, model, schema, validator, or fixture change:

1. Run focused deterministic tests:

   ```bash
   ./gradlew :maluca-triage:test :maluca-proxy:test :maluca-mcp:test
   ```

2. Confirm the migration test actually ran rather than skipped. Use the JUnit
   XML/HTML report or Gradle `--info` when Docker availability is uncertain.
3. If runbooks changed, inspect all 35 chunk IDs/headings and the seven offline
   retrieval scenarios.
4. Provision the exact local chat model and run `llmTest` with at least the
   committed repetition count.
5. Review individual fixture failures, raw model output during local debugging,
   and whether the validation gate rejected a genuinely unsafe output.
6. Run the actual Compose stack and ingest the corpus with
   `nomic-embed-text`; manually query unlabeled incident briefs to assess real
   retrieval behavior.
7. Exercise at least one safe proposal and a deliberately stale apply in an
   isolated policy copy. Bind the exact proposal ID/digest, baseline policy
   digest, and incident version; keep production apply flags false.
8. Update fixture/baseline metadata only after reviewing why behavior changed.
   Do not lower `minimumPassRate` simply to make a changed prompt green.
9. Record model name/tag or digest, Ollama version, processor, repetitions,
   pass rate, and date with release evidence.

## Suggested end-to-end verification

The following is a manual/system test; it is not automated by the current
JUnit suite:

1. Start the triage Compose profile with apply flags false.
2. Verify 35 runbook chunks and perform a real semantic search.
3. Run `normal.py` and confirm it does not meet detector floors.
4. Run one attack generator and verify exported decision rows, a deterministic
   incident, a report, and citations whose IDs occur in its persisted retrieval
   context.
5. Verify `/mcp` lets an agent token read/propose but cannot discover apply;
   verify `/operator/mcp` rejects it.
6. Copy the policy file into an isolated writable location, enable both apply
   switches, and verify only the operator MCP endpoint/token can apply the
   exact reviewed proposal.
7. Submit a wrong proposal ID/digest, baseline digest, and incident version in
   turn; each must conflict without a file change.
8. Force reload verification failure and confirm the original policy bytes are
   restored and state/audit show `APPLY_FAILED`; then separately force rollback
   verification failure and confirm `APPLY_INDETERMINATE`.
9. Stop triage/PostgreSQL during proxy traffic and confirm request handling
   continues while sink failure/drop metrics account for evidence loss.

## Known coverage gaps

The ordinary suite does not yet provide integration tests for:

- full triage API serialization beyond the focused terminal-lifecycle HTTP
  authorization slice;
- decision repository idempotency against a real database;
- detector SQL, locking, lifecycle, debounce, or multi-replica behavior;
- retention scheduling;
- runbook ingestion idempotency/change/removal against Ollama plus pgvector;
- `IncidentTriageWorker` claim/reopen/report behavior;
- the full repair loop with recorded model responses;
- report retrieval-context persistence and historical reproducibility;
- proposal/read HTTP serialization, real audit rows, apply feature flag, and
  the complete exact-approval workflow against PostgreSQL;
- scheduled reconciliation of crash-stranded `APPROVED` proposals across
  baseline, target, and third-party file digests;
- partial-band resolution across triage defaults, policy-local values, and
  proxy defaults;
- numeric active-limiter verification and backup-pruning limits;
- Compose health/dependency behavior;
- model latency and sustained/capacity proxy load beyond the checked-in
  warmed sink-enabled failure-isolation benchmark; and
- a captured traffic-script-to-report regression corpus.

These gaps should be represented as gaps in release evidence, not inferred as
passing behavior from nearby unit tests.
