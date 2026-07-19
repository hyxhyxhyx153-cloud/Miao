package com.hyx.miao.ui.screens.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.local.entity.EmojiEntity
import com.hyx.miao.data.local.entity.MessageEntity
import com.hyx.miao.data.remote.api.ModelApi
import com.hyx.miao.data.remote.api.ModelDto
import com.hyx.miao.data.remote.NetworkErrorMessages
import com.hyx.miao.data.remote.dto.MemoryItem
import com.hyx.miao.data.remote.dto.SseEvent
import com.hyx.miao.data.repository.AuthRepository
import com.hyx.miao.data.repository.ConversationRepository
import com.hyx.miao.data.repository.MediaRepository
import com.hyx.miao.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

data class ChatUiState(
    val isGenerating: Boolean = false,
    val isUploading: Boolean = false,
    val streamingText: String = "",
    val imageGenerationStatus: String? = null,
    val currentEmotion: String? = null,
    val pendingEmojiUrl: String? = null,
    val conversationTitle: String = "喵喵",
    val modelId: String = "",
    val userAvatarUrl: String? = null,
    val aiAvatarUrl: String? = null,
    val availableModels: List<ModelDto> = emptyList(),
    val modelsLoading: Boolean = false,
    val isChangingModel: Boolean = false,
    val avatarUploading: String? = null,
    val quotaUsed: Int = 0,
    val dailyQuota: Int = 0,
    val quotaExhaustedMessage: String = "今日配额已用完，请明天再来",
    val streamingEnabled: Boolean = true,
    val sendOnEnter: Boolean = false,
    val hapticFeedback: Boolean = true,
    val memories: List<MemoryItem> = emptyList(),
    val contextClearNext: Boolean = false,
    val notice: String? = null,
) {
    val quotaRemaining: Int get() = (dailyQuota - quotaUsed).coerceAtLeast(0)
    val quotaExhausted: Boolean get() = dailyQuota > 0 && quotaRemaining == 0
    val isBusy: Boolean get() = isGenerating || isUploading
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ConversationRepository,
    private val mediaRepository: MediaRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val modelApi: ModelApi,
    private val networkErrorMessages: NetworkErrorMessages,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private val activeConversationId = MutableStateFlow("")

    val messages = activeConversationId.flatMapLatest { id ->
        if (id.isBlank()) flowOf(emptyList()) else repository.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val emojis = repository.observeEmojis()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        streamingEnabled = settings.streaming,
                        sendOnEnter = settings.sendOnEnter,
                        hapticFeedback = settings.hapticFeedback,
                    )
                }
            }
        }
    }

    fun init(conversationId: String) {
        if (activeConversationId.value == conversationId) return
        activeConversationId.value = conversationId
        authRepository.currentUser?.let { user ->
            _uiState.update { it.copy(quotaUsed = user.quotaUsed, dailyQuota = user.dailyQuota) }
        }
        viewModelScope.launch { loadModels() }
        viewModelScope.launch {
            authRepository.refreshQuota().onSuccess { quota ->
                _uiState.update {
                    it.copy(
                        quotaUsed = quota.quotaUsed,
                        dailyQuota = quota.dailyQuota,
                        quotaExhaustedMessage = quota.quotaExhaustedMessage
                            ?: it.quotaExhaustedMessage,
                    )
                }
            }.onFailure {
                if (authRepository.currentUser == null) {
                    showNotice("配额状态暂时无法同步，将由服务器在发送时校验")
                }
            }
            repository.getConversationById(conversationId)?.let { conversation ->
                _uiState.update {
                    it.copy(
                        conversationTitle = conversation.title,
                        modelId = conversation.modelId,
                        userAvatarUrl = conversation.userAvatarUrl,
                        aiAvatarUrl = conversation.aiAvatarUrl,
                    )
                }
            }
            repository.syncPendingMessages()
            repository.loadMessages(conversationId).onFailure {
                showNotice("当前处于离线状态，已显示本地消息")
            }
            repository.syncEmojis()
        }
    }

    fun refreshModels() {
        viewModelScope.launch { loadModels() }
    }

    fun changeModel(model: ModelDto) {
        if (!model.isEnabled) {
            showNotice("该模型已停用，请选择其他模型")
            return
        }
        if (_uiState.value.isGenerating) {
            showNotice("请先停止当前回复，再切换模型")
            return
        }
        if (_uiState.value.modelId == model.modelId || _uiState.value.isChangingModel) return
        val conversationId = activeConversationId.value
        viewModelScope.launch {
            _uiState.update { it.copy(isChangingModel = true) }
            repository.setModel(conversationId, model.modelId)
                .onSuccess { conversation ->
                    if (activeConversationId.value == conversationId) {
                        _uiState.update { it.copy(modelId = conversation.modelId) }
                        showNotice("已切换到 ${model.displayName}")
                    }
                }
                .onFailure { showNotice(friendlyChatError(it)) }
            _uiState.update { it.copy(isChangingModel = false) }
        }
    }

    fun updateAvatar(isUser: Boolean, uri: Uri?) {
        if (_uiState.value.avatarUploading != null) return
        val target = if (isUser) "user" else "ai"
        val conversationId = activeConversationId.value
        viewModelScope.launch {
            _uiState.update { it.copy(avatarUploading = target) }
            try {
                val avatarUrl = uri?.let { mediaRepository.upload(it) }
                repository.setAvatar(conversationId, isUser, avatarUrl)
                    .onSuccess { conversation ->
                        if (activeConversationId.value == conversationId) {
                            _uiState.update {
                                it.copy(
                                    userAvatarUrl = conversation.userAvatarUrl,
                                    aiAvatarUrl = conversation.aiAvatarUrl,
                                )
                            }
                            showNotice(if (avatarUrl == null) "头像已恢复默认" else "头像已更新")
                        }
                    }
                    .onFailure { showNotice(friendlyChatError(it)) }
            } catch (error: Exception) {
                showNotice(friendlyChatError(error))
            } finally {
                _uiState.update { it.copy(avatarUploading = null) }
            }
        }
    }

    private suspend fun loadModels() {
        _uiState.update { it.copy(modelsLoading = true) }
        runCatching { modelApi.getModels(includeDisabled = true) }
            .onSuccess { models -> _uiState.update { it.copy(availableModels = models) } }
            .onFailure {
                if (_uiState.value.availableModels.isEmpty()) {
                    showNotice("模型列表加载失败，请稍后重试")
                }
            }
        _uiState.update { it.copy(modelsLoading = false) }
    }

    fun sendMessage(content: String, imageUris: List<Uri> = emptyList()) {
        if (content.isBlank() && imageUris.isEmpty()) return
        if (!canStartRequest()) return
        generateJob = viewModelScope.launch {
            val uploadedUrls = if (imageUris.isEmpty()) {
                null
            } else {
                _uiState.update { it.copy(isUploading = true) }
                try {
                    mediaRepository.uploadAll(imageUris)
                } catch (error: Exception) {
                    showNotice(networkErrorMessages.from(error, "图片上传失败"))
                    return@launch
                } finally {
                    _uiState.update { it.copy(isUploading = false) }
                }
            }
            val local = repository.saveUserMessage(activeConversationId.value, content, uploadedUrls)
            streamChat(local, content, uploadedUrls, _uiState.value.contextClearNext)
        }
    }

    fun sendEmoji(emoji: EmojiEntity) {
        if (!canStartRequest()) return
        generateJob = viewModelScope.launch {
            repository.incrementEmojiSendCount(emoji.id)
            val content = emoji.description.ifBlank { "[${emoji.emotionTag}表情]" }
            val local = repository.saveUserMessage(
                activeConversationId.value,
                content,
                listOf(emoji.url),
                emoji.id,
            )
            streamChat(local, content, listOf(emoji.url), _uiState.value.contextClearNext)
        }
    }

    fun retry(errorMessage: MessageEntity) {
        if (!canStartRequest()) return
        generateJob = viewModelScope.launch {
            val original = repository.previousUserMessage(errorMessage)
            if (original == null) {
                showNotice("找不到需要重试的原消息")
                return@launch
            }
            repository.deleteMessage(errorMessage.id)
            streamChat(
                original,
                original.content,
                repository.decodeUrls(original.mediaUrls),
                _uiState.value.contextClearNext,
            )
        }
    }

    private suspend fun streamChat(
        localUserMessage: MessageEntity,
        content: String,
        mediaUrls: List<String>?,
        contextClear: Boolean,
    ) {
        _uiState.update {
            it.copy(
                isGenerating = true,
                streamingText = "",
                imageGenerationStatus = null,
                pendingEmojiUrl = null,
                currentEmotion = null,
                memories = emptyList(),
                contextClearNext = false,
            )
        }
        var accumulated = ""
        var emotion: String? = null
        var action: String? = null
        var emojiId: String? = null
        var emojiUrl: String? = null
        var serverId: String? = null
        var serverFailure: String? = null
        var receivedDone = false
        var streamCompleted = false

        suspend fun persistCompletedResponse() {
            repository.markMessageSynced(localUserMessage.id, serverId)
            if (repository.loadMessages(activeConversationId.value).isFailure && accumulated.isNotBlank()) {
                repository.saveAiMessage(
                    activeConversationId.value,
                    accumulated,
                    emotion,
                    action,
                    emojiId,
                )
                emojiUrl?.let { repository.saveEmojiMessage(activeConversationId.value, it, emojiId) }
            }
            serverFailure?.let {
                repository.saveErrorMessage(
                    activeConversationId.value,
                    it,
                    localUserMessage.clientId ?: localUserMessage.id,
                )
            }
        }

        try {
            repository.chat(
                activeConversationId.value,
                content,
                mediaUrls,
                contextClear,
                localUserMessage.clientId ?: localUserMessage.id,
                localUserMessage.emojiId,
            ).collect { event ->
                when (event) {
                    is SseEvent.ThinkingStart -> Unit
                    is SseEvent.Memory -> _uiState.update { it.copy(memories = event.items) }
                    is SseEvent.MessageAck -> {
                        if (event.clientId == localUserMessage.clientId || event.clientId == localUserMessage.id) {
                            serverId = event.messageId
                            repository.markMessageSynced(localUserMessage.id, event.messageId)
                        }
                    }
                    is SseEvent.SystemNotice -> repository.saveSystemNotice(
                        activeConversationId.value,
                        event.message,
                    )
                    is SseEvent.Delta -> {
                        accumulated += event.content
                        if (_uiState.value.streamingEnabled) {
                            _uiState.update { it.copy(streamingText = accumulated) }
                        }
                    }
                    is SseEvent.ImageGenerationStart -> _uiState.update {
                        it.copy(imageGenerationStatus = event.message)
                    }
                    is SseEvent.ImageGenerationProgress -> _uiState.update {
                        it.copy(imageGenerationStatus = event.message)
                    }
                    is SseEvent.ImageGenerationError -> {
                        _uiState.update { it.copy(imageGenerationStatus = null) }
                        showNotice(event.message)
                    }
                    is SseEvent.ImageGenerated -> {
                        repository.saveGeneratedImage(event.message)
                        _uiState.update { it.copy(imageGenerationStatus = null) }
                    }
                    is SseEvent.Done -> {
                        receivedDone = true
                        emotion = event.emotion
                        action = event.action
                        emojiId = event.emojiId
                        _uiState.update { it.copy(currentEmotion = event.emotion) }
                    }
                    is SseEvent.Emoji -> {
                        emojiUrl = event.url
                        _uiState.update { it.copy(pendingEmojiUrl = event.url) }
                    }
                    is SseEvent.Error -> serverFailure = event.message
                    is SseEvent.Quota -> {
                        authRepository.updateQuota(event.quotaUsed, event.dailyQuota)
                        _uiState.update {
                            it.copy(quotaUsed = event.quotaUsed, dailyQuota = event.dailyQuota)
                        }
                    }
                }
            }
            streamCompleted = true
            persistCompletedResponse()
        } catch (cancelled: CancellationException) {
            if (!receivedDone && accumulated.isNotBlank()) {
                repository.saveAiMessage(activeConversationId.value, accumulated, emotion, action, null)
            }
            throw cancelled
        } catch (error: Exception) {
            if (receivedDone) {
                // The server persists the assistant reply before emitting `done`. A broken
                // trailing chunk must therefore reconcile as success, not create a false
                // error bubble.
                streamCompleted = true
                persistCompletedResponse()
            } else {
                val clientId = localUserMessage.clientId ?: localUserMessage.id
                val serverAlreadyReplied = repository.reconcileRemoteReply(
                    activeConversationId.value,
                    clientId,
                ).getOrDefault(false)
                if (serverAlreadyReplied) {
                    streamCompleted = true
                } else {
                    if (accumulated.isNotBlank()) {
                        repository.saveAiMessage(activeConversationId.value, accumulated, emotion, action, null)
                    }
                    repository.saveErrorMessage(
                        activeConversationId.value,
                        friendlyChatError(error),
                        clientId,
                    )
                }
            }
        } finally {
            if (!streamCompleted && serverId != null) repository.markMessageSynced(localUserMessage.id, serverId)
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    streamingText = "",
                    imageGenerationStatus = null,
                    pendingEmojiUrl = null,
                    currentEmotion = null,
                )
            }
        }
    }

    fun stopGeneration() {
        generateJob?.cancel()
        showNotice("已停止生成")
    }

    fun regenerate(message: MessageEntity) {
        if (!canStartRequest()) return
        generateJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingText = "",
                    imageGenerationStatus = null,
                    memories = emptyList(),
                )
            }
            var accumulated = ""
            var emotion: String? = null
            var action: String? = null
            var failure: String? = null
            var receivedDone = false
            val baseline = repository.prepareRegeneration(message).getOrNull()
            val targetMessage = baseline ?: message
            val conversationId = targetMessage.conversationId
            try {
                repository.regenerate(targetMessage.id).collect { event ->
                    when (event) {
                        is SseEvent.Delta -> {
                            accumulated += event.content
                            if (_uiState.value.streamingEnabled) {
                                _uiState.update { it.copy(streamingText = accumulated) }
                            }
                        }
                        is SseEvent.Done -> {
                            receivedDone = true
                            emotion = event.emotion
                            action = event.action
                        }
                        is SseEvent.Quota -> {
                            authRepository.updateQuota(event.quotaUsed, event.dailyQuota)
                            _uiState.update { it.copy(quotaUsed = event.quotaUsed, dailyQuota = event.dailyQuota) }
                        }
                        is SseEvent.Memory -> _uiState.update { it.copy(memories = event.items) }
                        is SseEvent.Error -> failure = event.message
                        is SseEvent.ImageGenerationStart -> _uiState.update {
                            it.copy(imageGenerationStatus = event.message)
                        }
                        is SseEvent.ImageGenerationProgress -> _uiState.update {
                            it.copy(imageGenerationStatus = event.message)
                        }
                        is SseEvent.ImageGenerationError -> {
                            _uiState.update { it.copy(imageGenerationStatus = null) }
                            showNotice(event.message)
                        }
                        is SseEvent.ImageGenerated -> {
                            repository.saveGeneratedImage(event.message)
                            _uiState.update { it.copy(imageGenerationStatus = null) }
                        }
                        is SseEvent.SystemNotice -> repository.saveSystemNotice(activeConversationId.value, event.message)
                        else -> Unit
                    }
                }
                if (receivedDone) {
                    if (repository.loadMessages(conversationId).isFailure && accumulated.isNotBlank()) {
                        repository.saveAiMessage(
                            conversationId,
                            accumulated,
                            emotion,
                            action,
                            null,
                            id = targetMessage.id,
                            synced = targetMessage.isSynced,
                        )
                    }
                } else if (failure == null) {
                    throw IOException("Regeneration stream ended before done")
                }
                failure?.let { repository.saveErrorMessage(conversationId, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                var serverRegenerated = false
                if (baseline != null) {
                    for (attempt in 0..2) {
                        serverRegenerated = repository.reconcileRegeneratedMessage(baseline)
                            .getOrDefault(false)
                        if (serverRegenerated) break
                        if (attempt < 2) delay(if (attempt == 0) 250 else 750)
                    }
                }
                if (!serverRegenerated) {
                    repository.saveErrorMessage(conversationId, friendlyChatError(error))
                }
            } finally {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        imageGenerationStatus = null,
                    )
                }
            }
        }
    }

    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch {
            repository.deleteMessage(message.id).onFailure { showNotice(friendlyChatError(it)) }
        }
    }

    fun recallMessage(message: MessageEntity) {
        viewModelScope.launch {
            repository.recallMessage(message.id)
                .onSuccess { showNotice("消息已撤回") }
                .onFailure { showNotice(it.message ?: "消息已超过两分钟撤回时限") }
        }
    }

    fun addToMemory(message: MessageEntity) {
        viewModelScope.launch {
            repository.addMessageToMemory(message.id)
                .onSuccess { showNotice("已添加到记忆") }
                .onFailure { showNotice(friendlyChatError(it)) }
        }
    }

    fun clearContextOnNextMessage() {
        _uiState.update { it.copy(contextClearNext = true, notice = "下一条消息将开启全新上下文") }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun canStartRequest(): Boolean {
        if (generateJob?.isCompleted == false) return false
        if (_uiState.value.isBusy) return false
        if (_uiState.value.quotaExhausted) {
            showNotice(_uiState.value.quotaExhaustedMessage)
            return false
        }
        return true
    }

    private fun showNotice(message: String) {
        _uiState.update { it.copy(notice = message) }
    }

    private fun friendlyChatError(error: Throwable): String {
        val fallback = if (error is HttpException) {
            when (error.code()) {
                400 -> "请求内容无效，请检查后重试"
                401 -> "登录状态已失效，请重新登录"
                403, 429 -> "今日消息配额已用完"
                404 -> "会话或消息不存在"
                503 -> "当前模型暂不可用，请更换模型"
                in 500..599 -> "服务暂时不可用，请稍后重试"
                else -> "请求失败（${error.code()}）"
            }
        } else {
            "发送失败，请稍后重试"
        }
        return networkErrorMessages.from(error, fallback)
    }
}
