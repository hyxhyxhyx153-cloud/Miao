package com.hyx.miao.ui.screens.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyx.miao.ui.screens.auth.glassTextFieldColors
import com.hyx.miao.ui.theme.LocalMiaoExtraColors

data class ModelOption(
    val provider: String,
    val modelId: String,
    val displayName: String,
    val emoji: String,
    val description: String,
    val supportsVision: Boolean = false,
    val isAvailable: Boolean = true,
)

data class PersonaOption(
    val id: String,
    val name: String,
    val description: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationSheet(
    options: ConversationOptionsState,
    onDismiss: () -> Unit,
    onConfirm: (model: ModelOption, persona: PersonaOption, title: String?) -> Unit,
) {
    val extra = LocalMiaoExtraColors.current
    val creatingState = rememberUpdatedState(options.isCreating)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            target != SheetValue.Hidden || !creatingState.value
        },
    )
    var selectedModelId by remember { mutableStateOf(options.selectedModelId) }
    var selectedPersonaId by remember { mutableStateOf(options.selectedPersonaId) }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(options.models, options.personas, options.selectedModelId, options.selectedPersonaId) {
        if (options.models.none { it.modelId == selectedModelId && it.isAvailable }) {
            selectedModelId = options.selectedModelId.takeIf { id ->
                options.models.any { it.modelId == id && it.isAvailable }
            } ?: options.models.firstOrNull { it.isAvailable }?.modelId.orEmpty()
        }
        if (options.personas.none { it.id == selectedPersonaId }) {
            selectedPersonaId = options.selectedPersonaId.takeIf { id ->
                options.personas.any { it.id == id }
            } ?: options.personas.firstOrNull()?.id.orEmpty()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!options.isCreating) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !options.isCreating,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = extra.glassStrong,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        scrimColor = Color.Black.copy(alpha = 0.28f),
    ) {
        if (options.isLoading) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@ModalBottomSheet
        }
        val selectedModel = options.models.firstOrNull {
            it.modelId == selectedModelId && it.isAvailable
        } ?: options.models.firstOrNull { it.isAvailable }
        val selectedPersona = options.personas.firstOrNull { it.id == selectedPersonaId }
            ?: options.personas.firstOrNull()
        LazyColumn(
            modifier = Modifier.padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("新建会话", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(30) },
                    enabled = !options.isCreating,
                    label = { Text("会话名称（可选）") },
                    placeholder = { Text("未填写时将自动生成") },
                    singleLine = true,
                    colors = glassTextFieldColors(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("AI 模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(options.models, key = { it.modelId }) { model ->
                        ModelCard(
                            model = model,
                            selected = model.modelId == selectedModel?.modelId,
                            enabled = model.isAvailable && !options.isCreating,
                            onClick = { selectedModelId = model.modelId },
                        )
                    }
                }
            }
            item {
                Text("人格", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.personas.forEach { persona ->
                        PersonaRow(
                            persona = persona,
                            selected = persona.id == selectedPersona?.id,
                            enabled = !options.isCreating,
                            onClick = { selectedPersonaId = persona.id },
                        )
                    }
                }
            }
            item {
                Text(
                    "温度 ${"%.1f".format(options.temperature)} · 最长 ${options.maxTokens} tokens · 上下文 ${options.contextTurns} 轮",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(
                    onClick = {
                        if (selectedModel != null && selectedPersona != null) {
                            onConfirm(selectedModel, selectedPersona, title.trim().ifBlank { null })
                        }
                    },
                    enabled = selectedModel != null && selectedPersona != null && !options.isCreating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (options.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            "开始聊天",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp),
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))),
                contentAlignment = Alignment.Center,
            ) { Text(model.emoji, color = Color.White, fontWeight = FontWeight.Bold) }
            Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        }
        Text(model.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (model.supportsVision) Text("支持图片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PersonaRow(
    persona: PersonaOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        Column {
            Text(persona.name, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(persona.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
