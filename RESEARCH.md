# Conversation Archive — Research: conversation-capture tooling

Date: 2026-10-12. All five mandated URLs were fetched and inspected (code and/or
README, not memory). Additional tools were found via web search. Claims below
distinguish **code-verified** facts from **README/doc claims** and from
**inferred** items.

Bottom line: every working tool captures from the user's own logged-in browser
session (DOM scraping or internal-request interception). There is no public or
official API that returns a user's Gemini / AI Studio / Jules conversation
history without their login session. The realistic architecture is therefore:
**companion userscript (runs in the user's browser, in their session) +
import of third-party export formats**, plus Google Takeout import for Gemini.
Anything claiming otherwise is either wrong or relies on credential harvesting.

## 1. Mandated sources

### 1.1 rmtd418/ai-studio-conversation-exporter (GitHub, MIT)

- URL: https://github.com/rmtd418/ai-studio-conversation-exporter
- License: MIT (per GitHub repo metadata). Reuse/adaptation permitted with
  attribution.
- Status: created 2026-08-05, **1 commit** (d056c6c), 1 star, 0 forks,
  0 releases. Effectively unmaintained.
- What it is: Chrome MV3 extension (extension/: manifest.json, background.js,
  content.js, page-bridge.js, popup.*, floating.css). Load-unpacked only; not
  on the Chrome Web Store.
- What it can actually retrieve (README claims; source NOT line-by-line
  reviewed): intercepts AI Studio's **conversation resource network request**
  ("reads the conversation resource directly") rather than DOM scraping, so
  export speed does not depend on scrolling/virtualization. Preserves real
  order: user message -> model thought -> model answer. Keeps multiple model
  responses and non-text user turns. Optional model-thought export. The
  `page-bridge.js` file name confirms a page-world bridge for request
  interception.
- Formats: JSON, Markdown, TXT.
- Schema (from README): JSON places the ordered conversation in `turns` and
  `entries`. See fixtures/rmtd418_sample_INFERRED.json (**inferred**, not
  code-verified).
- Permissions: storage, downloads, tabs; host access limited to
  https://aistudio.google.com/*.
- Limitations: single-commit project; internal-request approach is robust to
  DOM churn but fragile to Google changing its undocumented internal API.
  No evidence of timestamp, attachment, or multi-turn-branch handling.

### 1.2 "Google AI Studio Exporter" (Chrome Web Store, v2.5.1)

- URL: https://chromewebstore.google.com/detail/google-ai-studio-exporter/ccahfgoehbkchljcjpclmgbfohefmgnh
- Offered by nietosarah456. Version 2.5.1, updated 2026-01-05. Size 36.25 KiB.
  No public source repository found (privacy-policy link points at github.com
  but no repo was locatable). Treat as closed-source.
- What it can actually retrieve (store listing claims; no code to inspect):
  handles AI Studio's virtual scrolling to capture "the complete" chat,
  filters UI clutter, keeps User->Model order, optional system instructions
  and page URLs in the export. Local-only processing.
- Formats: Markdown (.md), JSON, plain text (.txt).
- Schema: not published. No fixture possible beyond a generic illustration.
- Limitations: unverifiable claims (no source); store listing is marketing
  copy. Developer identity is thin (non-trader, single contact email).
  Closed-source means it cannot serve as a reference implementation and its
  format cannot be imported against a spec.

### 1.3 Tokisaki-Galaxy/aistudio-dump-script (GitHub + GreasyFork, AGPL-3.0)

- URL: https://github.com/Tokisaki-Galaxy/aistudio-dump-script
- Install: https://greasyfork.org/zh-CN/scripts/557864-ai-studio-chat-exporter-markdown-code-block-support
- License: **AGPL-3.0**. Copyleft: adapting this code into the app triggers
  source-disclosure obligations on distribution. Write an original userscript
  inspired by its *approach*; do not copy code.
- Status: created 2025-12-04, 9 commits (master), 1 release, 6 stars.
  GreasyFork v5.0, 102 total installs, last updated 2025-12-04. Dormant ~10
  months.
- What it can actually retrieve (**source reviewed in full**, GreasyFork code
  page v5.0): Tampermonkey userscript, `@match https://aistudio.google.com/prompts/*`,
  `@grant none` (pure DOM, no GM APIs, no cross-origin fetch).
  - Scroll automation: jumps `ms-autoscroll-container` to top, then scrolls
    in 350px steps with 1200ms pauses, collecting `ms-chat-turn` elements,
    until bottom or 3 stuck passes.
  - System instruction: from `ms-system-instructions-panel .subtitle`,
    falling back to `ms-system-instruction-editor textarea`; auto-clicks
    `.runsettings-toggle-button` to expand the sidebar if collapsed.
  - Markdown conversion: headings, lists, bold/italic, inline code, and
    `MS-CODE-BLOCK` -> fenced code blocks with language.
  - **Does NOT capture model thoughts**: `MS-THOUGHT-CHUNK` elements are
    explicitly skipped in code.
  - Role detection: `user` default; `assistant` if `.model-prompt-container`
    present or `data-turn-role="Model"`.
- Output schema (code-verified):
  `{ "system_instruction": "...", "messages": [ { "role": "user"|"assistant", "content": "..." } ] }`
  See fixtures/aistudio-dump-script_v5.0_sample.json.
- Limitations: **no timestamps, no attachments/images, no message ids, no
  branches, no thoughts**. DOM selectors are fragile
  (`ms-autoscroll-container`, `ms-chat-turn`); only matches
  `aistudio.google.com/prompts/*`, so newer AI Studio URL shapes are not
  covered. Prerequisites say "desktop Chromium" — not mobile-tested.

### 1.4 voyager-crew/voyager ("Voyager", GPL-3.0)

- URL: https://github.com/voyager-crew/voyager (redirects from nagi-ovo/voyager)
- License: **GPL-3.0**. Copyleft like AGPL for distributed apps: do not adapt
  its code; treat as a black-box tool whose *output format* we can import.
- Status: very active and maintained. 20,362 stars, 684 forks, 2,706 commits,
  108 releases, 111 tags. Docs: https://voyager.nagi.fun/en/guide/export
- What it can actually retrieve (docs claims; export code not line-by-line
  reviewed): enhancement suite for Gemini, AI Studio, Claude, ChatGPT,
  DeepSeek. Chat Export (Gemini-exclusive feature) exports "full chat content
  — including code blocks, images, and formatting". Deep Research sessions
  export with thinking process, source links, and final report. Works on
  Chrome, Edge, Firefox, Safari, Opera, Brave. Prompt Vault and folder
  organization are separate features.
- Formats: Markdown (images embedded), PDF (images included), JSON (images
  base64-encoded).
- Schema (from project's AGENTS.md `ConversationExportService.toJSON`):
  `{ format: 'gemini-voyager.chat.v1', url, exportedAt, count, items }`.
  See fixtures/voyager_export_sample.json. Exact `items` fields not verified.
- Limitations: export feature is Gemini-focused per docs ("Gemini Exclusive"),
  though "Supported Platforms" says export works on AI Studio too. GPL-3.0
  blocks code reuse in a proprietary app; JSON import is the safe
  integration point. As a DOM-based tool it is subject to the same
  Google-UI-churn risk, mitigated by an active maintainer.

### 1.5 "Jules Task Manager" (Chrome Web Store, v2.5) + tommyzed/jules-task-manager

- Store URL: https://chromewebstore.google.com/detail/jules-task-manager/chlkogegcamaipaecfekkbedajlmfoac
  (v2.5, updated 2026-09-22, 75.34 KiB, developer egodevnull.com)
- Source: https://github.com/tommyzed/jules-task-manager — a fork of
  n24q02m/jules-task-archiver. License metadata says **Apache-2.0** but the
  README "License" section says **MIT** — inconsistency; verify before
  reusing any code. 220 commits, created 2026-07-11; upstream updated ~6 days
  ago (active).
- Important: this is **not a conversation exporter**. It bulk-archives Jules
  tasks, starts code suggestions, and removes suggestions. But it is the best
  public documentation of what data Jules exposes to page-side tooling:
  - Endpoint: `https://jules.google.com/_/Swebot/data/batchexecute`
    (internal, undocumented Google RPC; works only inside the user's own
    logged-in session).
  - Auth: `main-world.js` reads tokens (`SNlM0e`, `cfb2h`, `FdrFJe`) from
    `WIZ_global_data` in the page's MAIN world; observes fetch() for
    StartSuggestion config.
  - Known RPCs: ListTasks `p1Takd` (all active tasks), ArchiveTask `Tjmm5c`,
    ListSources `YqkSHd` (connected repos), ListSuggestions `hQP40d`,
    StartSuggestion `Rja83d`, daily quota `KQOO7`.
  - Attested task fields: task title, task id, status/state, connected GitHub
    repo + branch, task prompt, suggestion category/prompts, model config,
    experiment IDs, multi-account (`/u/0`, `/u/1`).
  - NOT attested: activity-log contents, plan steps, per-turn timestamps,
    diffs/patches. The extension checks GitHub PRs via api.github.com rather
    than reading diffs from Jules, suggesting diffs are not easily available
    through these RPCs.
- See fixtures/jules_task_fields_observed.md for the full field inventory.
- Limitation for our purposes: no export feature exists; a Jules capture
  would need a new userscript that (a) calls/observes ListTasks to enumerate
  tasks and (b) captures the rendered activity/plan/output panels per task.
  RPC shapes are opaque and can change without notice.

## 2. Additional tools found via search

### Gemini (gemini.google.com)

- **caffeineGMT/gemini-export** (MIT, created 2026-03-18, 1 commit):
  console-pasted scraper; expands sidebar, clicks through each conversation
  (~2.5s each). Output: `{exported_at, source, conversation_count,
  total_messages, conversations: [{index, title, url, messageCount, messages:
  [{role, content}], extracted_at}]}`. Motivation: "Google Takeout often
  returns empty files for Gemini data" (as of Mar 2026). No timestamps,
  attachments, or thought capture documented. Fixture:
  fixtures/gemini-export_caffeinegmt_sample.json
- **davidmalko87/gemini-chat-exporter** (MIT, created 2026-06-27, 5 commits,
  v0.1.3): userscript capture + Python toolkit. Capture streams NDJSON
  `{id, title, turns, source, flags}` per line; full-history runs take
  30-45 min with the tab open and in front; hardened with content-identity
  validation, SHA-256 duplicate-body guard, resume, and loud failures.
  `--verify` audits any export; `--import-takeout` ingests Takeout's
  `MyActivity.json`; `--reconcile` diffs scrape vs Takeout. Notably honest
  about silent-corruption failure modes of naive scrapers. Fixture:
  fixtures/gemini-chat-exporter_davidmalko87_sample.ndjson
- **matthewmcneill/gemini-takeout-viewer** (Apache-2.0): browser-based viewer
  for Takeout Gemini exports; Markdown export with Turndown conversion,
  attachment/canvas support. Confirms Takeout Gemini Apps JSON is a usable
  import source.
- **maciekk/gemini-slurp**: Python importer of Takeout Gemini data to
  Obsidian; notes Takeout groups turns heuristically (time-gap based) and
  supports scheduled recurring Takeout exports (every 2 months, up to a year).
- **Agelakz/universal-ai-chat-exporter** (docs reviewed): provider-adapter
  design; for Gemini it observes the `hNvQHb` batched-RPC conversation
  request in page context, reuses the authenticated payload to request an
  expanded history, and never replaces a complete capture with a later short
  chunk. DOM fallback if capture fails. Good reference *approach* (do not
  copy code without checking its license).
- **revivalstack/ai-chat-exporter**, **0xenzyme/ai-chat-exporter**,
  **covai-labs/ai-chat-exporter** (privacy-focused, 100% local): additional
  Gemini-capable exporters found but not deep-reviewed.
- GreasyFork: **Gemini2Markdown** (thinking steps, model-name detection,
  dual/parallel responses, partial exports via context menu), **Gemini
  Helper** (conversation management + export, outline navigation, reading
  history).

### AI Studio (aistudio.google.com)

- **rajat-xr/google-ai-studio-chat-exporter** (+ Chrome Web Store listing):
  exports AI Studio to Markdown/PDF/JSON, DOM traversal with viewport
  scrolling to mount virtualized chunks. Not deep-reviewed.
- **zaydkhalid/ai-studio-pdf-export**: DOM scraper with multi-strategy
  selectors (`ms-chat-turn`, etc.) and documented selector-update procedure
  for when Google changes the DOM. Not deep-reviewed.
- **cristianerberich / Paper-Crane-Devteam better-sidebar-for-google-ai-studio**:
  folders, tags, full-text search, conversation export (MD/TXT/JSON), local
  SQLite. Not deep-reviewed.
- GreasyFork: **Google AI Studio Exporter** userscript (auto-scrolling,
  user/model role differentiation, "full mobile optimization" — the only
  exporter explicitly claiming mobile optimization), **Google AI Studio -
  Ultimate Optimizer UI** (export JSON/MD).

### Jules

- **n24q02m/jules-task-archiver** (upstream of the store extension): same
  architecture, adds scheduled auto-archive. Active (~6 days ago).
- **gemini-cli-extensions/jules**: official-ish Gemini CLI extension to
  *create* Jules tasks — not a capture tool, but shows Jules' task model
  (async agent tasks on GitHub repos, new branches per task).

## 3. Google Takeout coverage (researched Oct 2026)

- **Gemini app (gemini.google.com): YES, partially.** Path: takeout.google.com
  -> Deselect all -> My Activity -> "All activity data included" -> keep only
  "Gemini Apps" -> JSON format. Multiple independent projects
  (davidmalko87, matthewmcneill, maciekk) confirm transcripts (prompts and
  responses) are present in recent exports, though the schema varies by
  account and is under-documented, and empty-file exports have been reported
  (Mar 2026). The standalone **"Gemini"** Takeout product exports *Gems*,
  not chat history — select "Gemini Apps" under My Activity, not that.
  Recurring exports (every 2 months, up to a year) are supported.
- **AI Studio: NO coverage found.** No source documents AI Studio prompts
  appearing in Takeout. Treat as not exported.
- **Jules: NO coverage found.** Treat as not exported.
- Implication: Takeout import is a viable *secondary* source for Gemini only.
  It is manual (user downloads a ZIP and imports it), prompt-centric rather
  than conversation-centric (one project groups turns heuristically by time
  gap), and cannot be automated inside the app without the user doing the
  Takeout dance.

## 4. Is there any official/public API for conversation history?

**No.** Verified points:

- The Gemini Developer API (generativelanguage) is stateless: clients pass
  history per request (`startChat(history=...)` in SDKs / Firebase AI Logic).
  There is no endpoint that lists or reads a user's past gemini.google.com or
  AI Studio conversations.
- The only documented conversation-list/message-list APIs found are for a
  different product: the Gemini for Google Cloud "conversational analytics"
  API (`geminidataanalytics.googleapis.com`), unrelated to consumer chat
  history.
- What exists instead are *undocumented internal* RPCs used by the web apps:
  Gemini's batched RPC (e.g. the `hNvQHb` conversation request), AI Studio's
  "conversation resource request", Jules' `/_/Swebot/data/batchexecute`.
  All require the user's own authenticated session. Third-party projects
  (e.g. Leechael/Gemini-API) drive these with the user's *cookies* — that is
  credential use, not a public API, and must not be adopted or recommended.
- Conclusion: without the user's logged-in browser session there is no
  legitimate way to read their history. The app must capture in-session
  (userscript) or import user-supplied files (Takeout, third-party exports).

## 5. Jules: what the web UI exposes per task

From the Jules Task Manager project documentation (no official Jules API
docs were found; jules.google/docs covers usage, not a data API):

- Task title, task id, status/state, linked GitHub repo and branch, the
  original task prompt, code suggestions per repo with category
  (security/performance/testing/cleanup), generated per-suggestion prompts,
  model configuration, experiment IDs, remaining daily quota, and
  multi-account session handling.
- The Jules web UI additionally renders per-task activity logs, plans, and
  outputs — but the exact RPC shapes/fields for these were **not published**
  by any reviewed source. Mapping them requires a live logged-in session and
  network inspection; treat as open research, not a solved problem.
- Diffs: the extension cross-checks GitHub PRs instead of reading diffs from
  Jules, so do not assume patch data is reachable via the known RPCs.

## 6. Android browsers with userscript/extension support (verified Oct 2026)

- **Kiwi Browser: DEAD.** Discontinued by its developer in January 2025;
  archived on GitHub; removed from the Play Store. Do not recommend (frozen
  Chromium, no security updates).
- **Quetta** (Android, free): Chromium-based; installs extensions from the
  Chrome Web Store and Edge Add-ons natively; Tampermonkey works. Presented
  as the post-Kiwi gap-filler.
- **Lemur Browser** (Android; also iOS per one source): Chromium-based;
  Chrome Web Store + Edge Add-ons + local .crx/.zip sideload; Tampermonkey
  confirmed working.
- **Helium** (Android, indie Chromium fork by jqssun): full Manifest V2
  extension support in recent 2026 builds; install from Chrome Web Store
  requires enabling "Desktop site" mode as a workaround; framed by users as
  Kiwi's direct successor.
- **Mises Browser**: Chromium-based with extension support; Tampermonkey
  installable.
- **Microsoft Edge for Android**: supports extensions; Violentmonkey
  explicitly verified working on it by a userscript author. (Kiwi's extension
  code was integrated into Edge Canary back in 2025.)
- **Firefox for Android (release)**: open extension ecosystem since v120
  (Dec 2023); 1,000+ Android extensions; **Tampermonkey is in the AMO Android
  catalog** and installs directly (Menu > Add-ons). For unlisted/custom
  add-ons: Firefox Nightly/Beta "Custom Add-on Collection" via the debug
  menu, or `web-ext run -t firefox-android` for testing.
- **Tampermonkey's standalone Android browser**: exists but is an outdated
  Chromium beta — avoid.
- Net: on Android in 2026, userscript managers are installable via Firefox
  (easiest, official catalog), or via Quetta/Lemur/Helium/Mises/Edge with
  Tampermonkey or Violentmonkey from the Chrome Web Store. Any companion
  userscript for Conversation Archive must be tested against **mobile DOM**
  (these sites serve different markup/touch layouts on mobile) — desktop-only
  scripts like aistudio-dump-script are not proven on phones.

## 7. Comparison table

| Tool | Targets | Method | Formats | Full history? | Thoughts | Sys. instr. | Timestamps | Attachments | License | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| rmtd418/ai-studio-conversation-exporter | AI Studio | internal request intercept | JSON/MD/TXT | claimed (README) | optional (README) | n/a | unknown | unknown | MIT | 1 commit, dormant |
| Google AI Studio Exporter (CWS) | AI Studio | DOM + autoscroll (claimed) | MD/JSON/TXT | claimed (listing) | unknown | optional (listing) | unknown | unknown | closed | v2.5.1, 2026-01-05 |
| aistudio-dump-script | AI Studio | DOM + scroll automation | JSON | yes, via scroll (code) | **no** (skipped in code) | yes (code) | no | no | AGPL-3.0 | dormant since 2025-12-04 |
| Voyager | Gemini (+AI Studio) | DOM (docs) | MD/PDF/JSON | claimed (docs) | Deep Research only (docs) | n/a | unknown | images yes (docs) | GPL-3.0 | very active |
| Jules Task Manager | Jules | batchexecute RPC | n/a (no export) | n/a | n/a | n/a | n/a | n/a | Apache-2.0/MIT (conflicted) | active |
| caffeinegmt/gemini-export | Gemini | DOM click-through | JSON | yes, slow (README) | no | no | no | no | MIT | 1 commit, 2026-03-18 |
| davidmalko87/gemini-chat-exporter | Gemini | userscript + Takeout | NDJSON/JSON/MD | yes, 30-45 min (README) | no | no | no | no | MIT | active-ish (v0.1.3) |
| Google Takeout | Gemini app | official export | JSON/HTML | yes (varies) | no | no | yes (activity) | varies | n/a (Google) | current |

"n/a" = not applicable; "unknown" = not documented and not code-verified.
Nothing above was found to export *branches/alternate responses* except
rmtd418's README claim (multiple model responses kept).

## 8. Capture strategy recommendation

Recommended: **companion userscript + import of third-party exports**, for
these reasons:

1. **In-session capture is the only legitimate live route.** No official API
   exists; internal RPCs need the user's own logged-in session. A userscript
   (Tampermonkey/Violentmonkey/ScriptCat) running in the user's browser on
   gemini.google.com, aistudio.google.com, and jules.google.com is the honest
   mechanism. It must never exfiltrate credentials or session tokens — it
   reads the user's own visible data and writes a local export file, which the
   app then imports (share sheet / file picker).
2. **Prefer internal-request interception over DOM scraping where feasible.**
   rmtd418's approach (read the conversation resource request) and the
   Agelakz approach (observe the batched-RPC conversation request, replay for
   expanded history) are more robust than DOM scraping: they get complete,
   ordered data including thoughts/branches without scroll automation, and
   survive UI restyles. DOM scraping (aistudio-dump-script, Voyager) breaks
   whenever Google changes markup — and AI Studio's selectors
   (`ms-chat-turn`, `ms-autoscroll-container`) are already version-specific.
   The userscript should implement request-observation first, DOM as fallback.
3. **Import third-party formats.** Accept Voyager JSON
   (`gemini-voyager.chat.v1`), the aistudio-dump-script shape
   (`{system_instruction, messages[]}`), caffeinegmt's
   `{conversations[]}` shape, davidmalko87's NDJSON, and Takeout
   `MyActivity.json` (Gemini Apps). This gives users a path even if they
   prefer someone else's exporter, and Takeout is the only *authoritative*
   source for Gemini. Note AGPL-3.0/GPL-3.0 constraints: import their
   *files*, do not adapt their *code*.
4. **Write an original userscript; do not fork AGPL/GPL code.** A clean-room
   userscript implementing scroll-automation + request-observation is not a
   derivative work of aistudio-dump-script or Voyager. Keep it dependency-
   free, `@grant none` where possible, local-only, with no analytics.
5. **Jules needs dedicated research.** No existing exporter covers it. Next
   step: with a logged-in session, map the ListTasks/task-detail RPC shapes
   (or DOM panels) for title, id, prompt, status, timestamps, activity log,
   plans, and outputs; then build the userscript. The Jules Task Manager
   project proves page-side RPC access works.
6. **Mobile is the acceptance environment.** Test the userscript on the
   actual Android browsers users will run (Firefox + Tampermonkey is the
   lowest-friction path; Quetta/Lemur/Helium as alternates). Desktop-only
   assumptions (e.g. `@match .../prompts/*`) must be re-verified against
   mobile URL shapes and mobile DOM.
7. **Do not pursue:** cookie/token harvesting tools, unofficial
   cookie-driven APIs, or any server-side fetching of user history. None of
   the reviewed tools work without the user's own session, and suggesting
   otherwise would be dishonest and unsafe.

## 9. Fixtures

All in `fixtures/` (sandbox links):

- [aistudio-dump-script_v5.0_sample.json](sandbox://workspace/projects/conversation-archive/fixtures/aistudio-dump-script_v5.0_sample.json) — schema code-verified; AGPL-3.0 noted
- [gemini-export_caffeinegmt_sample.json](sandbox://workspace/projects/conversation-archive/fixtures/gemini-export_caffeinegmt_sample.json) — schema from README; MIT
- [gemini-chat-exporter_davidmalko87_sample.ndjson](sandbox://workspace/projects/conversation-archive/fixtures/gemini-chat-exporter_davidmalko87_sample.ndjson) — schema from README; MIT
- [voyager_export_sample.json](sandbox://workspace/projects/conversation-archive/fixtures/voyager_export_sample.json) — schema from project AGENTS.md; GPL-3.0 noted
- [rmtd418_sample_INFERRED.json](sandbox://workspace/projects/conversation-archive/fixtures/rmtd418_sample_INFERRED.json) — **inferred from README**, not code-verified; MIT
- [jules_task_fields_observed.md](sandbox://workspace/projects/conversation-archive/fixtures/jules_task_fields_observed.md) — observed RPCs/fields, no fabricated fixture

Samples are authored illustrations of each schema (not copies of project
code or real user data).

## 10. Sources

- https://github.com/rmtd418/ai-studio-conversation-exporter
- https://chromewebstore.google.com/detail/google-ai-studio-exporter/ccahfgoehbkchljcjpclmgbfohefmgnh
- https://github.com/Tokisaki-Galaxy/aistudio-dump-script
- https://greasyfork.org/zh-CN/scripts/557864-ai-studio-chat-exporter-markdown-code-block-support
- https://github.com/voyager-crew/voyager ; https://voyager.nagi.fun/en/guide/export
- https://github.com/tommyzed/jules-task-manager ; https://github.com/n24q02m/jules-task-archiver
- https://chromewebstore.google.com/detail/jules-task-manager/chlkogegcamaipaecfekkbedajlmfoac
- https://github.com/caffeinegmt/gemini-export
- https://github.com/davidmalko87/gemini-chat-exporter
- https://github.com/matthewmcneill/gemini-takeout-viewer
- https://github.com/maciekk/gemini-slurp
- https://github.com/Agelakz/universal-ai-chat-exporter
- https://github.com/rajat-xr/google-ai-studio-chat-exporter
- https://github.com/zaydkhalid/ai-studio-pdf-export
- https://github.com/cristianerberich/better-sidebar-for-google-ai-studio
- https://www.makeuseof.com/android-browsers-that-run-extensions-chrome-keeps-leaving-out/
- https://piunikaweb.com/2026/06/01/helium-browser-android-mv2-extensions/
- https://apkpure.com/lemur-browser-extensions/com.lemurbrowser.exts
- https://github.com/animeshkundu/youtube-audio/blob/HEAD/docs/research/03-firefox-mobile-support.md
- https://www.ghacks.net/2023/02/19/firefox-for-android-adds-tampermonkey-support/
