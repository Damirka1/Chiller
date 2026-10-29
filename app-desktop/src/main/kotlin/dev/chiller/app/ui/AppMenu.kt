package dev.chiller.app.ui

import dev.chiller.app.i18n.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.design.ChillerIconButton
import dev.chiller.design.LocalChillerPalette

/** One application command. [key] with Ctrl (⌘ on macOS) triggers it; [keyLabel] is how the key is written. */
class AppMenuItem(
    val title: String, val key: Key? = null, val keyLabel: String? = null, val enabled: Boolean = true, val onClick: () -> Unit,
)

/** A top-level menu; groups are separated by dividers. The same model feeds the system menu bar and the in-app menu. */
class AppMenuSection(val title: String, val groups: List<List<AppMenuItem>>)

/** The enabled command bound to [key], if any; used for accelerators when there is no system menu bar. */
internal fun List<AppMenuSection>.commandFor(key: Key): AppMenuItem? =
    asSequence().flatMap { it.groups.asSequence() }.flatten().firstOrNull { it.key == key && it.enabled }

/** The application menu as a toolbar button, for windows without a system menu bar (Windows custom title bar). */
@Composable
internal fun AppMenuButton(menu: List<AppMenuSection>) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChillerIconButton(Icons.Outlined.Menu, strings.screens.menu, { open = !open })
        if (open) ChillerPopover(onDismiss = { open = false }, width = 264.dp, below = true) {
            AppMenuContent(menu) { open = false }
        }
    }
}

@Composable
private fun AppMenuContent(menu: List<AppMenuSection>, onClose: () -> Unit) {
    val c = LocalChillerPalette.current
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        menu.forEachIndexed { index, section ->
            if (index > 0) MenuDivider()
            Text(section.title, color = c.faint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp))
            section.groups.flatten().forEach { item ->
                MenuRow(item.title, { onClose(); item.onClick() }, enabled = item.enabled, shortcut = item.keyLabel?.let { shortcut(it) })
            }
        }
    }
}
