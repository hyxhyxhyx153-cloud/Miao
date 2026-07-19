package com.hyx.miao.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.remote.api.PersonaApi
import com.hyx.miao.data.remote.dto.CreatePersonaRequest
import com.hyx.miao.data.remote.dto.PersonaResponse
import com.hyx.miao.data.remote.dto.UpdatePersonaRequest
import com.hyx.miao.data.repository.MediaRepository
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoTopBar
import com.hyx.miao.ui.theme.LocalMiaoExtraColors
import com.hyx.miao.ui.theme.ErrorRed
import com.hyx.miao.ui.theme.MiaoPurple
import dagger.hilt.android.lifecycle.HiltViewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val DEFAULT_PERSONA_SYSTEM_PROMPT = """
    每次回复末尾必须追加一个 JSON 注释，不要放入 Markdown 代码块，注释后不要再输出内容。
    格式：<!--{"emotion":"gentle","action":null}-->。
    emotion 根据当前回复从 happy/excited/curious/shy/embarrassed/caring/gentle/playful/thinking/surprised/sad/nervous/proud/sleepy/angry 中选；action 为符合人格的简短动作，无动作填 null。
""".trimIndent()

// ── ViewModel ────────────────────────────────────────────────────────────────

data class PersonaUiState(
    val personas: List<PersonaResponse> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PersonaSettingsViewModel @Inject constructor(
    private val personaApi: PersonaApi,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PersonaUiState())
    val state: StateFlow<PersonaUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching { personaApi.getAll() }
                .onSuccess { _state.value = _state.value.copy(personas = it, isLoading = false) }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message) }
        }
    }

    fun create(
        name: String,
        description: String?,
        systemPrompt: String?,
        existingReferenceUrls: List<String>,
        newReferenceUris: List<Uri>,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, error = null)
            runCatching {
                val references = uploadReferences(existingReferenceUrls, newReferenceUris)
                personaApi.create(
                    CreatePersonaRequest(
                        name = name,
                        description = description,
                        systemPrompt = systemPrompt,
                        referenceImageUrls = references,
                    )
                )
            }.onSuccess {
                _state.value = _state.value.copy(isSaving = false)
                load()
                onDone()
            }.onFailure {
                _state.value = _state.value.copy(isSaving = false, error = it.message)
            }
        }
    }

    fun update(
        id: String,
        name: String,
        description: String?,
        systemPrompt: String?,
        existingReferenceUrls: List<String>,
        newReferenceUris: List<Uri>,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, error = null)
            runCatching {
                val references = uploadReferences(existingReferenceUrls, newReferenceUris)
                personaApi.update(
                    id,
                    UpdatePersonaRequest(
                        name = name,
                        description = description,
                        systemPrompt = systemPrompt,
                        referenceImageUrls = references,
                    )
                )
            }.onSuccess {
                _state.value = _state.value.copy(isSaving = false)
                load()
                onDone()
            }.onFailure {
                _state.value = _state.value.copy(isSaving = false, error = it.message)
            }
        }
    }

    private suspend fun uploadReferences(
        existingReferenceUrls: List<String>,
        newReferenceUris: List<Uri>,
    ): List<String> {
        require(existingReferenceUrls.size + newReferenceUris.size <= 3) {
            "人格参考图最多 3 张"
        }
        val uploaded = if (newReferenceUris.isEmpty()) {
            emptyList()
        } else {
            mediaRepository.uploadReferenceImages(newReferenceUris)
        }
        return (existingReferenceUrls + uploaded).distinct().also {
            require(it.size <= 3) { "人格参考图最多 3 张" }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { personaApi.delete(id) }
                .onSuccess { load() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonaSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            !(state.isSaving && target == SheetValue.Hidden)
        },
    )
    val scope = rememberCoroutineScope()

    var showSheet by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PersonaResponse?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        MiaoTopBar(
            title = { Text("人格设置", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
        )

        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MiaoPurple)
            } else if (state.error != null && state.personas.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("加载失败", style = MaterialTheme.typography.bodyMedium)
                    Text(state.error ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.load() }) { Text("重试", color = MiaoPurple) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.personas, key = { it.id }) { persona ->
                        PersonaItem(
                            persona = persona,
                            onEdit = { editTarget = persona; showSheet = true },
                            onDelete = { viewModel.delete(persona.id) },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }

            FloatingActionButton(
                onClick = { editTarget = null; showSheet = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = LocalMiaoExtraColors.current.glassStrong,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, "新建人格")
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                if (!state.isSaving) {
                    showSheet = false
                    editTarget = null
                }
            },
            sheetState = sheetState,
            containerColor = LocalMiaoExtraColors.current.glassStrong,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        ) {
            PersonaForm(
                initial = editTarget,
                isSaving = state.isSaving,
                error = state.error,
                onSubmit = { name, desc, prompt, referenceUrls, referenceUris ->
                    val target = editTarget
                    if (target == null) {
                        viewModel.create(name, desc, prompt, referenceUrls, referenceUris) {
                            scope.launch { sheetState.hide(); showSheet = false }
                        }
                    } else {
                        viewModel.update(
                            target.id,
                            name,
                            desc,
                            prompt,
                            referenceUrls,
                            referenceUris,
                        ) {
                            scope.launch { sheetState.hide(); showSheet = false; editTarget = null }
                        }
                    }
                },
                onCancel = {
                    if (!state.isSaving) {
                        scope.launch { sheetState.hide(); showSheet = false; editTarget = null }
                    }
                },
            )
        }
    }
}

@Composable
private fun PersonaItem(
    persona: PersonaResponse,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(persona.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (persona.isBuiltin) {
                        Text(
                            "内置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MiaoPurple,
                        )
                    } else if (persona.sourcePersonaId != null) {
                        Text(
                            "专属",
                            style = MaterialTheme.typography.labelSmall,
                            color = MiaoPurple,
                        )
                    }
                }
                if (!persona.description.isNullOrBlank()) {
                    Text(
                        persona.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, "编辑", tint = MiaoPurple, modifier = Modifier.size(18.dp))
            }
            if (!persona.isBuiltin) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "删除", tint = ErrorRed, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun PersonaForm(
    initial: PersonaResponse?,
    isSaving: Boolean,
    error: String?,
    onSubmit: (
        name: String,
        description: String?,
        systemPrompt: String?,
        referenceImageUrls: List<String>,
        referenceImageUris: List<Uri>,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var description by remember(initial) { mutableStateOf(initial?.description ?: "") }
    var systemPrompt by remember(initial) {
        mutableStateOf(
            if (initial == null) DEFAULT_PERSONA_SYSTEM_PROMPT else initial.systemPrompt.orEmpty()
        )
    }
    var referenceImageUrls by remember(initial?.id) {
        mutableStateOf(initial?.referenceImageUrls.orEmpty())
    }
    var referenceImageUris by remember(initial?.id) { mutableStateOf<List<Uri>>(emptyList()) }
    var previewImage by remember { mutableStateOf<Any?>(null) }
    val referencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { selected ->
        val remaining = (3 - referenceImageUrls.size - referenceImageUris.size).coerceAtLeast(0)
        referenceImageUris = (referenceImageUris + selected)
            .distinctBy(Uri::toString)
            .take(referenceImageUris.size + remaining)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (initial == null) "新建人格" else "编辑人格",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (initial?.isBuiltin == true || initial?.sourcePersonaId != null) {
            Text(
                if (initial?.isBuiltin == true) {
                    "修改会保存为你的专属人格，不会影响系统内置人格。"
                } else {
                    "这是你的专属人格；删除后会恢复系统内置版本。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isSaving,
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("简介（可选）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 3,
            enabled = !isSaving,
        )
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("系统提示词（System Prompt）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 8,
            enabled = !isSaving,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("人格参考图", style = MaterialTheme.typography.titleSmall)
                Text(
                    "用于生成图片时保持人物形象一致，仅支持 JPEG、PNG、WebP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${referenceImageUrls.size + referenceImageUris.size}/3",
                style = MaterialTheme.typography.labelLarge,
                color = MiaoPurple,
            )
        }

        if (referenceImageUrls.isNotEmpty() || referenceImageUris.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(referenceImageUrls, key = { "remote:$it" }) { url ->
                    ReferenceImageThumbnail(
                        model = url,
                        onPreview = { previewImage = url },
                        onRemove = { referenceImageUrls = referenceImageUrls - url },
                        enabled = !isSaving,
                    )
                }
                items(referenceImageUris, key = { "local:$it" }) { uri ->
                    ReferenceImageThumbnail(
                        model = uri,
                        onPreview = { previewImage = uri },
                        onRemove = { referenceImageUris = referenceImageUris - uri },
                        enabled = !isSaving,
                    )
                }
            }
        }

        TextButton(
            onClick = {
                referencePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            enabled = !isSaving && referenceImageUrls.size + referenceImageUris.size < 3,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("选择参考图")
        }

        if (!error.isNullOrBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !isSaving, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            Button(
                onClick = {
                    if (name.isNotBlank() && systemPrompt.isNotBlank()) {
                        onSubmit(
                            name.trim(),
                            description.trim().ifBlank { null },
                            systemPrompt.trim().ifBlank { null },
                            referenceImageUrls,
                            referenceImageUris,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MiaoPurple),
                enabled = name.isNotBlank() && systemPrompt.isNotBlank() && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text("保存")
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    previewImage?.let { model ->
        AlertDialog(
            onDismissRequest = { previewImage = null },
            title = { Text("参考图预览") },
            text = {
                AsyncImage(
                    model = model,
                    contentDescription = "人格参考图预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = { previewImage = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ReferenceImageThumbnail(
    model: Any,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    Box(modifier = Modifier.size(104.dp)) {
        AsyncImage(
            model = model,
            contentDescription = "人格参考图",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(92.dp)
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onPreview),
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.size(48.dp).align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.64f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除参考图",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
