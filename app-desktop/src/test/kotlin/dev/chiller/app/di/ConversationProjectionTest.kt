package dev.chiller.app.di

import dev.chiller.app.ui.activityGroupLabel
import dev.chiller.app.ui.isCompletedDemo
import dev.chiller.app.ui.projectConversation
import dev.chiller.contracts.Activity
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationProjectionTest {
    // Helpers read the global language; these checks are written against the Russian texts.
    @kotlin.test.BeforeTest fun russian() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.RuStrings; dev.chiller.contracts.CoreLanguage.russian = true }
    @kotlin.test.AfterTest fun english() { dev.chiller.app.i18n.I18n.current = dev.chiller.app.i18n.EnStrings; dev.chiller.contracts.CoreLanguage.russian = false }

    @Test fun completedDemoIsDistinguishedFromNormalConversationStates() {
        assertEquals(true, isCompletedDemo(true, "completed"))
        assertEquals(true, isCompletedDemo(true, "failed"))
        assertEquals(false, isCompletedDemo(false, "completed"))
        assertEquals(false, isCompletedDemo(true, "streaming"))
    }

    @Test fun mapsActivitiesByToolCallIdWhenActivityPositionDoesNotMatchMessagePosition() {
        val messages = listOf(
            AgentMessage("user", "Inspect these files"),
            AgentMessage("assistant", toolCalls = listOf(ToolCall("read-a", "read_file", "{}"))),
            AgentMessage("tool", "ignored in projection", toolCallId = "read-a"),
            AgentMessage("assistant", toolCalls = listOf(ToolCall("edit-b", "edit_file", "{}"))),
        )
        val activities = listOf(
            Activity("edit-activity", "edit_file", "src/B.kt", "completed", toolCallId = "edit-b"),
            Activity("read-activity", "read_file", "src/A.kt", "completed", toolCallId = "read-a"),
        )

        val projection = projectConversation(messages, activities)

        assertEquals(listOf(0, 1, 3), projection.entries.map { it.index })
        assertEquals("read-activity", projection.entries[1].activities.single().id)
        assertEquals("edit-activity", projection.entries[2].activities.single().id)
        assertEquals(emptyList(), projection.unlinked)
    }

    @Test fun legacyActivitiesWithoutToolCallIdRemainUnlinked() {
        val activity = Activity("legacy", "read_file", "src/old.kt", "completed")
        val projection = projectConversation(
            listOf(AgentMessage("assistant", toolCalls = listOf(ToolCall("call-1", "read_file", "{}")))),
            listOf(activity),
        )

        assertEquals(emptyList(), projection.entries.single().activities)
        assertEquals(listOf(activity), projection.unlinked)
    }

    @Test fun repeatedToolCallIdsConsumeMatchingActivitiesInQueueOrder() {
        val messages = listOf(
            AgentMessage("assistant", toolCalls = listOf(ToolCall("same-call", "read_file", "{}"))),
            AgentMessage("assistant", toolCalls = listOf(ToolCall("same-call", "read_file", "{}"))),
        )
        val activities = listOf(
            Activity("first-activity", "read_file", "src/first.kt", "completed", toolCallId = "same-call"),
            Activity("second-activity", "read_file", "src/second.kt", "completed", toolCallId = "same-call"),
        )

        val projection = projectConversation(messages, activities)

        assertEquals(listOf("first-activity"), projection.entries[0].activities.map { it.id })
        assertEquals(listOf("second-activity"), projection.entries[1].activities.map { it.id })
        assertEquals(emptyList(), projection.unlinked)
    }

    @Test fun labelsCountDistinctKnownPathsOnlyForSuccessfulGroups() {
        val reads = listOf(
            Activity("r1", "read_file", "src/A.kt", "completed"),
            Activity("r2", "read_file", "src/A.kt", "completed"),
            Activity("r3", "read_file", "src/B.kt", "completed"),
        )
        assertEquals("Прочитано файлов: 2", activityGroupLabel(reads))

        val edits = listOf(
            Activity("e1", "edit_file", "src/A.kt", "completed"),
            Activity("e2", "create_file", "src/B.kt", "completed"),
        )
        assertEquals("Изменено файлов: 2", activityGroupLabel(edits))

        assertEquals("Действий: 2", activityGroupLabel(edits.map { it.copy(status = "failed") }))
        assertEquals("Действий: 1", activityGroupLabel(listOf(Activity("unknown", "edit_file", "", "completed"))))
    }
}
