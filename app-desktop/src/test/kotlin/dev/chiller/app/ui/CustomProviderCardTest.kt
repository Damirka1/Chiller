package dev.chiller.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.chiller.app.di.CustomProvider
import dev.chiller.design.ChillerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CustomProviderCardTest {
    @Test fun typedValuesStayAndAreSaved() = runComposeUiTest {
        val saved = mutableListOf<CustomProvider>()
        val actions = SettingsActions(saveCustomProvider = { provider, done -> saved += provider; done(null) })
        setContent {
            ChillerTheme(darkTheme = false, reducedMotion = true) {
                CustomProviderCard(SettingsData("https://api.deepseek.com", "deepseek-flash", false, false), actions, editing = null, onEdit = {}, onUse = {})
            }
        }
        onNodeWithText("Добавить свой провайдер").performClick()
        // Each character recomposes the card; the draft must survive it.
        onNodeWithContentDescription("Название").performTextInput("Qarmet")
        onNodeWithContentDescription("Адрес API").performTextInput("https://gateway.example.com/v1")
        onNodeWithContentDescription("Переменная с ключом (необязательно)").performTextInput("QARMET_API_KEY")
        onNodeWithContentDescription("Название").assertTextEquals("Qarmet", includeEditableText = true)
        onNodeWithText("Полный").performClick()
        onNodeWithText("Добавить и подключить").performClick()
        val provider = saved.single()
        assertEquals("Qarmet", provider.name)
        assertEquals("https://gateway.example.com/v1", provider.baseUrl)
        assertEquals("QARMET_API_KEY", provider.keyVariable)
        assertEquals("full", provider.profile)
    }
}
