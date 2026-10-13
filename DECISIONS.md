# Decisions log

- D0 (2026-10-12, build environment): On this VM the Gradle daemon's TCP
  protocol exchange fails when the client JVM uses IPv6-mapped IPv4
  (`::ffff:127.0.0.1`) — the client connects but the daemon never receives a
  valid message ("Could not receive a message from the daemon"). Pure-IPv4
  sockets work fine. Workaround: ALWAYS run Gradle with
  `GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"` (forces the client to IPv4).
  Also note `-Djava.net.preferIPv4Stack=true` placed in
  `org.gradle.jvmargs` is silently dropped by Gradle's daemon-parameter
  filtering, so it must go in GRADLE_OPTS (client side), not gradle.properties.
  JAVA_HOME=~/workspace/.jdks/jdk-17.0.20.1+1,
  GRADLE_USER_HOME=~/workspace/.gradle,
  ANDROID_HOME (or sdk.dir in local.properties)=~/workspace/.android-sdk.
- D0b (2026-10-12, dependency downloads): Java's TLS stack cannot complete a
  handshake through this sandbox's egress proxy over IPv6 (TLS 1.3 ClientHello
  is blackholed; plain-IPv6 proxy connections also break Gradle's HttpClient).
  curl/OpenSSL works. Workaround that makes Gradle dependency downloads work:
  JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true
  -Djdk.tls.client.protocols=TLSv1.2" — picked up by every JVM (client AND
  daemon), bypassing Gradle's filtering of these flags in org.gradle.jvmargs
  and systemProp.*. Full working env for any Gradle invocation on this VM:
  JAVA_HOME=~/workspace/.jdks/jdk-17.0.20.1+1
  GRADLE_USER_HOME=~/workspace/.gradle
  GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
  JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djdk.tls.client.protocols=TLSv1.2"
  ANDROID_HOME=~/workspace/.android-sdk
- D0c (2026-10-12): The sandbox egress proxy does TLS interception for some
  hosts (e.g. dl.google.com presents a cert issued by "Hatch Sandbox Egress
  CA"), while others pass through with real certs (repo.maven.apache.org).
  Java's cacerts did not trust the sandbox CA -> PKIX failures. Fix: imported
  /etc/ssl/certs/hatch-egress-ca.pem into the Temurin JDK's cacerts
  (~/workspace/.jdks/jdk-17.0.20.1+1/lib/security/cacerts, alias
  hatch-egress-ca). This is the environment's own CA, not a user secret.
- D0d (2026-10-12): plugins.gradle.org/m2 303-redirects the
  com.android.application marker to Maven Central where it 404s, so the Gradle
  Plugin Portal is omitted from pluginManagement; settings.gradle.kts maps
  plugin IDs to modules via resolutionStrategy.eachPlugin { useModule(...) }.

- D1 (2026-10-12): PDF export uses a hand-rolled minimal PDF 1.4 writer
  (`export/PdfWriter.kt`) instead of `android.graphics.pdf.PdfDocument`.
  Reason: the writer is pure Kotlin/JVM, so unit tests can assert `%PDF`
  header, xref table, and page count, and the build VM can validate output
  with `pypdf`. Trade-off: Base-14 fonts only (WinAnsi); non-WinAnsi Unicode
  is replaced with `?` in PDF output and this is documented. All other export
  formats preserve Unicode fully.
- D2 (2026-10-12): Room entity stores the full canonical JSON in a `rawJson`
  TEXT column as the single source of truth; scalar columns (platform, title,
  timestamps, hash, favorite) are indexed query helpers. Rendering and export
  always re-parse `rawJson`, so schema migrations only touch parsing code.
- D3 (2026-10-12): Backup/restore is a zip of canonical JSON files + manifest,
  not a Room database file copy. Restoring re-imports through the normal
  import pipeline, so backups survive app/schema version changes.
- D4 (2026-10-12): kotlinx-serialization-json for all JSON work (schema
  discipline, unknown-field preservation via JsonObject catch-alls).
- D5 (2026-10-12): No third-party Markdown rendering library; a small built-in
  annotated-string renderer covers headings/bold/code fences. Keeps APK small.
- D6 (2026-10-12): KSP (not kapt) for Room annotation processing.
- D7 (2026-10-12): Capture workflow is external-browser + companion userscript
  + share-sheet/file import. No in-app WebView scraping, no credential or
  cookie handling of any kind. Each userscript is @match-scoped to its own
  site and labeled experimental with visible extraction counts.
- D8 (2026-10-12): Duplicate detection via SHA-256 content hash with a unique
  DB index; duplicates are reported and skipped, with an explicit
  "import anyway" path.
- D9 (2026-10-12): APKs are DEBUG builds signed with the Android debug key.
  Stated in README, in-app About screen, and APK filenames.
