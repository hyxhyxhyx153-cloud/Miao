package com.hyx.miao.ui.screens.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.remote.api.ModelApi
import com.hyx.miao.data.remote.api.PersonaApi
import com.hyx.miao.data.repository.ConversationRepository
import com.hyx.miao.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationOptionsState(
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val models: List<ModelOption> = emptyList(),
    val personas: List<PersonaOption> = emptyList(),
    val selectedModelId: String = "",
    val selectedPersonaId: String = "00000000-0000-0000-0000-000000000001",
    val temperature: Float = 0.8f,
    val maxTokens: Int = 4096,
    val contextTurns: Int = 20,
)

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ConversationRepository,
    private val modelApi: ModelApi,
    private val personaApi: PersonaApi,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _options = MutableStateFlow(ConversationOptionsState())
    val options = _options.asStateFlow()

    val conversations = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
        loadOptions()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            repository.sync().onFailure { _error.value = "同步会话失败，正在使用本地数据" }
            _isRefreshing.value = false
        }
    }

    private fun loadOptions() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val models = runCatching { modelApi.getModels() }.getOrDefault(emptyList()).map {
                ModelOption(
                    provider = it.provider,
                    modelId = it.modelId,
                    displayName = it.displayName,
                    emoji = providerEmoji(it.provider),
                    description = it.description,
                    supportsVision = it.supportsVision,
                    isAvailable = it.isEnabled,
                )
            }.ifEmpty { fallbackModels }
            val personas = runCatching { personaApi.getAll() }.getOrDefault(emptyList()).map {
                PersonaOption(it.id, it.name, it.description.orEmpty())
            }.ifEmpty { fallbackPersonas }
            _options.value = ConversationOptionsState(
                isLoading = false,
                models = models,
                personas = personas,
                selectedModelId = settings.modelId.takeIf { id ->
                    models.any { it.modelId == id && it.isAvailable }
                } ?: models.firstOrNull { it.isAvailable }?.modelId.orEmpty(),
                selectedPersonaId = personas.first().id,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                contextTurns = settings.contextTurns,
            )
        }
    }

    fun createConversation(
        model: ModelOption,
        persona: PersonaOption,
        title: String?,
        onSuccess: (String) -> Unit,
    ) {
        val config = _options.value
        if (config.isCreating || !model.isAvailable) return
        _options.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val result = repository.create(
                provider = model.provider,
                modelId = model.modelId,
                personaId = persona.id,
                title = title,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                contextTurns = config.contextTurns,
            )
            _options.update { it.copy(isCreating = false) }
            result.onSuccess { onSuccess(it.id) }
                .onFailure { _error.value = it.message ?: "创建会话失败，请检查网络后重试" }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id).onFailure { _error.value = it.message ?: "删除会话失败" }
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            repository.setPinned(id, pinned).onFailure { _error.value = it.message ?: "置顶操作失败" }
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            runCatching { repository.markRead(id) }
                .onFailure { _error.value = it.message ?: "标记已读失败" }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun providerEmoji(provider: String) = when (provider.lowercase()) {
        "anthropic" -> "A"
        "openai" -> "O"
        "deepseek" -> "D"
        "qwen" -> "Q"
        "zhipu" -> "Z"
        else -> "AI"
    }

    companion object {
        private val fallbackModels = listOf(
            ModelOption("anthropic", "claude-haiku-4-5-20251001", "Claude Haiku", "A", "快速、稳定的日常对话", true, true),
            ModelOption("openai", "gpt-4o", "GPT-4o", "O", "支持图片理解", true, true),
            ModelOption("deepseek", "deepseek-chat", "DeepSeek", "D", "中文与推理能力出色", false, true),
        )
        private val fallbackPersonas = listOf(
            PersonaOption("00000000-0000-0000-0000-000000000001", "喵喵", "活泼可爱的猫娘，温柔陪伴"),
            PersonaOption("00000000-0000-0000-0000-000000000002", "知识助手", "专注知识解答"),
            PersonaOption("00000000-0000-0000-0000-000000000003", "写作助手", "文字润色与创意写作"),
            PersonaOption("00000000-0000-0000-0000-000000000004", "代码助手", "编程问题与代码实现"),
        )
    }
}
