package dev.codexpocket.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.codexpocket.app.data.*
import dev.codexpocket.app.R
import kotlinx.serialization.json.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalCard(a: PendingApproval, online: Boolean, onRespond: (PendingApproval, JsonObject) -> Unit) {
    if (ConversationPresentation.hookType(a.method)) return
    val context = LocalContext.current
    val enabled = online && !a.submitted
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (a.submitted) PocketIcons.PendingActions else PocketIcons.ShieldCheck, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(if (a.submitted) context.getString(R.string.waiting_resolution) else context.getString(R.string.attention), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (a.method) {
                "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                    Text(if (a.method.contains("commandExecution")) context.getString(R.string.run_command) else context.getString(R.string.apply_changes), style = MaterialTheme.typography.titleMedium)
                    a.params.string("command").takeIf(String::isNotBlank)?.let { command ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            SelectionContainer { Text(command, Modifier.fillMaxWidth().padding(14.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    a.params.string("cwd").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    a.params.string("reason").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    var details by rememberSaveable(a.key) { mutableStateOf(false) }
                    PocketActionRow(
                        title = if (details) context.getString(R.string.hide_request) else context.getString(R.string.review_request),
                        icon = PocketIcons.Info,
                        onClick = { details = !details },
                        trailing = { Icon(if (details) PocketIcons.ExpandLess else PocketIcons.ExpandMore, null, Modifier.size(18.dp)) },
                    )
                    AnimatedVisibility(details, enter = fadeIn(tween(pocketMotionMillis(180))) + expandVertically(tween(pocketMotionMillis(180))), exit = fadeOut(tween(pocketMotionMillis(120))) + shrinkVertically(tween(pocketMotionMillis(180)))) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val paths = a.params.array("changes").map { it.asObject().string("path") }.filter(String::isNotBlank)
                            if (paths.isNotEmpty()) {
                                PocketSectionLabel(stringResource(R.string.v02_request_files))
                                paths.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                            Text(stringResource(R.string.v02_permissions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Keep native decision strings and exact advertised option filtering unchanged.
                    val advertised = a.params["availableDecisions"] as? JsonArray
                    fun allowed(decision: String) = advertised == null || JsonPrimitive(decision) in advertised
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (allowed("accept")) PocketButton(context.getString(R.string.allow_once), { onRespond(a, buildJsonObject { put("decision", "accept") }) }, enabled = enabled)
                        if (allowed("decline")) PocketButton(context.getString(R.string.decline), { onRespond(a, buildJsonObject { put("decision", "decline") }) }, enabled = enabled, secondary = true)
                        if (allowed("cancel")) PocketButton(context.getString(R.string.deny_stop), { onRespond(a, buildJsonObject { put("decision", "cancel") }) }, enabled = enabled, secondary = true, destructive = true)
                    }
                }
                "item/tool/requestUserInput" -> UserInputQuestions(a, enabled, onRespond)
                else -> {
                    Text(context.getString(R.string.desktop_required))
                    a.params.string("reason").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun UserInputQuestions(a: PendingApproval, enabled: Boolean, onRespond: (PendingApproval, JsonObject) -> Unit) {
    val context = LocalContext.current
    val questions = a.params.array("questions").map(JsonElement::asObject)
    // Answers, especially isSecret input, stay in memory until native resolution.
    val answers = remember(a.key) { mutableStateMapOf<String, String>() }
    questions.forEach { q ->
        val id = q.string("id")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.string("header").takeIf(String::isNotBlank)?.let { PocketSectionLabel(it) }
            Text(q.string("question"), style = MaterialTheme.typography.bodyLarge)
            val options = q.array("options").map(JsonElement::asObject)
            options.forEach { option ->
                val label = option.string("label")
                PocketActionRow(
                    title = label,
                    icon = if (answers[id] == label) PocketIcons.CheckCircle else PocketIcons.RadioButtonUnchecked,
                    onClick = { answers[id] = label },
                    subtitle = option.string("description").takeIf(String::isNotBlank),
                    selected = answers[id] == label,
                    enabled = enabled,
                )
            }
            if (options.isEmpty() || q.flag("isOther")) {
                val value = answers[id].orEmpty().takeUnless { text -> options.any { it.string("label") == text } }.orEmpty()
                val secret = q.flag("isSecret")
                PocketTextField(
                    value, { answers[id] = it }, enabled = enabled,
                    label = if (options.isEmpty()) context.getString(R.string.your_answer) else context.getString(R.string.other_answer),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    PocketButton(
        text = context.getString(R.string.submit_answers),
        enabled = enabled && questions.isNotEmpty() && questions.all { !answers[it.string("id")].isNullOrBlank() },
        onClick = {
            onRespond(a, buildJsonObject { put("answers", buildJsonObject { questions.forEach { q -> put(q.string("id"), buildJsonObject { put("answers", buildJsonArray { add(answers.getValue(q.string("id"))) }) }) } }) })
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
