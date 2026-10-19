package dev.chiller.agent

import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContextPlannerTest {
    @Test fun budgetDropsOldestWholeTurnAndKeepsSystemAndNewestTurn() {
        val system = AgentMessage("system", "instructions")
        val oldUser = AgentMessage("user", "old ".repeat(25))
        val oldAssistant = AgentMessage("assistant", "old answer ".repeat(10))
        val currentUser = AgentMessage("user", "current request")
        val currentAssistant = AgentMessage("assistant", "current answer")
        val all = listOf(system, oldUser, oldAssistant, currentUser, currentAssistant)

        val selected = ContextPlanner(maxCharacters = 500, outputReserveCharacters = 350).select(all)

        assertEquals(listOf(system, currentUser, currentAssistant), selected.messages)
        assertEquals(2, selected.droppedMessages)
        assertTrue(selected.estimatedTokens > 0)
        assertTrue(selected.estimateOnly)
    }

    @Test fun retainedToolGroupPreservesReplayPayloadExactly() {
        val replay = buildJsonObject { put("reasoning_content", "private replay stays attached") }
        val call = ToolCall("call-1", "read", "{\"path\":\"src/Main.kt\"}")
        val messages = listOf(
            AgentMessage("system", "system"),
            AgentMessage("user", "inspect"),
            AgentMessage("assistant", toolCalls = listOf(call), replay = replay),
            AgentMessage("tool", "{\"ok\":true}", toolCallId = "call-1"),
            AgentMessage("assistant", "done"),
        )

        val selected = ContextPlanner(maxCharacters = 1_000, outputReserveCharacters = 100).select(messages)

        assertEquals(messages, selected.messages)
        assertEquals(replay, selected.messages[2].replay)
    }

    @Test fun droppingAHistoryTurnNeverLeavesOrphanedToolMessages() {
        val oldCall = ToolCall("old-call", "read", "{}")
        val messages = listOf(
            AgentMessage("system", "system"),
            AgentMessage("user", "old context ".repeat(30)),
            AgentMessage("assistant", toolCalls = listOf(oldCall)),
            AgentMessage("tool", "old result ".repeat(20), toolCallId = "old-call"),
            AgentMessage("assistant", "old final"),
            AgentMessage("user", "new question"),
        )

        val selected = ContextPlanner(maxCharacters = 500, outputReserveCharacters = 350).select(messages)

        assertEquals(listOf(messages[0], messages.last()), selected.messages)
        assertTrue(selected.messages.none { it.role == "tool" || it.toolCalls.isNotEmpty() })
        assertEquals(4, selected.droppedMessages)
    }

    @Test fun oversizedSystemMessagesFailWithTypedLimitException() {
        val planner = ContextPlanner(maxCharacters = 100, outputReserveCharacters = 20)
        assertFailsWith<ContextLimitException> {
            planner.select(listOf(AgentMessage("system", "x".repeat(100))))
        }
    }

    @Test fun oversizedCurrentTurnWithPairedActiveToolCallIsRejectedWhole() {
        val call = ToolCall("active-call", "read", "{\"path\":\"${"x".repeat(200)}\"}")
        val messages = listOf(
            AgentMessage("system", "system"),
            AgentMessage("user", "current request".repeat(20)),
            AgentMessage("assistant", toolCalls = listOf(call)),
            AgentMessage("tool", "result", toolCallId = "active-call"),
        )
        val planner = ContextPlanner(maxCharacters = 250, outputReserveCharacters = 50)

        assertFailsWith<ContextLimitException> { planner.select(messages) }
    }

    @Test fun oldLargeToolResultsArePrunedUnderPressureButPairsStayIntact() {
        val messages = mutableListOf(AgentMessage("system", "system"), AgentMessage("user", "inspect the project"))
        repeat(8) { index ->
            val call = ToolCall("call-$index", "read", "{}")
            messages += AgentMessage("assistant", toolCalls = listOf(call))
            messages += AgentMessage("tool", "x".repeat(800), toolCallId = "call-$index")
        }
        val planner = ContextPlanner(maxCharacters = 8_000, outputReserveCharacters = 1_000)

        val selected = planner.select(messages)

        assertEquals(2, selected.prunedResults)
        val tools = selected.messages.filter { it.role == "tool" }
        assertEquals(8, tools.size)
        assertEquals(listOf("call-0", "call-1"), tools.take(2).map { it.toolCallId })
        assertTrue(tools.take(2).all { it.content.orEmpty().contains("pruned") })
        assertTrue(tools.drop(2).all { it.content.orEmpty().length == 800 })
        assertEquals(0, selected.droppedMessages)
    }

    @Test fun longActiveTurnDropsWholeExchangesButKeepsGoalAndUnknownOutcome() {
        val messages = mutableListOf(AgentMessage("user", "Build landing with pricing"))
        repeat(10) { i ->
            messages += AgentMessage("assistant", toolCalls = listOf(ToolCall("c$i", "create_file", "{\"content\":\"${"x".repeat(900)}\"}")))
            messages += AgentMessage("tool", if (i == 0) "OUTCOME_UNKNOWN" else "ok", toolCallId = "c$i")
        }
        val selected = ContextPlanner(maxCharacters = 3500, outputReserveCharacters = 500).select(messages)
        assertTrue(selected.droppedMessages > 0)
        assertEquals(messages.first(), selected.messages.first())
        assertTrue(selected.messages.any { it.content == "OUTCOME_UNKNOWN" })
        assertTrue(selected.messages.any { it.toolCallId == "c9" })
        assertEquals(selected.messages.flatMap { it.toolCalls }.map { it.id }.toSet(), selected.messages.mapNotNull { it.toolCallId }.toSet())
    }

    @Test fun shortSessionKeepsToolResultsVerbatim() {
        val messages = mutableListOf(AgentMessage("system", "system"), AgentMessage("user", "inspect"))
        repeat(3) { index ->
            messages += AgentMessage("assistant", toolCalls = listOf(ToolCall("call-$index", "read", "{}")))
            messages += AgentMessage("tool", "x".repeat(800), toolCallId = "call-$index")
        }

        val selected = ContextPlanner(maxCharacters = 8_000, outputReserveCharacters = 1_000).select(messages)

        assertEquals(0, selected.prunedResults)
        assertTrue(selected.messages.filter { it.role == "tool" }.all { it.content.orEmpty().length == 800 })
    }
    @Test fun activeTurnCanFitAfterOldResultsAreCompacted() {
        val messages = mutableListOf(AgentMessage("user", "investigate"))
        repeat(10) { i ->
            messages += AgentMessage("assistant", toolCalls = listOf(ToolCall("c$i", "read_file", "{}")))
            messages += AgentMessage("tool", "x".repeat(1500), toolCallId = "c$i")
        }
        val selected = ContextPlanner(maxCharacters = 7000, outputReserveCharacters = 1000, keepToolResults = 2).select(messages)
        assertEquals(8, selected.prunedResults)
        assertEquals(0, selected.droppedMessages)
        assertEquals(messages.size, selected.messages.size)
        assertTrue(selected.messages.takeLast(4).filter { it.role == "tool" }.all { it.content!!.length == 1500 })
    }

    @Test fun compactionPreservesFailureAndNeverInventsSuccess() {
        val messages = mutableListOf(AgentMessage("user", "inspect"))
        listOf("{\"ok\":false,\"error\":{\"code\":\"STALE_SNAPSHOT\",\"message\":\"${"x".repeat(1000)}\"}}", "x".repeat(1000)).forEachIndexed { i, body ->
            messages += AgentMessage("assistant", toolCalls = listOf(ToolCall("c$i", "read_file", "{}")))
            messages += AgentMessage("tool", body, toolCallId = "c$i")
        }
        val result = ContextPlanner(maxCharacters = 3000, outputReserveCharacters = 500, keepToolResults = 0).select(messages)
        val tools = result.messages.filter { it.role == "tool" }
        assertTrue(tools[0].content!!.contains("STALE_SNAPSHOT"))
        assertTrue(tools[0].content!!.contains("false"))
        assertTrue(!tools[1].content!!.contains("\"ok\""))
    }
    @Test fun pairedCancelledTurnDoesNotHideEarlierGoal() {
        val messages = listOf(
            AgentMessage("user", "Make a landing"), AgentMessage("assistant", "Skywise Tailwind plan"),
            AgentMessage("user", "Start"), AgentMessage("assistant", toolCalls = listOf(ToolCall("c", "run_process", "{}"))),
            AgentMessage("tool", "PERMISSION_DENIED", toolCallId = "c"), AgentMessage("user", "Try again"))
        assertEquals(messages, ContextPlanner().select(messages).messages)
    }

    @Test fun unknownHistoricalToolOutcomeStillStopsSelection() {
        val messages = listOf(AgentMessage("user", "old goal"), AgentMessage("assistant", "plan"),
            AgentMessage("user", "write"), AgentMessage("assistant", toolCalls = listOf(ToolCall("unknown", "write", "{}"))),
            AgentMessage("user", "continue"))
        val selected = ContextPlanner().select(messages)
        assertEquals(listOf(messages.last()), selected.messages)
    }

    @Test fun estimateAccountsForNonAsciiAndKnownWindowDoesNotUseLegacyCharacterCap() {
        assertTrue(ContextPlanner().select(listOf(AgentMessage("user", "я".repeat(100)))).estimatedTokens >
            ContextPlanner().select(listOf(AgentMessage("user", "x".repeat(100)))).estimatedTokens)
        val messages = listOf(AgentMessage("user", "x".repeat(100_000)), AgentMessage("assistant", "done"), AgentMessage("user", "continue"))
        assertEquals(0, ContextPlanner(maxCharacters = effectiveContextWindow(1_048_576) * 4, outputReserveCharacters = 16384).select(messages).droppedMessages)
    }

    @Test fun memoryKeepsDetailedGoalAndPlanAcrossShortFollowupsAndRebuildsAfterDeletion() {
        val goal = "Build Skywise landing: " + "requirements ".repeat(10)
        val source = listOf(AgentMessage("user", goal), AgentMessage("assistant", "Plan: Skywise with pricing")) +
            (1..10).flatMap { listOf(AgentMessage("user", "continue"), AgentMessage("assistant", "working")) }
        val memory = conversationMemory(source)
        assertTrue(memory.contains("requirements"))
        assertTrue(memory.contains("Plan: Skywise"))
        assertTrue(memory.contains("extractive_reference"))
        assertTrue(!conversationMemory(source.drop(2)).contains("Skywise"))
    }

    @Test fun earlyConstraintsAndTheOpenPlanOutliveLongConversations() {
        val early = "Fix pricing. Do not change the public API of quote_total. Retries должно быть 2."
        val source = listOf(AgentMessage("user", early), AgentMessage("assistant", null, listOf(ToolCall("p", "update_plan",
            """{"steps":[{"step":"Fix subtotal","status":"done"},{"step":"Clamp discount","status":"pending"}]}""")))) +
            (1..30).flatMap { listOf(AgentMessage("user", "Review batch $it: all samples returned 200."), AgentMessage("assistant", "Reviewed batch $it.")) }
        val memory = Json.parseToJsonElement(conversationMemory(source, maxCharacters = 2_000)).jsonObject
        val constraints = memory.getValue("constraints").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
        assertTrue("Do not change the public API of quote_total." in constraints, constraints.toString())
        assertTrue("Retries должно быть 2." in constraints, constraints.toString())
        assertEquals("user message 1", memory.getValue("constraints").jsonArray.first().jsonObject.getValue("source").jsonPrimitive.content)
        assertTrue(conversationMemory(source, maxCharacters = 2_000).length <= 2_000, "lower-priority excerpts give way to the budget")
    }

    @Test fun constraintsAreDedupedAndKeepTheEarliestWhenMany() {
        val requests = (1..30).map { "Rule $it: never touch file$it.txt." } + "rule 1: never touch file1.txt."
        val found = userConstraints(requests)
        assertEquals(16, found.size)
        assertEquals(1, found.first().first, "the earliest rules stay")
        assertEquals("Rule 30: never touch file30.txt.", found.last().second)
    }

}
