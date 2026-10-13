# Conversation Archive — Test Report

**Build:** v1.2.0 (versionCode 3). Source complete 2026-10-13; **APKs NOT
built in this session** — the Gradle daemon TCP protocol is blocked by the
runtime network sandbox for this agent (see DECISIONS.md D15). v1.1.0
(versionCode 2) DEBUG-signed APKs were built 2026-10-13 on a Linux VM
(Temurin JDK 17, Android SDK platform-34 + build-tools 34.0.0, Gradle 8.7
via the full install at ~/workspace/.gradle-dist/gradle-8.7 — see
DECISIONS.md D13 — AGP 8.5.2, Kotlin 1.9.24, kotlinx-serialization-json
1.6.3).
**Test command:** `./gradlew :app:testDebugUnitTest` (with the environment
workarounds documented in DECISIONS.md D0–D0d, D13). **Could not run in
this session** (D15); v1.2.0's new pure-Kotlin logic was instead compiled
and tested directly with kotlinc + JUnit from the Gradle cache (see below).

## Unit tests — v1.2.0 new suite: 15/15 PASS (standalone kotlinc+JUnit run)

| Suite | Tests | Result | How run |
|---|---|---|---|
| UserAgentModeTest (new in v1.2) | 15 | PASS | kotlinc + JUnit 4.13.2 directly (Gradle blocked, D15) |

Covers: `; wv` stripping (real WebView UA → plain Chrome UA; no-token and
empty UAs unchanged), STANDARD mode keeps genuine UA minus token,
DESKTOP mode returns the desktop string, SYSTEM mode untouched, mode
cycling STANDARD→DESKTOP→SYSTEM→STANDARD, block-page detection (the exact
on-device "Couldn't sign you in" title, case-insensitivity, "may not be
secure" variant, accounts.google.com error-title guard, null title and
normal titles not flagged).

## Unit tests — v1.1.0: 98/98 PASS, 0 failures, 0 errors, 0 skipped

| Suite | Tests | Result |
|---|---|---|
| BackupRestoreTest | 7 | PASS |
| CanonicalSchemaTest | 12 | PASS |
| CodeBlockPreservationTest | 3 | PASS |
| DuplicateDetectionTest | 3 | PASS |
| ExporterTests | 9 | PASS |
| FilenamesTest | 5 | PASS |
| JsBridgeResultTest (new in v1.1) | 14 | PASS |
| LargeConversationTest | 3 | PASS |
| ParserTests | 14 | PASS |
| PdfImporterTest | 7 | PASS |
| PdfWriterTest | 8 | PASS |
| RepositoryTest | 8 | PASS |
| UnicodeRoundTripTest | 5 | PASS |

v1.0.0 had 84/84 passing; v1.1.0 adds the 14-test JsBridgeResultTest suite
and keeps all 84 green (no regressions). **The full 98-test suite plus the
15 new v1.2.0 tests (113 total) still need one `./gradlew
:app:testDebugUnitTest` run wherever the network policy allows it** — the
v1.2.0 UI changes (BrowserScreen dialog, Settings toggle) are
compile-unverified in this session (reviewed by hand; the new logic they
depend on is covered by the 15 passing tests).

Coverage of the required test list:
- ✅ App data model: canonical schema validation (lenient decode, unknown-field
  preservation, validation warnings)
- ✅ JSON import against fixtures for every supported shape: canonical v1,
  Voyager, rmtd418 ai-studio-conversation-exporter (INFERRED schema, tolerant),
  aistudio-dump-script v5.0 (code-verified), caffeinegmt multi-conversation,
  Google Takeout "My Activity", NDJSON
- ✅ Markdown / TXT / HTML import (heuristic message splitting; honest
  single-document fallback with warning when no structure found)
- ✅ Every exporter output validity: JSON re-parses with identical message
  count; Markdown contains fenced code blocks; HTML is a complete document;
  TXT non-empty; PDF bytes start with `%PDF-1.4`, valid xref table, correct
  `/Count`
- ✅ Unicode / special-char round trips (emoji, CJK, RTL, curly quotes, em
  dash) through JSON/Markdown/HTML/TXT export+import
- ✅ Code-block preservation (language tags + content)
- ✅ Duplicate detection (SHA-256 content hash; insert-twice → `wasDuplicate`;
  forced re-import gets its own row with `#dupN`-suffixed hash)
- ✅ Filename generation (`AIStudio_Conversation_2026-10-12.json` pattern,
  sanitization)
- ✅ Large conversation: 2000 messages through JSON/Markdown/PDF export —
  message counts asserted, no truncation
- ✅ Invalid-file error paths: malformed JSON (exact parse error), non-zip
  backup bytes (`not a zip archive (missing PK signature)`), encrypted/
  unsupported PDFs (clear error, original retained)
- ✅ Backup/restore round trip (tags, favorites, notes preserved)
- ✅ Repository logic (search/filter/sort/delete/updateMeta) via
  FakeConversationDao
- ✅ PDF validated end-to-end with `pypdf` (outside unit tests): 2-page sample
  PDF parsed, text extracted correctly incl. WinAnsi special chars (éèê,
  “quotes”, —); non-WinAnsi chars (中文, 😀) correctly replaced with `?` as
  documented

## v1.1 additions to coverage

- ✅ JS-bridge result parsing (`JsBridgeResultTest`, 14 tests): valid
  canonical payload round-trips through the evaluateJavascript string
  encoding; `null`/empty bridge values → Empty; `{"__error"}` →
  JsError; `{"__empty"}` → Empty with reason; malformed bridge values →
  Malformed; extractor-shaped payload parses via `CanonicalParser` with
  warnings only; unknown top-level fields land in `metadata`, unknown
  message fields land in `raw`; empty message list is ok-with-warning.
- ✅ Download allow-list (`DownloadHelper`): google.com https URLs accepted;
  non-google hosts, plain http, and malformed URLs rejected; file names
  from Content-Disposition/URL sanitized.
- ✅ `assets/extractor.js` syntax-checked by loading it in the unit-test
  JVM? No — it is plain browser JS and is NOT executed in tests. Its logic
  mirrors the reviewed userscripts; the Kotlin side of the protocol is what
  the tests cover. On-device behavior of the WebView screens is NOT tested
  (see below).

## Real-bug fixes found by the test run (not test-only patches)

1. `**User:**` regex capture-group indices (group 1 is the `**` delimiter).
2. `PdfImporter` used raw-deflate `Inflater(true)`; PDF FlateDecode and the
   app's `PdfWriter` use zlib-wrapped streams.
3. `Repository.insertAnyway` collided with the unique contentHash index —
   resolved with `#dupN`-suffixed hash (documented in code).
4. A KDoc comment containing `` `conversations/*.json` `` terminated a block
   comment early (`*/` sequence) — reworded.
5. Build config: Kotlin 1.9.24 + kotlinx-serialization-json 1.7.3 are
   incompatible (plugin requires Kotlin 2.0+); pinned serialization to 1.6.3.
6. AGP 8.5 does not generate `BuildConfig` unless `buildFeatures.buildConfig`
   is enabled — enabled explicitly.
7. `combinedClickable` needs `@OptIn(ExperimentalFoundationApi::class)`.

## NOT performed

- **Instrumentation / on-device tests:** no Android emulator or physical
  device is available on the build VM. The following were verified by code
  review + unit tests only, not by running the app: activity launch, Compose
  navigation, Room database on-device, share-sheet/ACTION_VIEW intent handling,
  Storage Access Framework pickers, the WebView browser screens (login flow,
  cookie persistence, `evaluateJavascript` capture, DownloadListener), the
  injected `extractor.js` against the live sites, and the companion
  userscripts against the live sites (labeled EXPERIMENTAL).
- **Userscript DOM extraction:** verified by code review against RESEARCH.md
  findings only; Google changes markup frequently, so selectors may need
  updates (each script logs its found-message count to make misses visible).

## APK verification (aapt / apksigner)

### v1.1.0 (2026-10-13)

- `convarchive-1.1.0-debug-arm64-v8a.apk` (15.8 MB) — apksigner: VERIFIED
- `convarchive-1.1.0-debug-armeabi-v7a.apk` (15.8 MB) — apksigner: VERIFIED
- `convarchive-1.1.0-debug-universal.apk` (15.8 MB) — apksigner: VERIFIED
- All: `package='app.convarchive.debug' versionCode='2' versionName='1.1.0'`,
  `sdkVersion:'26'`, `targetSdkVersion:'34'`; launchable activity
  `app.convarchive.MainActivity`; label "Conversation Archive";
  `android.permission.INTERNET` declared (new in v1.1, scoped to the in-app
  browsers); signed with the Android debug key (`CN=Android Debug`).
- The app contains no native `.so` libraries (pure Kotlin/JVM + Jetpack), so
  the per-ABI APKs are byte-identical; the splits are still emitted per the
  build config and each installs on its ABI. The universal APK runs on any
  arm64 or arm32 device.

### v1.0.0 (2026-10-12)

- `convarchive-1.0.0-debug-arm64-v8a.apk` (15.7 MB) — apksigner: VERIFIED
- `convarchive-1.0.0-debug-armeabi-v7a.apk` (15.7 MB) — apksigner: VERIFIED
- `convarchive-1.0.0-debug-universal.apk` (15.7 MB) — apksigner: VERIFIED
- All: `package='app.convarchive.debug' versionCode='1' versionName='1.0.0'`,
  `sdkVersion:'26'`, `targetSdkVersion:'34'`; launchable activity
  `app.convarchive.MainActivity`; label "Conversation Archive"; signed with
  the Android debug key (`CN=Android Debug`).
- The app contains no native `.so` libraries (pure Kotlin/JVM + Jetpack), so
  the per-ABI APKs are byte-identical; the splits are still emitted per the
  build config and each installs on its ABI. The universal APK runs on any
  arm64 or arm32 device.
