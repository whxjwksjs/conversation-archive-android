# Conversation Archive — Architecture

App name: **Conversation Archive** · Package: `app.convarchive`
`minSdk 26` (Android 8.0) · `targetSdk 34` · `compileSdk 34`
Language: Kotlin · UI: Jetpack Compose (BOM) · DB: Room (+KSP) · JSON: kotlinx-serialization-json
Navigation: navigation-compose. No analytics, no network SDKs, no accounts.

## 1. Canonical JSON schema v1

One schema for all three platforms plus generic imports. Imported third-party
exports are CONVERTED to this schema on import; the untouched source file is
kept verbatim in `originalExport` and inside the DB row. Unknown JSON fields
are preserved, never dropped.

```jsonc
{
  "schemaVersion": 1,
  "platform": "gemini | aistudio | jules | other",
  "title": "string",
  "sourceId": "string | null",      // e.g. Gemini conversation id, Jules task id
  "sourceUrl": "string | null",
  "createdAt": "ISO-8601 | null",
  "updatedAt": "ISO-8601 | null",
  "exportedAt": "ISO-8601",         // when this JSON was produced
  "exporter": "string",             // e.g. "convarchive-userscript-aistudio/1.0"
  "tags": ["string"],
  "favorite": false,
  "notes": "string",
  "messages": [
    {
      "role": "user | assistant | system | tool | event",
      // "event" is for Jules activity-log entries, status changes, errors.
      "text": "plain or markdown text",
      "markdown": "original markdown when known, else same as text",
      "createdAt": "ISO-8601 | null",
      "model": "string | null",     // e.g. "gemini-2.5-pro"
      "codeBlocks": [ { "language": "kotlin | null", "code": "..." } ],
      "attachments": [ { "name": "...", "mimeType": "...", "sizeBytes": 0,
                          "note": "content not embedded, metadata only" } ],
      "raw": { }                    // platform-specific extras, verbatim
    }
  ],
  "metadata": { },                   // platform extras: model, systemInstruction,
                                    // temperature, jules repo/branch, etc.
  "originalExport": { } | "string" | null   // untouched source, verbatim
}
```

Validation rules (see `model/Canonical.kt`): `schemaVersion==1`, non-blank
`platform` (unknown → `"other"` with warning), `messages` is a list (missing →
empty + warning). Everything else is lenient: unknown fields kept in a
`JsonObject` catch-all, surfaced in the raw-JSON inspector.

Duplicate detection: `SHA-256(platform + "\n" + title + "\n" + for each message:
role + "\n" + text)` over normalized (trimmed, NFC) text. Stored per row.

## 2. Module layout (single `:app` module)

```
app/src/main/java/app/convarchive/
  MainActivity.kt                 // entry, intent filters, theme
  ConvArchiveApp.kt               // Application, Room init
  data/
    ConversationEntity.kt         // Room entity (indexed cols + rawJson TEXT)
    ConversationDao.kt
    AppDatabase.kt
    Repository.kt                 // search/filter/sort, CRUD, duplicate check
  model/
    Canonical.kt                  // @Serializable data classes + validation
    ImportResult.kt               // success/errors/warnings/duplicate
    parsers/
      CanonicalParser.kt          // schema v1 (+ tolerant v0)
      VoyagerParser.kt            // nagi-ovo/voyager Gemini export format
      AiStudioExporterParser.kt   // rmtd418/ai-studio-conversation-exporter format
      AiStudioDumpParser.kt       // Tokisaki-Galaxy/aistudio-dump-script format
      MarkdownImporter.kt         // .md/.markdown → heuristic message split
      TextImporter.kt             // .txt → single doc / heuristic split
      HtmlImporter.kt             // .html → strip tags, keep structure
      PdfImporter.kt              // minimal embedded text extractor (best effort)
      ParserRegistry.kt           // detect platform/exporter from JSON shape
  export/
    JsonExporter.kt               // canonical JSON (+ original preserved)
    MarkdownExporter.kt           // headings, timestamps, fenced code
    TextExporter.kt               // clean plain text
    HtmlExporter.kt               // standalone styled HTML
    PdfWriter.kt                  // minimal pure-Kotlin/PDF-1.4 writer
    PdfExporter.kt                // text layout: wrap, pages, code font
    ZipExporter.kt                // multi-conversation .zip
    Filenames.kt                  // e.g. AIStudio_Conversation_2026-10-12.json
  backup/
    BackupManager.kt              // SAF zip: manifest.json + one canonical JSON per
                                 // conversation (+ raw originals). Restore re-imports.
  ui/
    theme/Theme.kt                // light/dark (system default + manual)
    Nav.kt                        // routes (v1.1: + browser/{platform})
    browser/
      BrowserConfig.kt            // per-platform URLs (+ desktop-Chrome UA fallback string)
      JsBridgeResult.kt           // evaluateJavascript result parsing (pure JVM)
      DownloadHelper.kt           // DownloadListener: google.com-only, cookie-authed
    screens/
      HomeScreen.kt               // sections: Gemini, AI Studio, Jules (v1.1:
                                  //   open in-app browsers), Saved Conversations,
                                  //   Import, Export & Backup, Settings
      BrowserScreen.kt            // v1.1: in-app WebView + one-tap save + downloads
      PlatformGuideScreen.kt      // per platform: what capture can/can't do,
                                  // "Open site in browser", userscript install steps
                                  // (v1.1: linked from each browser screen)
      LibraryScreen.kt            // search, filter platform/date, sort,
                                  // multi-select, actions
      DetailScreen.kt             // transcript view, tags/notes/favorite/rename
      RawJsonScreen.kt            // scrollable raw JSON inspector
      ImportScreen.kt             // SAF picker, results, error details
      ExportScreen.kt             // single/multi export, format pick, SAF save
      BackupScreen.kt
      SettingsScreen.kt           // theme, default export format, about/privacy
  util/
    Hashing.kt  Dates.kt  Io.kt   // small helpers
  assets/
    extractor.js                  // v1.1: injectable conversation extractor
                                  // (gemini/aistudio/jules + bounded auto-scroll)
    userscript-guide.md           // in-app copy of install instructions
```

`userscripts/` (repo root, NOT in APK except the guide): three `.user.js`
companions, `@match`-scoped to their site only.

## 3. Data layer

`ConversationEntity`:
- `id: Long` (auto), `platform: String` (index), `title: String` (index),
  `sourceId: String?`, `sourceUrl: String?`, `createdAt: Long?` (epoch ms, index),
  `updatedAt: Long?`, `exportedAt: Long`, `exporter: String`, `tags: String`
  (comma-joined, app splits), `favorite: Boolean` (index), `notes: String`,
  `contentHash: String` (unique index — duplicate detection),
  `messageCount: Int`, `rawJson: String` (full canonical JSON, source of truth),
  `importedAt: Long`.
- Rendering/export always re-parse `rawJson` → `CanonicalConversation`; indexed
  columns are query helpers only. Single source of truth, no drift.
- Search: `LIKE %q%` over `title` and `rawJson` (documented: fast enough for a
  personal archive; no FTS dependency).
- All DB access via `Repository` on `Dispatchers.IO`.

## 4. Capture design

v1.1 adds in-app browsers (see §11); the userscript workflow below remains
as the fallback.

### 4a. In-app browsers with one-tap save (v1.1)

Each platform has an in-app browser screen (`ui/screens/BrowserScreen.kt`,
route `browser/{platform}`) hosting an Android WebView:

- The user logs in with their Google account on Google's own pages inside
  the WebView. Login persists via the WebView cookie store:
  `CookieManager.setAcceptCookie(true)`, third-party cookies accepted on the
  WebView, `CookieManager.flush()` on every app pause.
- The WebView uses a configurable browser identity (v1.2.0,
  `ui/browser/UserAgentMode.kt`): STANDARD (default) presents the genuine
  mobile-Chrome UA with only the `; wv` WebView marker stripped —
  the community-standard fix for Google's embedded-WebView sign-in block;
  DESKTOP (the old v1.1 desktop-Chrome string) and SYSTEM (untouched UA)
  remain as fallbacks, switchable in Settings → In-app browser. The mode
  is applied at WebView creation and re-applied on resume if changed.
- "Save conversation" (FAB) injects `assets/extractor.js` via
  `evaluateJavascript`: a bounded auto-scroll loads virtualized history
  (progress shown; 60-pass cap, ~65s timeout, cancellable), then the
  platform extractor returns canonical JSON v1 as a string. Kotlin parses it
  with `JsBridgeResult` → `CanonicalParser` → `Repository.insert` (normal
  duplicate detection). Outcomes: saved / duplicate skipped / nothing found
  with a reason / failed with a reason.
- `DownloadListener` intercepts page-offered file downloads: restricted to
  `https://*.google.com` URLs, fetched with the WebView's session cookies
  into app-private cache, then offered for one-tap import through the
  normal import pipeline.
- Back button navigates WebView history; toolbar offers reload, "open in
  external browser", and the capture guide.

SECURITY (non-negotiable): credentials are typed by the user into Google's
own pages only. The app never reads password fields, never copies cookies
or tokens anywhere, and makes no network calls of its own except loading
the three official sites (plus same-site downloads the page offers). The
extractor reads only conversation DOM the user can already see.

Caveat: Google may refuse or challenge embedded-WebView logins at any time.
The browser screen detects Google's "Couldn't sign you in" block page
(title heuristic in `onPageFinished`) and shows an in-app dialog: Try
another mode (cycles STANDARD → DESKTOP → SYSTEM and reloads), open in
external browser, or dismiss. Fallback is always the v1.0
external-browser + import workflow (§4b).

### 4b. Userscript workflow (v1.0, kept as fallback)

Direct in-app DOM scraping is impossible without the user's login session, and
the app MUST NOT harvest passwords, cookies, or tokens, or bypass auth.
Per-platform workflow (identical shape, platform-specific guide text):

1. In-app button **opens the official site in the user's external browser**
   (explicit label: "Opening the site does not capture anything by itself").
2. User installs the companion userscript (Tampermonkey/Violentmonkey) in a
   compatible Android browser (see RESEARCH.md for the 2026 browser list). Each
   script is `@match`-scoped to its own site, adds an in-page
   "Export to Archive (JSON)" button, extracts the accessible conversation
   content, and downloads canonical-schema JSON.
3. User imports the JSON: Android share sheet → "Conversation Archive"
   (`ACTION_SEND` intent filter), or in-app file picker (`ACTION_OPEN_DOCUMENT`).

Fallbacks, in order: (a) userscript export; (b) import from third-party
exporters (Voyager etc. — `ParserRegistry` detects them); (c) copy/paste or
save the page as `.md`/`.txt`/`.html` and import best-effort;
(d) Google Takeout if it covers the data (see RESEARCH.md).

Userscript limits (documented in-app and in each file header): only
*rendered* content can be extracted (virtual-scroll windows, collapsed
branches, login walls are out of reach); selectors are DOM-dependent and may
break when Google changes markup — each script logs what it found
(`messages found: N`) so failures are visible, not silent.

Jules: treated as a task/session archive. Fields: task title/id, prompt,
status, timestamps, activity entries (`role:"event"`), plans, outputs,
repo/branch, diffs where the page exposes them. Anything not exposed is listed
as unavailable in the guide — never invented.

## 5. Export details

- JSON: pretty-printed canonical schema v1, `originalExport` preserved verbatim.
- Markdown: `# Title`, metadata block, `##` per message with role + timestamp,
  fenced code blocks with language tags.
- TXT: clean transcript, separators between messages.
- HTML: single self-contained file, inline CSS, `<pre><code>` blocks, escaped.
- PDF: **real PDF** via hand-rolled `PdfWriter` (PDF 1.4, Base-14
  Helvetica/Courier, FlateDecode content streams, page breaks, code in
  Courier). Decision recorded in DECISIONS.md: chosen over
  `android.graphics.pdf.PdfDocument` so the writer is unit-testable on the JVM
  (`%PDF` header, xref table, page count assertions) and verifiable with `pypdf`
  on the build machine. Unicode outside WinAnsi is replaced with `?` and this
  is documented (all other formats preserve Unicode fully).
- Multi-export: `.zip` with one file per conversation (format chosen once).
- Filenames: `{Platform}_{slug(title)}_{yyyy-MM-dd}[_{n}].{ext}`,
  e.g. `AIStudio_Conversation_2026-10-12.json`. Sanitized, ASCII-safe.
- SAF: `ACTION_CREATE_DOCUMENT` for single files, share sheet via FileProvider
  for quick share. Large conversations stream; message counts are asserted in
  tests (no silent truncation — exporters write from the full message list).

## 6. Import details

- `.json`: `ParserRegistry` sniffs shape → canonical / Voyager / AI-Studio
  exporter / AI-Studio dump-script / Jules-ish generic → `CanonicalConversation`
  with `originalExport` = the untouched input. Unknown fields preserved.
- `.md`/`.markdown`: heuristic split on headings (`##`, `**User:**`-style
  markers, `---` rules); unparseable → single `user`-authored document? No —
  single message with `role:"event"`, `notes` explaining heuristic. Honest.
- `.txt`: same heuristic, simpler.
- `.html`: strip scripts/styles, extract text with block structure; keep
  `<pre>` as code blocks.
- `.pdf`: embedded minimal extractor (uncompress Flate streams, `Tj`/`TJ`/`'`
  text-showing ops, WinAnsi mapping). Reconstructing message structure is
  best-effort; the importer says so in warnings. Encrypted/unsupported PDFs →
  clear error, original kept for manual inspection.
- Every import returns `ImportResult` with errors/warnings listed; the Import
  screen shows them verbatim. Duplicates (hash match) are reported and skipped
  unless the user taps "Import anyway" (then stored with a new id — hash stays
  for future detection display).
- Invalid files: exact failure reason (e.g. "JSON parse error at line 12:
  Unexpected token"), never a silent drop. A "keep original anyway" option
  stores the file bytes as an `other`-platform record with the error in notes.

## 7. Privacy & data lifetime

- Local-first. No accounts, no telemetry, no analytics, no crash reporting.
  Since v1.1 the app holds the INTERNET permission, used ONLY by the in-app
  browsers to load the three official sites (gemini.google.com,
  aistudio.google.com, jules.google.com) and Google's login pages, plus
  same-site file downloads the page itself offers. No other network calls.
- No passwords, cookies, or tokens are ever requested by the app, read from
  form fields, copied outside the WebView's own cookie jar, or transmitted
  anywhere. The extractor reads only conversation DOM the user can already
  see.
- Delete requires confirmation; multi-delete too.
- **Uninstall or Clear data deletes the archive** (and saved logins — you
  sign in again after reinstalling). The app says this in Settings →
  Storage and before backup. Backup/restore via SAF zip is the escape hatch;
  backups live wherever the user saves them.

## 8. UI/UX

- Home sections exactly: Gemini, Google AI Studio, Google Jules
  (v1.1: these open the in-app browsers; the capture guides remain reachable
  from each browser screen), Saved Conversations, Import Conversations,
  Export and Backup, Settings.
- Material3, dynamic color off by default (predictable), light + dark
  (system + manual override in Settings).
- Every screen reachable from Home must be functional. Loading indicators on
  import/export/backup; errors shown with the underlying reason and a retry.
- Detail screen renders markdown-lite (headings, bold, code fences via
  annotated strings — no heavy markdown lib).

## 9. Build & test

- Gradle wrapper, AGP 8.5.x, Kotlin 1.9.24+, KSP for Room, Compose BOM.
- `splits.abi`: `armeabi-v7a`, `arm64-v8a`, `universalApk true`. Debug signing
  (debug key) — APKs are DEBUG builds, stated everywhere.
- Unit tests (`./gradlew testDebugUnitTest`): schema validation, every parser
  vs fixtures (incl. real exporter samples), every exporter validity, unicode
  round-trips, code-block preservation, duplicate detection, filenames, 2000-
  message integrity, invalid-file paths, backup/restore round-trip, PdfWriter
  `%PDF`/xref/page-count checks. No emulator on the build VM → no
  instrumentation tests (stated in TEST_REPORT.md).

## 10. Key decisions (see DECISIONS.md for full log)

- D1: Hand-rolled `PdfWriter` over `PdfDocument` (testability).
- D2: `rawJson` TEXT column as source of truth; indexed scalar columns for query.
- D3: Backup = zip of canonical JSONs + manifest (not Room file copy) → robust
  across app versions.
- D4: kotlinx-serialization-json for all JSON (schema discipline + unknown-field
  preservation via JsonObject).
- D5: No markdown-rendering library; small built-in renderer.
- D6: KSP over kapt for Room.
