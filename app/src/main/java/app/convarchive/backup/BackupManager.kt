package app.convarchive.backup

import app.convarchive.export.Filenames
import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson
import app.convarchive.model.ImportResult
import app.convarchive.model.parsers.CanonicalParser
import app.convarchive.util.Dates
import app.convarchive.util.Io
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Backup/restore via a zip of canonical JSON files + manifest (decision D3).
 * Restoring re-imports through the normal import pipeline, so backups survive
 * app and schema version changes. Pure JVM, no android.* imports.
 *
 * Layout:
 *   manifest.json
 *   conversations/<sanitized-title>_<index>.json   (canonical schema v1 each)
 */
object BackupManager {

    const val MANIFEST_NAME = "manifest.json"
    const val CONVERSATIONS_DIR = "conversations/"
    const val APP_ID = "convarchive-app"
    const val BACKUP_VERSION = 1

    /** Build a backup zip from conversations. */
    fun createBackup(convs: List<CanonicalConversation>): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        val manifest = JsonObject(
            mapOf(
                "app" to JsonPrimitive(APP_ID),
                "version" to JsonPrimitive(BACKUP_VERSION),
                "exportedAt" to JsonPrimitive(Dates.nowIso()),
                "count" to JsonPrimitive(convs.size),
            ),
        )
        val manifestBytes = CanonicalJson.json.encodeToString(JsonObject.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)
        entries.add(MANIFEST_NAME to manifestBytes)

        val used = mutableSetOf<String>()
        convs.forEachIndexed { index, conv ->
            var name = "${Filenames.sanitize(conv.title)}_${index + 1}.json"
            var n = 2
            while (!used.add(name)) {
                name = "${Filenames.sanitize(conv.title)}_${index + 1}_$n.json"
                n++
            }
            entries.add(Pair(CONVERSATIONS_DIR + name, CanonicalJson.encode(conv).toByteArray(Charsets.UTF_8)))
        }
        return Io.writeZip(entries)
    }

    /**
     * Read a backup zip. Each JSON file under the conversations/ directory is
     * parsed with [CanonicalParser]; malformed entries become
     * `ImportResult(ok=false)` with the exact error so the caller can report
     * imported / failed counts. The caller inserts results via
     * [app.convarchive.data.Repository], which handles duplicate detection.
     */
    fun parseBackup(bytes: ByteArray): List<ImportResult> {
        val results = mutableListOf<ImportResult>()
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return listOf(
                ImportResult(
                    ok = false,
                    errors = listOf("not a zip archive (missing PK signature)"),
                    detectedExporter = "convarchive-backup",
                ),
            )
        }
        val entries = try {
            Io.readZipEntries(bytes)
        } catch (e: Exception) {
            return listOf(
                ImportResult(
                    ok = false,
                    errors = listOf("backup is not a readable zip: ${e.message}"),
                    detectedExporter = "convarchive-backup",
                ),
            )
        }
        if (entries.none { it.first == MANIFEST_NAME }) {
            results += ImportResult(
                ok = false,
                errors = listOf("backup is missing $MANIFEST_NAME"),
                detectedExporter = "convarchive-backup",
            )
        } else {
            // Validate the manifest but keep going regardless.
            val manifestBytes = entries.first { it.first == MANIFEST_NAME }.second
            runCatching {
                val m = CanonicalJson.json.parseToJsonElement(
                    manifestBytes.toString(Charsets.UTF_8),
                ).jsonObject
                val app = m["app"]?.jsonPrimitive?.content
                if (app != null && app != APP_ID) {
                    results += ImportResult(
                        ok = false,
                        errors = listOf("backup manifest is for \"$app\", not $APP_ID"),
                        detectedExporter = "convarchive-backup",
                    )
                }
            }.onFailure { e ->
                results += ImportResult(
                    ok = false,
                    errors = listOf("backup manifest is malformed: ${e.message}"),
                    detectedExporter = "convarchive-backup",
                )
            }
        }

        val convEntries = entries
            .filter { it.first.startsWith(CONVERSATIONS_DIR) && it.first.endsWith(".json") }
            .sortedBy { it.first }
        if (convEntries.isEmpty()) {
            results += ImportResult(
                ok = false,
                errors = listOf("backup contains no conversation files under $CONVERSATIONS_DIR"),
                detectedExporter = "convarchive-backup",
            )
        }
        for ((name, data) in convEntries) {
            val text = try {
                data.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                results += ImportResult(
                    ok = false,
                    errors = listOf("$name: not valid UTF-8 (${e.message})"),
                    detectedExporter = "convarchive-backup",
                )
                continue
            }
            val r = CanonicalParser.parse(text, name.substringAfterLast('/'))
            results += if (r.ok) r else r.copy(
                errors = r.errors.map { "$name: $it" },
            )
        }
        return results
    }
}
