# Conversation Archive

**Version 1.2.0 — 2026-10-13.** A 100%-local Android app that archives
conversations from **Gemini** (gemini.google.com), **Google AI Studio**
(aistudio.google.com), and **Google Jules** (jules.google.com) into one
searchable, exportable personal library. No accounts, no telemetry, no
servers — your archive never leaves your device.

## Open services and capture conversations

The home screen's **Open website** action opens Gemini, AI Studio, or Jules in
your regular browser. This is now the recommended path because Google can block
sign-in or fail to load chat content inside embedded Android WebViews. The app
does not try to bypass those restrictions.

Each platform card also has two alternatives:

- **In-app view:** experimental WebView with a **Save conversation** button.
  It can work, but Google may refuse sign-in or parts of the site may not load.
- **Capture guide:** platform-specific instructions for the companion userscript
  and import workflow.

For reliable use, open the official site in a compatible browser, capture or
export the conversation using a supported exporter/userscript, then import the
result into Conversation Archive. The archive and its import/export functions
work locally and do not require API keys.

The app never reads password fields or copies cookies/tokens. Its network
permission is used to load official pages in the optional in-app browser and
to handle downloads the page itself offers.

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

- **Platform launchers:** open Gemini, AI Studio, and Jules in the regular
  browser by default, with optional in-app WebView and a direct capture guide.
  The WebView extractor can save rendered page content when the site permits it.
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
- **Privacy:** no accounts, no analytics, no network SDKs. The INTERNET
  permission is used for the optional in-app browser and site-offered downloads.
  Credentials are typed into official site pages; the app never reads or
  transmits them.
- **Theme:** Material3, light + dark (system default, manual override),
  dynamic color off by default.

## Honest limitations

- **Embedded WebView support is not guaranteed.** Google can block sign-in or
  chat loading inside an in-app browser. Use the home screen's **Open website**
  action to access the service in your regular browser. Conversation capture
  then depends on the site's export features or a compatible userscript/
  exporter; import the resulting file into the app.
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
