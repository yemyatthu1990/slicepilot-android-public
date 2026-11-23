# SlicePilot knowledge graph

Machine-readable memory of **what the project's state is**, so an agent can read the current
state in one file and an agent that just finished work can record it in one place.

```text
knowledge/
  graph.json       canonical state: nodes (things that exist) + edges (how they relate)
  sessions.jsonl   append-only journal: one JSON object per agent session, one line each
  kb.py            stdlib CLI: check | render | show | query | node set | session add
  STATUS.md        generated digest (rendered from graph.json + sessions.jsonl)
  README.md        this schema and the update protocol
```

`STATUS.md` is **generated** — never hand-edit it. Edit `graph.json` (or use `kb.py node set`)
and run `python3 knowledge/kb.py render`.

---

## 1. Node kinds

Every fact about the project is a node. `id` is stable forever: `<kind>:<kebab-slug>`. Never
rename a node that anything references; add a new one and mark the old one `superseded`.

| kind | what it represents | status values |
| --- | --- | --- |
| `project` | the app itself (exactly one) | `active`, `archived` |
| `release` | a version that was built/uploaded/shipped (`rel:v1.9.6`) | `planned`, `built`, `uploaded`, `staged`, `live`, `superseded`, `halted`, `draft` |
| `module` | a buildable/authored source component (`mod:android-app`) | `active`, `vendored`, `frozen`, `legacy`, `stub` |
| `feature` | a user- or agent-visible capability (`feat:cloud-print`) | `idea`, `in_progress`, `code_complete`, `merged`, `shipped`, `deprecated` |
| `branch` | a git branch worth remembering (`br:feature/multi-filament`) | `active`, `pending_merge`, `merged`, `parked`, `stale`, `abandoned`, `deleted` |
| `external` | a service/API/endpoint the app talks to (`ext:elegoo-cloud`) | `live`, `configured`, `unverified`, `retired` |
| `device` | a phone, printer, or target used for verification (`dev:pixel-4a`) | `available`, `in_use`, `unreachable`, `retired`, `unknown` |
| `artifact` | a build output or asset set (`art:screenshots-phone-v1.9.0`) | `current`, `stale`, `archived` |
| `rule` | an enforced practice/gotcha learned the hard way (`rule:jni-r8-keep`) | `enforced`, `advisory`, `draft` |
| `doc` | a document that carries knowledge (`doc:agents`) | `current`, `reference`, `stale`, `draft` |
| `issue` | an open problem, risk, or known gap (`iss:no-instrumented-tests`) | `open`, `mitigated`, `resolved`, `wont_fix` |

### Node shape

```jsonc
{
  "id": "feat:multi-filament",
  "kind": "feature",
  "title": "Multi-filament / N-colour printing",
  "status": "code_complete",
  "summary": "One sentence of truth: what exists now, not what was planned.",
  "updated": "2026-11-22",              // ISO date of the last real change to this fact
  "owner": "agent | dev | both",        // optional
  "verification": {                     // how far this has actually been proven
    "level": "none | code_review | unit_tests | debug_apk | release_apk | device | store",
    "at": "2026-11-22",
    "ref": "commit:5163dfe | file:store_listing/metadata.md | url:https://…"
  },
  "next": ["Device-verify on the Samsung S25 edge", "Merge to main"],   // ordered open work
  "refs": ["android/app/src/main/java/…", "commit:05ff585", "url:https://…"],
  "attrs": { "any": "structured kind-specific detail, e.g. versionCode" }
}
```

Rules of thumb:

- `summary` states what is true **now** (verified), not the intent. If you only read the code,
  say so with `verification.level: code_review`.
- `verification` is the difference between "written" and "works". A `feature` cannot be
  `shipped` with `verification.level` below `release_apk`; `released to Play` wants `store`.
  (`kb.py check` enforces this.)
- `next` is the actionable list. It is what the next agent reads first. Empty it when done.
- `updated` changes only when the fact changes, not when you re-render `STATUS.md`.

## 2. Edge types

```jsonc
{ "from": "feat:multi-filament", "to": "br:feature/multi-filament", "type": "worked_on" }
```

| type | reads as |
| --- | --- |
| `part_of` | child → containing module/project |
| `implemented_by` | feature → module(s) that carry it |
| `shipped_in` | feature / module → release that shipped it |
| `worked_on` | feature → branch where the work lives |
| `depends_on` | build/runtime dependency (module → module, feature → external) |
| `integrates` | module → external service it integrates |
| `hosted_on` | artifact/module → external platform (Play, Cloudflare, GitHub) |
| `runs_on` / `verified_on` | work → device used |
| `documented_by` | anything → doc that describes it |
| `produced_by` | artifact → module/release that builds it |
| `verified_by` | feature/release → test, artifact, or screenshot set |
| `guards` | rule → module/feature it protects |
| `blocks` | issue → what it blocks |
| `affects` | issue → what it degrades |
| `supersedes` | newer release/doc/module → the one it replaced |
| `derived_from` | ported or vendored source provenance |
| `requires` | feature → rule/secret/artifact it cannot work without |

`kb.py check` rejects unknown kinds, unknown statuses, malformed ids/dates, duplicate ids,
edges pointing at missing nodes, and features marked `shipped` without a `shipped_in` edge.

## 3. Update protocol (what an agent does)

**Before starting work** — read `knowledge/STATUS.md` (or `kb.py query --kind feature`) so you
know what is already shipped, what is in flight on a branch, and what the recorded next actions
are.

**After a work session** — always, even for a one-line fix:

1. Update every node the session changed: `status`, `summary`, `updated`, `next`, `refs`,
   `verification`. Use `kb.py node set` for small edits, edit `graph.json` for bigger ones.
   Add nodes for anything you created (feature, branch, artifact, issue, rule).
2. Append one journal line — this is the append-only history, keep it honest about verification:

   ```bash
   python3 knowledge/kb.py session add \
     --agent pi --summary "Fixed X so Y" \
     --touched feat:gcode-preview,mod:android-app \
     --evidence commit:ab64461 \
     --verdict "debug build installed on Pixel 4a, screenshot in chat"
   ```

3. Re-render: `python3 knowledge/kb.py render` (it runs `check` first and refuses on errors).
4. Commit `knowledge/` **in the same commit** as the code it describes. If a change belongs to a
   branch that will be squashed for the public mirror, the graph rides along.

If a session ended in failure or a half-state, still record it: `--verdict` says what really
happened, and an `issue` node keeps the failure visible instead of buried in a chat log.

## 4. Conventions

- One writer per branch at a time (same rule as everywhere else in this repo).
- `sessions.jsonl` is append-only: never rewrite history, only append. Entries are
  `{date, agent, summary, touched[], evidence[], verdict?, backfilled?}`;
  `"backfilled": true` marks entries reconstructed from git history rather than logged live.
- Dates are ISO `YYYY-MM-DD` UTC. IDs are lowercase-kebab. No secrets in the graph: reference
  `file:secrets/build.properties`, never its values.
- Truth ranking when sources disagree: git history > Play Store API > generated docs > prose.
  `kb.py` records what it verified and when, so stale claims are detectable.
- Scope: this graph describes the *project*, not the codebase's internal call graph — pi-lens
  (`project_report`, `module_report`, `csearch`) answers that. The graph answers "where does the
  project stand and where is each capability's source".
