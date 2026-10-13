# Jules task data — fields observed via third-party tooling

Sources:
- tommyzed/jules-task-manager (fork of n24q02m/jules-task-archiver)
  https://github.com/tommyzed/jules-task-manager
  - Chrome Web Store "Jules Task Manager" v2.5, updated 2026-09-22
  - GitHub metadata license: Apache-2.0, but README "License" section says MIT
    (inconsistency — verify before reusing code).
- Upstream n24q02m/jules-task-archiver CLAUDE.md / AGENTS.md (updated ~6 days ago)

## How the data is reached (verified from project docs/code layout)

- Jules has NO public REST API for task history. The extension talks to an
  internal endpoint from *inside the user's own logged-in browser session*:
  `https://jules.google.com/_/Swebot/data/batchexecute`
- Auth: content script `main-world.js` runs in the page's MAIN world and reads
  auth tokens (`SNlM0e`, `cfb2h`, `FdrFJe`) from `WIZ_global_data`; also
  observes fetch() for StartSuggestion calls to capture model config and
  experiment IDs. No DOM automation; direct HTTP calls with the session.
- This proves task data IS reachable page-side, but only with the user's own
  live Google session. Nothing here works server-side or token-free.

## Known RPCs (opaque Google-internal IDs; may change without notice)

| RPC            | ID      | Returns / does                       |
|----------------|---------|--------------------------------------|
| ListTasks      | p1Takd  | All active tasks                     |
| ArchiveTask    | Tjmm5c  | Archives a single task               |
| ListSources    | YqkSHd  | Connected repositories               |
| ListSuggestions| hQP40d  | Code suggestions for a repo          |
| StartSuggestion| Rja83d  | Starts a suggestion as a new task    |
| (daily quota)  | KQOO7   | Remaining daily suggestion quota     |

## Task fields attested by the extension's behavior

- Task title, task id, status/state (completed vs active filtering)
- Connected GitHub repo, branch info (GitHub PR cross-check via api.github.com)
- Task prompt (the original request text)
- Suggestion category (security / performance / testing / cleanup), generated
  per-suggestion prompts, model config + experiment IDs
- Multi-account support (`/u/0`, `/u/1`, ...)

## NOT attested by these sources

- Activity log contents, plan steps, full conversation turns, diffs/patches,
  per-turn timestamps. The extension archives tasks and checks GitHub PRs
  rather than reading diffs from Jules, which suggests diffs are NOT easily
  available through these RPCs. The Jules web UI itself shows these per task,
  so a companion userscript doing DOM/API capture on jules.google.com is the
  realistic capture route; the exact RPC shapes need a live logged-in session
  to map (RPC field decoding was not published).

## Implication for Conversation Archive

A Jules companion userscript should: (1) observe the batchexecute
conversation/list RPCs in page context (like ListTasks p1Takd) to enumerate
tasks, (2) open each task and capture rendered activity/plan/output panels,
(3) export a JSON bundle per task. No credential or token handling in the
userscript beyond the ambient logged-in session — the user is already logged
in on their own device.
