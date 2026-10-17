package dev.chiller.sessions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceFileTest {
    @Test fun appendsBoundedRecords() = runBlocking {
        val dir = Files.createTempDirectory("trace")
        val file = TraceFile(dir.resolve("t/s.jsonl"), maxRecordBytes = 200, maxFileBytes = 250)
        file.append(buildJsonObject { put("round", 1); put("messages", "small") })
        file.append(buildJsonObject { put("round", 2); put("messages", "x".repeat(500)) })
        file.append(buildJsonObject { put("round", 3); put("messages", "y".repeat(150)) })
        file.append(buildJsonObject { put("round", 4) })
        val lines = Files.readAllLines(file.path)
        assertEquals(listOf(1, 2, 3), lines.map { Json.parseToJsonElement(it).jsonObject.getValue("round").jsonPrimitive.int })
        assertTrue("omitted" in Json.parseToJsonElement(lines[1]).jsonObject)
    }
}
