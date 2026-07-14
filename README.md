# cloud-itonami-isco-2113

Open Occupation Blueprint for **ISCO-08 2113**: Chemists.

This repository designs a forkable OSS research support operation for chemistry: an autonomous advisor proposes lab operations (sample analysis, report drafting, hazardous result flagging, equipment time requests) under a governor-gated actor, ensuring lab safety, sample verification, and human-in-the-loop escalation for hazardous findings and novel claims.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here an autonomous lab advisor proposes analysis pipelines, report preparation, and equipment scheduling under an actor that gates all proposals and an independent **Chemistry Governor** that enforces lab safety and research integrity. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
flagging hazardous results, or proposing novel research claims) require human sign-off.

## Core Contract

```text
project + samples + equipment + research timeline
        |
        v
Lab Advisor -> Chemistry Governor -> analyze/draft/flag, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or finalize a result without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `2113`). Required capabilities:

- :robotics
- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section, alongside `cloud-itonami-isco-2411`, and other occupation actors): a real
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
human-in-the-loop interrupt/resume via checkpointing.

```text
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

- `src/chemistry/store.cljc` — `Store` protocol + `MemStore`:
  registered projects, samples, equipment, committed records, an append-only audit ledger.
- `src/chemistry/advisor.cljc` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a lab operation from a
  request; `llm-advisor` wraps a `langchain.model/ChatModel` — either
  way the advisor only ever produces a `:propose`-effect proposal,
  never a committed record, and LLM parse failures always yield
  `confidence 0.0` (forces escalation, never fabricated confidence).
- `src/chemistry/governor.cljc` — `ChemistryGovernor/check`: a pure
  function, wired as its own `:govern` node. Hard invariants
  (unregistered project, missing sample for analysis, a proposal whose
  `:effect` isn't `:propose`, finalized claims in draft proposals)
  always route to `:hold`. Escalation invariants (`:flag-hazardous-result`,
  `:draft-report` with novel claims, or low advisor confidence) always route to
  `:request-approval` — an `interrupt-before` node that the graph
  checkpoints and only resumes on explicit human approval
  (`actor/approve!`), matching the README's robotics-premise statement
  that hazardous findings and novel research claims always require
  human sign-off.
- `src/chemistry/actor.cljc` — `build-graph`, `run-request!`,
  `approve!`: the `langgraph.graph/state-graph` wiring itself.

Proposal operations (advisor-only, all `:effect :propose`):
- `:analyze-sample` — run/propose an analysis pipeline over a recorded sample.
- `:draft-report` — prepare a lab report draft section (never finalized).
- `:flag-hazardous-result` — surface a hazardous/unexpected result (escalates).
- `:request-equipment-time` — propose equipment allocation/scheduling.

```bash
clojure -M:test
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
