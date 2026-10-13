package app.convarchive.model.parsers

import app.convarchive.model.CanonicalMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Heuristic transcript splitter shared by the Markdown and Text importers.
 *
 * Splits on, in order: horizontal rules (`---`), ATX headings (`## ...`),
 * bold role markers (`**User:**`), and plain role prefixes (`User:`).
 * Lines inside fenced code blocks never trigger a split.
 *
 * Role assignment: explicit markers map to user/assistant/system; sections
 * opened by a bare `---` or a non-role heading alternate user/assistant
 * starting with user. This is a heuristic and is always reported in warnings.
 */
internal object TranscriptSplit {

    data class SplitResult(
        val messages: List<CanonicalMessage>,
        val titleHint: String?,
        val foundStructure: Boolean,
        val notes: List<String>,
    )

    fun split(text: String, markdownAware: Boolean, fileName: String): SplitResult {
        val lines = text.lines()
        val sections = mutableListOf<Section>()
        var current = Section(roleHint = null, heading = null)
        var inFence = false
        var foundStructure = false
        var titleHint: String? = null
        val notes = mutableListOf<String>()

        fun close() {
            if (current.lines.any { it.isNotBlank() } || current.heading != null) {
                sections += current
            }
            current = Section(roleHint = null, heading = null)
        }

        for (rawLine in lines) {
            val line = rawLine
            val trimmed = line.trim()

            // Fence tracking (markdown-aware only, but harmless either way).
            if (trimmed.startsWith("```")) {
                if (!inFence) foundStructure = true // code blocks count as structure
                inFence = !inFence
                current.lines += line
                continue
            }
            if (inFence) {
                current.lines += line
                continue
            }

            // Horizontal rule -> section break.
            if (HR.matches(trimmed)) {
                foundStructure = true
                close()
                continue
            }

            if (markdownAware) {
                val h = HEADING.matchEntire(trimmed)
                if (h != null) {
                    foundStructure = true
                    val headingText = h.groupValues[2].trim()
                    if (titleHint == null && sections.isEmpty() && current.lines.all { it.isBlank() }) {
                        titleHint = headingText
                    }
                    close()
                    current = Section(roleHint = roleFromName(headingText), heading = headingText)
                    continue
                }
                val b = BOLD_ROLE.matchEntire(trimmed)
                if (b != null) {
                    // group 1 = the ** or __ delimiter, group 2 = name, group 3 = rest of line
                    val name = b.groupValues[2].trim().trimEnd(':')
                    val role = roleFromName(name)
                    if (role != null) {
                        foundStructure = true
                        close()
                        current = Section(roleHint = role, heading = null)
                        val rest = b.groupValues[3]
                        if (rest.isNotBlank()) current.lines += rest
                        continue
                    }
                }
            }

            val p = PLAIN_ROLE.matchEntire(trimmed)
            if (p != null) {
                foundStructure = true
                close()
                current = Section(roleHint = roleFromName(p.groupValues[1])!!, heading = null)
                val rest = p.groupValues[2]
                if (rest.isNotBlank()) current.lines += rest
                continue
            }

            current.lines += line
        }
        close()

        val nonEmpty = sections.filter { it.lines.any { l -> l.isNotBlank() } || it.heading != null }
        if (nonEmpty.isEmpty()) {
            return SplitResult(emptyList(), titleHint, false, notes)
        }

        // Drop pure-heading sections that only label the next section.
        val messages = mutableListOf<CanonicalMessage>()
        var lastRole = "assistant" // so the first alternated section becomes "user"
        for (s in nonEmpty) {
            val body = s.lines.joinToString("\n").trim()
            if (body.isEmpty() && s.heading != null) continue // bare heading, skip
            val role = s.roleHint ?: run {
                lastRole = if (lastRole == "user") "assistant" else "user"
                lastRole
            }
            if (s.roleHint != null) lastRole = role
            messages += CanonicalMessage(
                role = role,
                text = body,
                markdown = body,
                codeBlocks = ParseHelpers.extractCodeBlocks(body),
                raw = JsonObject(
                    buildMap {
                        if (s.heading != null) put("sectionHeading", JsonPrimitive(s.heading))
                        put("heuristicSplit", JsonPrimitive(true))
                    },
                ),
            )
        }

        if (!foundStructure) {
            notes += "no message structure detected; stored as one document"
        } else {
            notes += "message boundaries were guessed heuristically; check the transcript"
        }
        return SplitResult(messages, titleHint, foundStructure, notes)
    }

    private data class Section(
        val roleHint: String?,
        val heading: String?,
        val lines: MutableList<String> = mutableListOf(),
    )

    private val HR = Regex("^(\\*\\*\\*+|---+|___+)\\s*$")
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private val BOLD_ROLE = Regex("^\\s*(\\*\\*|__)(.+?)\\1\\s*:?\\s*(.*)$")
    private val PLAIN_ROLE =
        Regex("^\\s*(user|assistant|gemini|you|ai|human|system|bot|model)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)

    private fun roleFromName(name: String): String? = when (name.trim().lowercase()) {
        "user", "you", "human", "me", "question", "prompt" -> "user"
        "assistant", "gemini", "ai", "bot", "model", "answer", "response" -> "assistant"
        "system" -> "system"
        else -> null
    }
}
