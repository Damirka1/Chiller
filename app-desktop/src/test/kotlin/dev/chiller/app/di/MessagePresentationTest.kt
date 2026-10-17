package dev.chiller.app.di

import dev.chiller.app.ui.authorLabel
import dev.chiller.contracts.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MessagePresentationTest {
    @Test fun `legacy offline and persisted model labels remain distinct`() {
        assertEquals("Ассистент", authorLabel(AgentMessage("assistant", "old")))
        assertEquals("Chiller · offline", authorLabel(AgentMessage("assistant", origin = MessageOrigin(MessageOriginKind.OFFLINE))))
        assertEquals("DeepSeek V4.1 Flash", authorLabel(AgentMessage("assistant", origin = MessageOrigin(MessageOriginKind.MODEL, "deepseek-flash"))))
        assertEquals("gemma-test", authorLabel(AgentMessage("assistant", origin = MessageOrigin(MessageOriginKind.MODEL, "local/gemma-test"))))
        assertEquals("Вы", authorLabel(AgentMessage("user", "hello")))
    }
}
