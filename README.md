# Conversation Archive

**Version 1.0.0 — 2026-10-12.** A 100%-local Android app that archives
conversations from **Gemini** (gemini.google.com), **Google AI Studio**
(aistudio.google.com), and **Google Jules** (jules.google.com) into one
searchable, exportable personal library. No accounts, no telemetry, no
servers — your archive never leaves your device.

## How capture works (3 steps)

The app cannot reach into your logged-in Google sessions, and it must never
handle your passwords or cookies. So capture is a deliberate handoff:

1. **Open the official site in your external browser** — the app's guide
   screens have "Open site in browser" buttons. Opening the site captures
   nothing by itself.
2. **Export with a companion userscript** — install Tampermonkey or
   Violentmonkey in a userscript-capable Android browser (Firefox Android is
   the easiest path; Quetta, Lemur, Helium, Mises, and Edge Android also
   work — **Kiwi Browser is discontinued, do not use it**), add the
   companion script for that site from `userscripts/` in this repo, open
   the conversation while logged in, and tap the floating
   **"⬇ Export to Archive (JSON)"** button. It downloads canonical-schema
   JSON and tells you how many messages/entries it found.
3. **Import into the app** — share the downloaded file to Conversation
   Archive, or use Import Conversations and pick the file.

Full per-site instructions are in
`app/src/main/assets/userscript-guide.md` (also shown in-app).

Fallbacks if the userscript route doesn't suit you: import exports from
third-party tools (Voyager JSON, aistudio-dump-script, and generic
Gemini/AI Studio formats are recognized automatically), import a Google
Takeout "My Activity > Gemini Apps" export (JSON), or import `.md` / `.txt`
/ `.html` / `.pdf` files best-effort.

## Features

- **Library:** browse conversations grouped by Gemini / AI Studio / Jules;
  search, filter by platform/date, sort, tag, favorite, add notes, rename.
- **Detail view:** transcript with role labels, timestamps, code blocks,
  attachment metadata; raw-JSON inspector for every record.
- **Import:** canonical JSON v1 plus recognized third-party exporter
  formats (see Attribution), Markdown/TXT heuristics, HTML, and a minimal
  embedded PDF text extractor. Every import reports errors and warnings
  verbatim — nothing is silently dropped.
- **Export:** canonical JSON (original source preserved verbatim), Markdown,
  TXT, HTML, real PDF, multi-conversation ZIP. Unicode-safe everywhere
  except PDF (see Limitations).
- **Backup & restore:** SAF-based ZIP (manifest + one canonical JSON per
  conversation + raw originals); restore re-imports through the normal
  pipeline so backups survive app/schema changes.
- **Duplicates:** SHA-256 content-hash detection — duplicates are reported
  and skipped, with an explicit "import anyway" path.
- **Privacy:** no accounts, no analytics, no network SDKs. The only network
  use is firing an intent that opens an official site in *your* browser.
- **Theme:** Material3, light + dark (system default, manual override),
  dynamic color off by default.

## Honest limitations

- **The userscripts are EXPERIMENTAL.** They capture only *rendered*
  content — virtualized/scrolled-away messages, collapsed branches, and
  login walls are out of reach. DOM selectors are Google-markup-dependent
  and may break when layouts change; each script reports its found count so
  misses are visible, not silent. Full per-script limits are documented in
  each `.user.js` header and in `app/src/main/assets/userscript-guide.md`.
- **Per-platform capture limits:** AI Studio thought blocks, model name,
  temperature, and system instruction are best-effort. For Jules, activity-log
  contents, plan steps, per-entry timestamps, and diffs were not confirmed
  extractable by any reviewed source — they are attempted generically and may
  come back empty. Nothing is ever invented; missing fields are null/absent.
- **PDF export and Unicode:** PDFs use a hand-rolled writer with Base-14
  fonts (WinAnsi). Non-WinAnsi Unicode characters are replaced with `?` in
  PDF output. All other formats preserve Unicode fully.
- **UI testing:** the build machine has no Android emulator, so the UI is
  covered by JVM unit tests, not instrumentation tests — the Compose UI is
  built but not emulator-tested. Unit test results are in TEST_REPORT.md.
- **Debug build:** APKs are DEBUG builds signed with the Android debug key,
  not production releases.
- **Uninstall / Clear data deletes the archive.** Back up via Export and
  Backup first (see "Uninstall / data clear" below).

## Install

1. On your phone, enable installing apps from unknown sources (Android:
   Settings > Apps > Special app access > Install unknown apps > allow your
   browser or file manager).
2. Download the right APK from `dist/`:
   - **arm64-v8a** — almost every phone made since ~2017 (most likely yours).
   - **armeabi-v7a** — older/cheaper 32-bit devices.
   - **universal** — works on both; larger download.
3. Open the APK to install. These are debug builds: Android may warn that
   the app was built for testing — that warning is accurate.

## Build from source

Requirements: **JDK 17**, Android SDK with **compileSdk 34**, and the
Gradle wrapper in this repo (AGP 8.5.2, Kotlin 1.9.24, KSP).

```bash
cd conversation-archive
./gradlew assembleDebug        # per-ABI APKs + universal land in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # JVM unit tests (schema, parsers, exporters, PDF writer, backup round-trip)
```

Splits produce `app-armeabi-v7a-debug.apk`, `app-arm64-v8a-debug.apk`, and
`app-universal-debug.apk` (all debug-signed).

> Build-environment note: on the sandboxed build VM used for v1.0.0, Gradle
> needed `GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"` and
> `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true
> -Djdk.tls.client.protocols=TLSv1.2"` (proxy/TLS quirks), plus the sandbox
> egress CA imported into the JDK's `cacerts`. See `DECISIONS.md` (D0–D0d).
> On a normal machine with direct internet access, plain `./gradlew` works.

## Project layout

```
conversation-archive/
  README.md / ARCHITECTURE.md / DECISIONS.md / RESEARCH.md
  userscripts/                 # companion userscripts (original code)
    convarchive-gemini.user.js
    convarchive-aistudio.user.js
    convarchive-jules.user.js
  fixtures/                    # third-party exporter format samples
  app/src/main/
    java/app/convarchive/
      data/        # Room entity/DAO/repository
      model/       # canonical schema + parsers (canonical, Voyager,
                   #          AI-Studio exporters, Markdown/Text/HTML/PDF)
      export/      # JSON/Markdown/TXT/HTML/PDF/ZIP exporters + filenames
      backup/      # SAF backup/restore manager
      ui/          # Compose screens (Home, Library, Detail, Import,
                   #          Export, Backup, Settings, Platform guides)
      util/        # hashing, dates, IO
    assets/userscript-guide.md # in-app capture guide
    res/ AndroidManifest.xml
  dist/                        # built APKs go here
```

## Privacy

- 100% local: no accounts, no analytics, no crash reporting, no ads.
- No passwords, cookies, or tokens are ever requested, stored, or
  transmitted — the userscripts run in *your* browser and only read what it
  already shows; nothing is sent to us or anyone else.
- Delete requires confirmation (single and multi-delete).

## Uninstall / data clear

**Uninstalling the app or using "Clear data" permanently deletes your
archive.** The app says this in Settings > Storage and warns before
backup. The escape hatch: use Export and Backup to save a ZIP wherever you
like (local storage, cloud drive) before uninstalling, then restore it
after reinstalling.

## Attribution — researched inspirations

These third-party projects were researched (Oct 2026, see RESEARCH.md) to
understand capture formats. The app's userscripts and code are **original** —
they do not copy or adapt these projects' code, only import their exported
files as black-box formats where noted:

- rmtd418/ai-studio-conversation-exporter — MIT — https://github.com/rmtd418/ai-studio-conversation-exporter
- Tokisaki-Galaxy/aistudio-dump-script — AGPL-3.0 — https://github.com/Tokisaki-Galaxy/aistudio-dump-script
- voyager-crew/voyager — GPL-3.0 — https://github.com/voyager-crew/voyager
- caffeinegmt/gemini-export — MIT — https://github.com/caffeinegmt/gemini-export
- davidmalko87/gemini-chat-exporter — MIT — https://github.com/davidmalko87/gemini-chat-exporter
- matthewmcneill/gemini-takeout-viewer — Apache-2.0 — https://github.com/matthewmcneill/gemini-takeout-viewer
- tommyzed/jules-task-manager — Apache-2.0/MIT (conflicted, verify before reuse) — https://github.com/tommyzed/jules-task-manager
- "Google AI Studio Exporter" (Chrome Web Store) — closed source; claims unverifiable.

Copyleft note: AGPL-3.0 and GPL-3.0 projects are import-format references
only. No AGPL/GPL code is included in this project.

## License

*(License file to be added by the project owner.)*

## Version

1.0.0 — 2026-10-12.
