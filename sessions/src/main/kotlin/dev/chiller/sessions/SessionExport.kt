package dev.chiller.sessions

import dev.chiller.contracts.EventStore
import dev.chiller.contracts.StoredEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Exports a session from its event log. Redaction covers known sensitive field names and common
 * credential spellings; it cannot guarantee that arbitrary user text contains no secret.
 */
class SessionExporter(private val store: EventStore) {
    private val outputJson = Json { prettyPrint = true; prettyPrintIndent = "  "; explicitNulls = true }
    private val secretKeys = Regex("(?i)(^|[^a-z0-9])(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization|password|passwd|private[_-]?key|credential|cookie|secret|token)(?:$|[^a-z0-9])")
    private val bearer = Regex("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val quotedAssignment = Regex("(?i)(\\b(?:[A-Z0-9_]*API[_-]?KEY|[A-Z0-9_]*ACCESS[_-]?TOKEN)\\s*[:=]\\s*)(['\"])([^'\"\\r\\n]{4,})\\2")
    private val bareAssignment = Regex("(?i)(\\b(?:[A-Z0-9_]*API[_-]?KEY|[A-Z0-9_]*ACCESS[_-]?TOKEN)\\s*[:=]\\s*)([A-Za-z0-9_-]{4,})(?![A-Za-z0-9_-]|\\s*\\()")
    private val redactionNotice = "Redaction targets common secret fields and credential patterns; it cannot guarantee secret-free user text."

    suspend fun markdown(sessionId: String): String {
        val events = orderedEvents(sessionId)
        val artifacts = artifactRefs(events)
        return buildString {
            appendLine("# Session export")
            appendLine()
            appendLine("- Session: `${safeInline(sessionId)}`")
            appendLine("- Exported at (UTC): `${Instant.now()}`")
            appendLine()
            appendLine("## Transcript")
            appendLine()
            val messages = events.filter { it.type == "message" }
            if (messages.isEmpty()) appendLine("_No messages._")
            for (event in messages) appendMessage(event)
            val activities = events.filter { it.type == "activity" }
            if (activities.isNotEmpty()) {
                appendLine("## Tool activity")
                appendLine()
                for (event in activities) appendActivity(event)
            }
            val references = events.filter { it.type == "change.reference" }
            if (references.isNotEmpty()) {
                appendLine("## Change references")
                appendLine()
                references.forEach { event ->
                    val p = event.payload
                    appendLine("- Activity `${safeInline(p.string("activityId"))}` → change `${safeInline(p.string("changeId"))}` (event ${event.seq}).")
                }
                appendLine()
            }
            if (artifacts.isNotEmpty()) {
                appendLine("## Artifact references")
                appendLine()
                artifacts.forEach { (id, size) -> appendLine("- `${safeInline(id)}`${size?.let { " (${it} bytes)" } ?: ""}") }
                appendLine()
            }
            appendLine("## Redaction")
            appendLine()
            appendLine(redactionNotice)
        }
    }

    suspend fun json(sessionId: String): String {
        val events = orderedEvents(sessionId)
        val envelope = buildJsonObject {
            put("schemaVersion", 1)
            put("sessionId", sessionId)
            put("exportedAtUtc", Instant.now().toString())
            put("redactionNotice", redactionNotice)
            put("events", JsonArray(events.map(::sanitizeEvent)))
            put("artifactRefs", JsonArray(artifactRefs(events).map { (id, size) ->
                buildJsonObject { put("artifactId", id); if (size != null) put("size", size) }
            }))
        }
        return outputJson.encodeToString(JsonElement.serializer(), envelope)
    }

    private suspend fun orderedEvents(sessionId: String): List<StoredEvent> = store.read(sessionId).sortedBy { it.seq }

    private fun sanitizeEvent(event: StoredEvent): JsonObject = buildJsonObject {
        put("schemaVersion", event.schemaVersion)
        put("id", event.id)
        put("sessionId", event.sessionId)
        put("seq", event.seq)
        put("timestamp", utc(event.timestamp))
        put("type", redactText(event.type))
        put("payload", sanitize(event.payload))
    }

    private fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { !it.equals("replay", ignoreCase = true) }.mapValues { (key, value) ->
            if (isSensitiveKey(key)) JsonPrimitive("[REDACTED]") else sanitize(value)
        })
        is JsonArray -> JsonArray(element.map(::sanitize))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redactText(element.content)) else element
        JsonNull -> JsonNull
    }

    private fun redactText(input: String): String {
        var text = input
        text = bearer.replace(text) { "${it.groupValues[1]}[REDACTED]" }
        text = quotedAssignment.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]${it.groupValues[2]}" }
        text = bareAssignment.replace(text) { "${it.groupValues[1]}[REDACTED]" }
        // Tool outputs often contain JSON encoded inside the message's content string.
        val trimmed = text.trim()
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            val sanitizedJson = runCatching { sanitize(Json.parseToJsonElement(text)) }.getOrNull()
            if (sanitizedJson != null) text = outputJson.encodeToString(JsonElement.serializer(), sanitizedJson)
        }
        return text
    }

    private fun StringBuilder.appendMessage(event: StoredEvent) {
        val payload = event.payload
        val role = payload.string("role") ?: "message"
        appendLine("### ${roleTitle(role)}")
        appendLine()
        val content = payload.string("content")
        if (content != null && content.isNotEmpty()) {
            appendFencedOrText(redactText(content), preferredLanguage(content))
        }
        payload["toolCalls"]?.jsonArrayOrNull?.forEach { call ->
            if (call !is JsonObject) return@forEach
            appendLine("#### Tool call: `${safeInline(redactText(call.string("name") ?: "tool"))}`")
            appendLine()
            val arguments = call.string("arguments")
            if (arguments != null) appendFencedOrText(redactText(arguments), "json")
        }
        payload.string("toolCallId")?.let { appendLine("_Tool call ID: `${safeInline(it)}`._"); appendLine() }
        appendLine("_UTC ${utc(event.timestamp)} · event ${event.seq}_")
        appendLine()
    }

    private fun StringBuilder.appendActivity(event: StoredEvent) {
        val p = event.payload
        val name = redactText(p.string("name") ?: "Tool")
        val target = redactText(p.string("target") ?: "")
        val status = redactText(p.string("status") ?: "unknown")
        appendLine("### ${name}${if (target.isNotEmpty()) " — `${safeInline(target)}`" else ""}")
        appendLine()
        appendLine("Status: **${safeInline(status)}** · UTC ${utc(event.timestamp)} · event ${event.seq}")
        appendLine()
        p.string("output")?.takeIf(String::isNotBlank)?.let { appendLine("Output:"); appendLine(); appendFencedOrText(redactText(it), preferredLanguage(it)) }
        p.string("diff")?.takeIf(String::isNotBlank)?.let { appendLine("Diff:"); appendLine(); appendFencedOrText(redactText(it), "diff") }
    }

    private fun StringBuilder.appendFencedOrText(text: String, language: String?) {
        val fence = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))
        append(fence).append(language.orEmpty()).append('\n')
        append(text)
        if (!text.endsWith('\n')) append('\n')
        append(fence).appendLine().appendLine()
    }

    private fun longestBacktickRun(text: String): Int {
        var longest = 0; var current = 0
        for (c in text) if (c == '`') { current++; longest = maxOf(longest, current) } else current = 0
        return longest
    }

    private fun preferredLanguage(text: String): String? = when {
        runCatching { Json.parseToJsonElement(text); true }.getOrDefault(false) -> "json"
        else -> null
    }

    private fun roleTitle(role: String): String = when (role.lowercase()) {
        "user" -> "User"
        "assistant" -> "Assistant"
        "tool" -> "Tool result"
        else -> role.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun isSensitiveKey(key: String): Boolean = secretKeys.containsMatchIn(key.replace(Regex("([a-z])([A-Z])"), "$1_$2"))
    private fun utc(value: String): String = runCatching { Instant.parse(value).toString() }.getOrDefault(value)
    private fun safeInline(value: String?): String = (value ?: "").replace("`", "\\`").replace('\n', ' ')

    private fun artifactRefs(events: List<StoredEvent>): List<Pair<String, Long?>> {
        val refs = linkedMapOf<String, Long?>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    for (key in listOf("artifactId", "beforeArtifact")) {
                        val id = element[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: continue
                        val size = element["size"]?.jsonPrimitive?.longOrNull
                        refs.putIfAbsent(id, size)
                    }
                    element.values.forEach(::visit)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        events.forEach { visit(it.payload) }
        return refs.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private val JsonElement.jsonArrayOrNull: JsonArray? get() = this as? JsonArray
}
