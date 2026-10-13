# Conversation Archive

**Version 1.2.0 — 2026-10-13.** A 100%-local Android app that archives
conversations from **Gemini** (gemini.google.com), **Google AI Studio**
(aistudio.google.com), and **Google Jules** (jules.google.com) into one
searchable, exportable personal library. No accounts, no telemetry, no
servers — your archive never leaves your device.

## How capture works (v1.1: in-app browsers)

Each service opens in an **in-app browser** right inside Conversation
Archive:

1. **Log in once** — tap Gemini, AI Studio, or Jules on the home screen.
   You sign in with your Google account on Google's own login pages, inside
   the app. The login persists (WebView cookies), so you don't re-login
   every time.
2. **Tap "Save conversation"** — the floating button on the browser screen.
   The app scrolls the page to load the full history (bounded, with a
   progress indicator), reads the visible conversation, and saves it
   straight into your library. Duplicates are detected and skipped.
3. **Files the page offers for download** (e.g. an export) are intercepted,
   saved to the app's private storage, and offered for one-tap import.

What the app NEVER does: it never reads password fields, never copies your
cookies or tokens anywhere, and makes no network calls of its own except
loading the three official sites (plus same-site file downloads the page
itself offers). Credentials are typed by you into Google's pages only.

**Login caveat (read this):** Google sometimes refuses sign-ins from
embedded WebViews ("Couldn't sign you in / This browser or app may not be
secure"). Since v1.2.0 the app's default browser identity is your *genuine*
mobile-Chrome user agent with only the `; wv` WebView marker stripped —
the community-standard fix (a May 2026 report from a shipped app found
Google relaxed the block for honest WebViews, while spoofed desktop UAs
with inconsistent client hints now trip bot detection). If Google still
blocks you, the browser screen detects the block page itself and offers
three options: **Try another mode** (cycles Standard → Desktop → System
default and reloads), **External browser**, or Dismiss. A persistent
browser-identity setting also lives under Settings → In-app browser. If a
site won't log in inside the app, use the per-screen "Open in external
browser" button and import the export — the v1.0 workflow below still works.

## Fallback: userscript capture (v1.0 workflow)

If the in-app browser doesn't suit you:

1. Open the official site in your external browser (guide screens have
   "Open site in browser" buttons).
2. Export with a companion userscript (Tampermonkey/Violentmonkey in a
   userscript-capable Android browser; see
   `app/src/main/assets/userscript-guide.md`), which adds an
   "Export to Archive (JSON)" button to the page.
3. Share the downloaded JSON to Conversation Archive, or use Import
   Conversations.

Other fallbacks: import exports from third-party tools (Voyager JSON,
aistudio-dump-script, and generic Gemini/AI Studio formats are recognized
automatically), import a Google Takeout "My Activity > Gemini Apps" export
(JSON), or import `.md` / `.txt` / `.html` / `.pdf` files best-effort.

## Features

- **In-app browsers (v1.1):** Gemini, AI Studio, and Jules open inside the
  app. Log in once with your Google account (login persists via WebView
  cookies); tap "Save conversation" to archive the open chat/task with
  duplicate detection. Page-offered downloads are intercepted and offered
  for import. Each browser screen also offers "Open in external browser"
  and the capture guide.
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
- **Privacy:** no accounts, no analytics, no network SDKs. Since v1.1 the
  app holds the INTERNET permission, used ONLY to load the three official
  sites in the in-app browsers (and same-site downloads). Credentials are
  typed into Google's pages only; the app never reads or transmits them.
- **Theme:** Material3, light + dark (system default, manual override),
  dynamic color off by default.

## Honest limitations

- **In-app login is at Google's mercy.** Embedded-WebView logins sometimes
  get refused or challenged by Google. Since v1.2.0 the default is the
  honest UA with only `; wv` stripped (Standard mode); Desktop (the old
  v1.1 spoof) and System default remain as fallbacks, switchable in
  Settings → In-app browser or via the on-page "Try another mode" dialog
  that appears when the block is detected. If login fails in-app, the
  external-browser + import fallback always works.
- **The extractors are EXPERIMENTAL.** Both the in-app extractor
  (`app/src/main/assets/extractor.js`) and the companion userscripts capture
  only *rendered* content — virtualized/scrolled-away messages, collapsed
  branches, and login walls are out of reach. DOM selectors are
  Google-markup-dependent and may break when layouts change; the in-app
  capture reports what it found, and failures say why instead of staying
  silent. Full per-script limits are documented in each `.user.js` header
  and in `app/src/main/assets/userscript-guide.md`.
- **Per-platform capture limits:** AI Studio thought blocks, model name,
  temperature, and system instruction are best-effort. For Jules, activity-log
  contents, plan steps, per-entry timestamps, and diffs were not confirmed
  extractable by any reviewed source — they are attempted generically and may
  come back empty. Nothing is ever invented; missing fields are null/absent.
- **PDF export and Unicode:** PDFs use a hand-rolled writer with Base-14
  fonts (WinAnsi). Non-WinAnsi Unicode characters are replaced with `?` in
  PDF output. All other formats preserve Unicode fully.
- **UI testing:** the build machine has no Android emulator, so the UI is
  covered by JVM unit tests, not instrumentation tests — the Compose UI and
  WebView screens are built but not emulator-tested. Unit test results are
  in TEST_REPORT.md.
- **Debug build:** APKs are DEBUG builds signed with the Android debug key,
  not production releases.
- **Uninstall / Clear data deletes the archive** (including saved logins —
  you will need to sign in again after reinstalling). Back up via Export and
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
                   #          Export, Backup, Settings, Platform guides,
                   #          in-app Browsers)
        browser/   # WebView config, JS-bridge result parsing, download helper
      util/        # hashing, dates, IO
    assets/
      extractor.js         # injectable conversation extractor (v1.1)
      userscript-guide.md  # in-app capture guide
    res/ AndroidManifest.xml
  dist/                        # built APKs go here
```

## Privacy

- 100% local: no accounts, no analytics, no crash reporting, no ads.
- Since v1.1 the app requests the INTERNET permission, used only to load
  gemini.google.com, aistudio.google.com, and jules.google.com (plus
  Google's login pages) in the in-app browsers, and to fetch same-site file
  downloads the page itself offers. No other network calls are made.
- No passwords, cookies, or tokens are ever requested by the app, read from
  form fields, stored outside the WebView's own cookie jar, or transmitted
  anywhere. You type credentials into Google's pages only; the extractor
  reads only the conversation DOM you can already see.
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

1.2.0 — 2026-10-13. (1.1.0 — 2026-10-13: in-app browsers + one-tap capture. 1.0.0 — 2026-10-12: first release, userscript-based capture.)
