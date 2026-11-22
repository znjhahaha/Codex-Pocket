package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.LocalAccess
import dev.codexpocket.app.data.LocalProviderDraft
import dev.codexpocket.app.data.LocalProviderRules
import dev.codexpocket.app.data.LocalProviderSummary
import dev.codexpocket.app.ui.kit.*

/** Which model service Codex on this phone uses: OpenAI's own sign-in, or a service the person has a key for. */
@Composable
internal fun ColumnScope.ThisPhoneProviders(access: LocalAccess, applying: Boolean, onUse: (String?) -> Unit,
    onSave: (LocalProviderDraft) -> Boolean, onRemove: (String) -> Unit, official: @Composable ColumnScope.() -> Unit) {
    // Empty is a new service, an id is one being changed.
    var editing by remember { mutableStateOf<String?>(null) }
    PText("模型服务", style = Pocket.type.titleSmall)
    val target = editing
    if (target != null) {
        val existing = access.providers.firstOrNull { it.id == target }
        key(target) { ProviderForm(existing, onSave, if (existing != null) ({ onRemove(existing.id) }) else null) { editing = null } }
        return
    }
    PText("可以登录 OpenAI 官方账号，也可以像 CC Switch 那样填写第三方服务的地址和 Key，随时切换。Key 加密保存在这台手机上，只交给手机上的 Codex。",
        style = Pocket.type.caption, color = Pocket.colors.inkMuted)
    PCard(padding = PaddingValues(6.dp), spacing = 2.dp) {
        PRow("OpenAI 官方", { onUse(null) }, Modifier.testTag("this-phone-official"), subtitle = "用 ChatGPT 账号登录",
            enabled = !applying, selected = access.activeId == null, accentSelection = true)
        access.providers.forEach { provider ->
            val selected = provider.id == access.activeId
            PRow(provider.name, { onUse(provider.id) }, Modifier.testTag("this-phone-provider:${provider.id}"),
                subtitle = "${provider.baseUrl} · ${provider.model.ifEmpty { "默认模型" }} · ${provider.keyHint}",
                enabled = !applying, selected = selected, accentSelection = true, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) PIcon(PocketIcons.Check, null, Modifier.size(17.dp))
                        PTextAction("编辑", { editing = provider.id }, Modifier.testTag("this-phone-provider-edit:${provider.id}"), enabled = !applying)
                    }
                })
        }
    }
    val active = access.active
    when {
        applying -> PocketProgressStatus("正在让手机上的 Codex 改用所选服务", Modifier.testTag("this-phone-applying"))
        active == null -> official()
        else -> PText("正在使用「${active.name}」，对话会发往 ${active.baseUrl}。", Modifier.testTag("this-phone-provider-active"), style = Pocket.type.bodySmall)
    }
    PButton("添加第三方服务", { editing = "" }, Modifier.fillMaxWidth().testTag("this-phone-add-provider"), kind = PButtonKind.Secondary, enabled = !applying)
    PText("切换或修改服务会重新启动手机上的 Codex，正在进行的任务会结束。", style = Pocket.type.caption, color = Pocket.colors.inkMuted)
}

@Composable
private fun ColumnScope.ProviderForm(existing: LocalProviderSummary?, onSave: (LocalProviderDraft) -> Boolean, onRemove: (() -> Unit)?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var model by remember { mutableStateOf(existing?.model.orEmpty()) }
    // A key is not placed in the Activity's unencrypted saved-state bundle, and a saved one is never shown again.
    var key by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    PText(if (existing == null) "添加第三方服务" else "编辑「${existing.name}」", style = Pocket.type.body)
    PTextField(name, { name = it; problem = null }, Modifier.testTag("this-phone-provider-name"), label = "名称", placeholder = "例如：我的中转站", singleLine = true)
    PTextField(url, { url = it.trim(); problem = null }, Modifier.testTag("this-phone-provider-url"), label = "接口地址（Base URL）",
        placeholder = "https://api.example.com/v1", singleLine = true, keyboardOptions = plain.copy(keyboardType = KeyboardType.Uri))
    PTextField(key, { key = it.trim(); problem = null }, Modifier.testTag("this-phone-provider-key"), label = "API Key",
        placeholder = if (existing != null) "留空则继续使用 ${existing.keyHint}" else "sk-…", singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = plain.copy(keyboardType = KeyboardType.Password))
    PTextField(model, { model = it.trim(); problem = null }, Modifier.testTag("this-phone-provider-model"), label = "模型（可选）",
        placeholder = "留空使用 Codex 默认模型", singleLine = true, keyboardOptions = plain.copy(keyboardType = KeyboardType.Ascii))
    PText("服务需要兼容 OpenAI 的 Responses 接口，地址一般以 /v1 结尾。使用 OpenAI 官方 API Key 时填 https://api.openai.com/v1。",
        style = Pocket.type.caption, color = Pocket.colors.inkMuted)
    problem?.let { PText(it, Modifier.testTag("this-phone-provider-problem"), style = Pocket.type.bodySmall, color = Pocket.colors.danger) }
    PButton("保存并使用", {
        problem = when {
            LocalProviderRules.name(name) == null -> "请填写名称，不超过 40 个字。"
            LocalProviderRules.baseUrl(url) == null -> "接口地址需要以 https:// 开头；http:// 只能用于数字 IP 地址。"
            (existing == null || key.isNotEmpty()) && LocalProviderRules.apiKey(key) == null -> "请填写完整的 API Key。"
            LocalProviderRules.model(model) == null -> "模型名称只能包含英文字母、数字和符号。"
            !onSave(LocalProviderDraft(existing?.id, name, url, key, model)) -> "未能保存，请重试。"
            else -> null
        }
        if (problem == null) { key = ""; onClose() }
    }, Modifier.fillMaxWidth().testTag("this-phone-provider-save"))
    if (onRemove != null) PButton("删除这个服务", { onRemove(); onClose() }, Modifier.fillMaxWidth().testTag("this-phone-provider-delete"), kind = PButtonKind.Danger)
    PButton("取消", onClose, Modifier.fillMaxWidth().testTag("this-phone-provider-cancel"), kind = PButtonKind.Secondary)
}
