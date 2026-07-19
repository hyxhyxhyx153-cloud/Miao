package com.hyx.miao.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.repository.AppSettings
import com.hyx.miao.data.repository.SettingsRepository
import com.hyx.miao.data.remote.api.ModelApi
import com.hyx.miao.data.remote.api.ModelDto
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoTopBar
import com.hyx.miao.ui.theme.MiaoPurple
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class ModelSettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val modelApi: ModelApi,
) : ViewModel() {

    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _models = MutableStateFlow<List<ModelDto>>(emptyList())
    val models = _models.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch { repo.syncFromCloud() }
        refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching { modelApi.getModels().filter { it.isEnabled } }.fold(
                onSuccess = { loaded ->
                    _models.value = loaded
                    _isLoading.value = false
                    val current = settings.value
                    if (loaded.isNotEmpty() && loaded.none { it.modelId == current.modelId }) {
                        val first = loaded.first()
                        repo.update { it.copy(modelProvider = first.provider, modelId = first.modelId) }
                    }
                },
                onFailure = {
                    _isLoading.value = false
                    _error.value = it.message ?: "模型列表加载失败"
                },
            )
        }
    }

    fun setProvider(v: String) {
        val first = _models.value.firstOrNull { it.provider == v } ?: return
        viewModelScope.launch { repo.update { it.copy(modelProvider = v, modelId = first.modelId) } }
    }
    fun setModelId(v: String)  { viewModelScope.launch { repo.update { it.copy(modelId = v) } } }
    fun setTemperature(v: Float) { viewModelScope.launch { repo.update { it.copy(temperature = v) } } }
    fun setMaxTokens(v: Int)   { viewModelScope.launch { repo.update { it.copy(maxTokens = v) } } }
    fun setContextTurns(v: Int) { viewModelScope.launch { repo.update { it.copy(contextTurns = v) } } }
    fun setStreaming(v: Boolean) { viewModelScope.launch { repo.update { it.copy(streaming = v) } } }

}

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelSettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val providers = remember(models) { models.map { it.provider }.distinct() }

    Column(modifier = modifier.fillMaxSize()) {
        MiaoTopBar(
            title = { Text("模型设置", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Provider selection
            LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("模型提供商", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    providers.forEach { key ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = s.modelProvider == key,
                                onClick = { viewModel.setProvider(key) },
                                colors = RadioButtonDefaults.colors(selectedColor = MiaoPurple),
                            )
                            Text(key.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Model ID dropdown
            LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    val providerModels = models.filter { it.provider == s.modelProvider }
                    ModelDropdown(
                        options = providerModels,
                        selected = s.modelId,
                        onSelect = { viewModel.setModelId(it) },
                    )
                    providerModels.firstOrNull { it.modelId == s.modelId }?.let { model ->
                        if (model.description.isNotBlank()) Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (model.supportsVision) Text("支持图片理解", style = MaterialTheme.typography.labelSmall, color = MiaoPurple)
                    }
                }
            }

            if (isLoading) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = MiaoPurple)
                }
            }
            error?.let {
                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        Button(onClick = viewModel::refreshModels) { Text("重新加载模型列表") }
                    }
                }
            }

            // Temperature
            LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("温度 (Temperature)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("%.1f".format(s.temperature), style = MaterialTheme.typography.bodySmall, color = MiaoPurple)
                    }
                    Slider(
                        value = s.temperature,
                        onValueChange = { viewModel.setTemperature((it * 10).toInt() / 10f) },
                        valueRange = 0f..2f,
                        steps = 19,
                        colors = SliderDefaults.colors(thumbColor = MiaoPurple, activeTrackColor = MiaoPurple),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("精确", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("创意", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Max Tokens
            LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("最大 Token 数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${s.maxTokens}", style = MaterialTheme.typography.bodySmall, color = MiaoPurple)
                    }
                    Slider(
                        value = s.maxTokens.toFloat(),
                        onValueChange = { viewModel.setMaxTokens((it / 256).toInt() * 256) },
                        valueRange = 256f..8192f,
                        steps = 30,
                        colors = SliderDefaults.colors(thumbColor = MiaoPurple, activeTrackColor = MiaoPurple),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("256", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("8192", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Context turns
            LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("上下文轮数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${s.contextTurns}", style = MaterialTheme.typography.bodySmall, color = MiaoPurple)
                    }
                    Slider(
                        value = s.contextTurns.toFloat(),
                        onValueChange = { viewModel.setContextTurns(it.toInt()) },
                        valueRange = 1f..50f,
                        steps = 48,
                        colors = SliderDefaults.colors(thumbColor = MiaoPurple, activeTrackColor = MiaoPurple),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Streaming toggle
            LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("流式输出", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("实时显示生成内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = s.streaming,
                        onCheckedChange = { viewModel.setStreaming(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = MiaoPurple, checkedTrackColor = MiaoPurple.copy(alpha = 0.4f)),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    options: List<ModelDto>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.modelId == selected }?.displayName ?: selected

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = { onSelect(model.modelId); expanded = false },
                )
            }
        }
    }
}
