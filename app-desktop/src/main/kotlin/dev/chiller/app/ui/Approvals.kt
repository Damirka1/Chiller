package dev.chiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chiller.contracts.ApprovalRequest
import dev.chiller.design.*
import kotlinx.serialization.json.*

/**
 * Whether file changes wait for the user's yes or no, and how the composer's mode menu changes that; the same menu
 * also switches web access ([web]), remembered per project like process permission.
 */
data class EditApproval(val enabled: Boolean = false, val onChange: (Boolean) -> Unit = {}, val onDecide: (approved: Boolean, restOfRun: Boolean) -> Unit = { _, _ -> },
    val web: Boolean = false, val onWeb: ((Boolean) -> Unit)? = null)

val LocalEditApproval = staticCompositionLocalOf { EditApproval() }

/** What a pending change does, in words, and the diff it proposes when its arguments carry one. */
data class ApprovalPreview(val title: String, val path: String, val diff: String?, val note: String? = null)

private const val PREVIEW_LINES = 200

/**
 * Built from the call's own arguments, before anything runs: replace_text shows its old and new text, a new file its
 * content, edit_file its line edits. It is what the agent asked for, not a diff against the file on disk.
 */
fun approvalPreview(request: ApprovalRequest): ApprovalPreview {
    val args = request.arguments
    fun str(name: String) = args[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    val path = str("path")
    fun lines(prefix: Char, text: String) = text.trimEnd('\n').lines().let { all ->
        all.take(PREVIEW_LINES).joinToString("\n") { "$prefix$it" } + if (all.size > PREVIEW_LINES) "\n… ещё ${all.size - PREVIEW_LINES} строк" else ""
    }
    fun header() = "--- a/$path\n+++ b/$path\n"
    return when (request.tool) {
        "replace_text" -> ApprovalPreview("Заменить текст", path, header() + "@@\n" + lines('-', str("old_text")) + "\n" + lines('+', str("new_text")),
            args["expected_count"]?.jsonPrimitive?.intOrNull?.takeIf { it > 1 }?.let { "Во всех $it местах" })
        "create_file" -> ApprovalPreview("Создать файл", path, "--- /dev/null\n+++ b/$path\n@@\n" + lines('+', str("content")))
        "append_file" -> ApprovalPreview("Дописать в конец", path, header() + "@@\n" + lines('+', str("content")))
        "edit_file" -> {
            val edits = args["edits"]?.jsonArray.orEmpty().map { it.jsonObject }
            ApprovalPreview("Изменить строки", path, header() + edits.joinToString("\n") { edit ->
                val op = edit["op"]?.jsonPrimitive?.contentOrNull
                val start = edit["start_line"]?.jsonPrimitive?.intOrNull; val end = edit["end_line"]?.jsonPrimitive?.intOrNull
                val before = edit["before_line"]?.jsonPrimitive?.intOrNull
                val content = edit["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (op) {
                    "delete" -> "@@ удалить строки $start–$end"
                    "insert" -> "@@ вставить перед строкой $before\n" + lines('+', content)
                    else -> "@@ заменить строки $start–$end\n" + lines('+', content)
                }
            }, "${edits.size} ${plural(edits.size, "правка", "правки", "правок")} по номерам строк")
        }
        "delete_file" -> ApprovalPreview("Удалить файл", path, null, "Файл можно будет вернуть отменой")
        "move_file" -> ApprovalPreview("Переместить файл", path, null, "→ ${str("destination")}")
        "create_directory" -> ApprovalPreview("Создать папку", path, null)
        "generate_image" -> ApprovalPreview("Создать картинку", path, null, str("prompt").take(400).ifEmpty { null })
        else -> ApprovalPreview(request.tool, path, null)
    }
}

/** The change waiting for the user's decision; the run continues on «Применить» or gets a refusal on «Отклонить». */
@Composable
internal fun ApprovalCard(request: ApprovalRequest, onDecide: (approved: Boolean, restOfRun: Boolean) -> Unit) {
    val c = LocalChillerPalette.current
    val preview = remember(request.id) { approvalPreview(request) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.accent.copy(alpha = .45f), shape).background(c.surface)) {
        Row(Modifier.fillMaxWidth().background(c.accent.copy(alpha = .06f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FactCheck, null, Modifier.size(16.dp), tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Подтвердите правку · ${preview.title}", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(preview.path.ifEmpty { null }, preview.note).joinToString(" · "), color = c.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = ChillerFonts.mono)
            }
        }
        preview.diff?.let { diff ->
            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Diff(diff) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChillerControl("Применить", { onDecide(true, false) }, icon = Icons.Outlined.Check, style = ControlStyle.PRIMARY)
            ChillerControl("Отклонить", { onDecide(false, false) }, icon = Icons.Outlined.Close, style = ControlStyle.OUTLINE)
            Spacer(Modifier.weight(1f))
            ChillerControl("Применять остальные в этом ходе", { onDecide(true, true) }, icon = Icons.Outlined.DoneAll)
        }
    }
}
