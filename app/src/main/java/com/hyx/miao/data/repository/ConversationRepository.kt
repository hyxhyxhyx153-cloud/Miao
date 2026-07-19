package com.hyx.miao.data.repository

import com.google.gson.Gson
import com.hyx.miao.data.local.dao.ConversationDao
import com.hyx.miao.data.local.dao.EmojiDao
import com.hyx.miao.data.local.dao.MessageDao
import com.hyx.miao.data.local.entity.ConversationEntity
import com.hyx.miao.data.local.entity.EmojiEntity
import com.hyx.miao.data.local.entity.MessageEntity
import com.hyx.miao.data.remote.SseParser
import com.hyx.miao.data.remote.api.ChatApi
import com.hyx.miao.data.remote.api.ConversationApi
import com.hyx.miao.data.remote.api.EmojiApi
import com.hyx.miao.data.remote.dto.ChatRequest
import com.hyx.miao.data.remote.dto.ConversationResponse
import com.hyx.miao.data.remote.dto.CreateConversationRequest
import com.hyx.miao.data.remote.dto.MessageResponse
import com.hyx.miao.data.remote.dto.SseEvent
import com.hyx.miao.data.remote.dto.SyncMessageRequest
import com.hyx.miao.data.remote.dto.SyncMessagesRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationApi: ConversationApi,
    private val chatApi: ChatApi,
    private val emojiApi: EmojiApi,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val emojiDao: EmojiDao,
) {
    private val gson = Gson()

    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.observeAll()
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeByConversation(conversationId)
    fun observeEmojis(): Flow<List<EmojiEntity>> = emojiDao.observeAll()
    suspend fun getConversationById(id: String): ConversationEntity? = conversationDao.getById(id)

    suspend fun sync(): Result<Unit> = runCatching {
        syncPendingMessages()
        val remote = conversationApi.getAll()
        if (remote.isEmpty()) conversationDao.deleteAll()
        else conversationDao.deleteMissing(remote.map { it.id })
        remote.forEach { conversationDao.upsert(it.toEntity()) }
    }

    suspend fun create(
        provider: String,
        modelId: String,
        personaId: String?,
        title: String?,
        temperature: Float = 0.8f,
        maxTokens: Int = 4096,
        contextTurns: Int = 20,
    ): Result<ConversationEntity> = runCatching {
        conversationApi.create(
            CreateConversationRequest(
                modelProvider = provider,
                modelId = modelId,
                personaId = personaId,
                title = title,
                temperature = temperature.coerceIn(0f, 2f),
                maxTokens = maxTokens.coerceIn(1, 32768),
                contextTurns = contextTurns.coerceIn(1, 100),
            )
        ).toEntity().also { conversationDao.upsert(it) }
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        conversationApi.delete(id)
        conversationDao.delete(id)
    }

    suspend fun setPinned(conversationId: String, pinned: Boolean): Result<Unit> = runCatching {
        conversationDao.upsert(
            conversationApi.update(conversationId, mapOf("isPinned" to pinned)).toEntity()
        )
    }

    suspend fun setModel(conversationId: String, modelId: String): Result<ConversationEntity> =
        runCatching {
            conversationApi.update(conversationId, mapOf("modelId" to modelId))
                .toEntity()
                .also { conversationDao.upsert(it) }
        }

    suspend fun setAvatar(
        conversationId: String,
        isUser: Boolean,
        avatarUrl: String?,
    ): Result<ConversationEntity> = runCatching {
        conversationApi.update(conversationId, avatarUpdateBody(isUser, avatarUrl))
            .toEntity()
            .also { conversationDao.upsert(it) }
    }

    suspend fun markRead(conversationId: String) {
        conversationDao.markRead(conversationId)
        runCatching { conversationApi.update(conversationId, mapOf("isRead" to true)) }
    }

    suspend fun loadMessages(conversationId: String): Result<Unit> = runCatching {
        cacheRemoteMessages(conversationId, fetchRemoteMessages(conversationId))
    }

    suspend fun reconcileRemoteReply(conversationId: String, clientId: String): Result<Boolean> = runCatching {
        val remoteMessages = fetchRemoteMessages(conversationId)
        val hasReply = clientId in successfulReplyClientIds(remoteMessages)
        cacheRemoteMessages(conversationId, remoteMessages)
        hasReply
    }

    suspend fun prepareRegeneration(message: MessageEntity): Result<MessageEntity> = runCatching {
        val remoteMessages = fetchRemoteMessages(message.conversationId)
        cacheRemoteMessages(message.conversationId, remoteMessages)
        val targetId = message.serverId ?: message.id
        messageDao.getById(targetId) ?: error("消息不存在")
    }

    suspend fun reconcileRegeneratedMessage(baseline: MessageEntity): Result<Boolean> = runCatching {
        val remoteMessages = fetchRemoteMessages(baseline.conversationId)
        val targetId = baseline.serverId ?: baseline.id
        val remote = remoteMessages.firstOrNull { it.id == targetId }
        val regenerated = remote != null &&
            remote.role == "assistant" &&
            !remote.isError &&
            (
                parseServerTime(remote.createdAt) > baseline.createdAt ||
                    remote.content.orEmpty() != baseline.content ||
                    remote.emotion != baseline.emotion ||
                    remote.actionText != baseline.actionText
                )
        cacheRemoteMessages(baseline.conversationId, remoteMessages)
        regenerated
    }

    private suspend fun fetchRemoteMessages(conversationId: String): List<MessageResponse> = buildList {
        var offset = 0
        do {
            val page = conversationApi.getMessages(conversationId, limit = 100, offset = offset)
            addAll(page)
            offset += page.size
        } while (page.size == 100)
    }

    private suspend fun cacheRemoteMessages(
        conversationId: String,
        remoteMessages: List<MessageResponse>,
    ) {
        val answeredClientIds = successfulReplyClientIds(remoteMessages)
        if (answeredClientIds.isNotEmpty()) {
            messageDao.deleteAnsweredErrorMessages(conversationId, answeredClientIds.toList())
        }
        messageDao.deleteTemporaryAssistantMessages(conversationId)
        remoteMessages.forEach { dto ->
            dto.clientId?.let { clientId ->
                messageDao.getByClientId(clientId)?.takeIf { it.id != dto.id }?.let {
                    messageDao.deleteById(it.id)
                }
            }
            messageDao.insert(dto.toEntity())
        }
        markRead(conversationId)
    }

    fun chat(
        conversationId: String,
        content: String,
        mediaUrls: List<String>?,
        contextClear: Boolean,
        clientId: String,
        emojiId: String? = null,
    ): Flow<SseEvent> = flow {
        chatApi.chat(
            conversationId,
            buildChatRequest(content, mediaUrls, contextClear, clientId, emojiId),
        ).use { body ->
            SseParser.parse(body).collect { emit(it) }
        }
    }

    suspend fun saveUserMessage(
        conversationId: String,
        content: String,
        mediaUrls: List<String>? = null,
        emojiId: String? = null,
    ): MessageEntity {
        val id = UUID.randomUUID().toString()
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            role = "user",
            content = content,
            contentType = if (mediaUrls.isNullOrEmpty()) "text" else if (emojiId == null) "image" else "emoji",
            mediaUrls = encodeUrls(mediaUrls),
            emojiId = emojiId,
            clientId = id,
            isSynced = false,
        ).also {
            messageDao.insert(it)
            conversationDao.updateLastMessage(conversationId, content.ifBlank { "[图片]" }, it.createdAt)
        }
    }

    suspend fun saveAiMessage(
        conversationId: String,
        content: String,
        emotion: String?,
        actionText: String?,
        emojiId: String?,
        id: String = UUID.randomUUID().toString(),
        synced: Boolean = false,
    ): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = "assistant",
        content = content,
        emotion = emotion,
        actionText = actionText,
        emojiId = emojiId,
        serverId = id.takeIf { synced },
        isSynced = synced,
    ).also {
        messageDao.insert(it)
        conversationDao.updateLastMessage(conversationId, content.take(256), it.createdAt)
    }

    suspend fun saveGeneratedImage(message: MessageResponse) {
        val entity = message.toEntity()
        messageDao.insert(entity)
        conversationDao.updateLastMessage(
            entity.conversationId,
            entity.content.ifBlank { "[图片]" }.take(256),
            entity.createdAt,
        )
    }

    suspend fun saveEmojiMessage(conversationId: String, emojiUrl: String, emojiId: String?) {
        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = "",
                contentType = "emoji",
                emojiId = emojiId,
                mediaUrls = encodeUrls(listOf(emojiUrl)),
                // This is only an offline fallback when the post-stream reload
                // failed. Keep it temporary so the server-persisted emoji can
                // replace it on the next successful reconciliation.
                isSynced = false,
            )
        )
    }

    suspend fun saveErrorMessage(
        conversationId: String,
        content: String,
        replyToClientId: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                role = "assistant",
                content = content,
                isError = true,
                replyToClientId = replyToClientId,
            )
        )
        conversationDao.updateLastMessage(conversationId, content, System.currentTimeMillis())
        return id
    }

    suspend fun saveSystemNotice(conversationId: String, content: String) {
        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "system",
                content = content,
                contentType = "system",
                isSynced = true,
            )
        )
    }

    suspend fun markMessageSynced(localId: String, serverId: String? = null) {
        if (serverId == null) messageDao.markSynced(localId) else messageDao.markSynced(localId, serverId)
    }

    suspend fun deleteMessage(localId: String): Result<Unit> = runCatching {
        val message = messageDao.getById(localId) ?: return@runCatching
        if (message.isSynced || message.serverId != null) {
            chatApi.deleteMessage(message.conversationId, message.serverId ?: message.id)
        }
        messageDao.deleteById(localId)
    }

    suspend fun previousUserMessage(message: MessageEntity): MessageEntity? =
        messageDao.getPreviousUserMessage(message.conversationId, message.createdAt)

    fun regenerate(localId: String): Flow<SseEvent> = flow {
        val message = messageDao.getById(localId) ?: error("消息不存在")
        chatApi.regenerate(message.conversationId, message.serverId ?: message.id).use { body ->
            SseParser.parse(body).collect { emit(it) }
        }
    }

    suspend fun recallMessage(localId: String): Result<Unit> = runCatching {
        val local = messageDao.getById(localId) ?: error("消息不存在")
        val remote = conversationApi.recallMessage(local.conversationId, local.serverId ?: local.id)
        messageDao.insert(
            local.copy(
                content = remote.content ?: "你撤回了一条消息",
                contentType = "system",
                isRecalled = true,
                serverId = remote.id,
                isSynced = true,
            )
        )
    }

    suspend fun addMessageToMemory(localId: String): Result<Unit> = runCatching {
        val message = messageDao.getById(localId) ?: error("消息不存在")
        chatApi.addToMemory(message.conversationId, message.serverId ?: message.id)
    }

    suspend fun syncEmojis(): Result<Unit> = runCatching {
        emojiDao.upsertAll(
            emojiApi.sync().emojis.map { dto ->
                EmojiEntity(
                    id = dto.id,
                    filename = dto.filename,
                    emotionTag = dto.emotionTag,
                    description = dto.description,
                    sceneKeywords = gson.toJson(dto.sceneKeywords),
                    url = dto.url,
                    thumbUrl = dto.thumbUrl,
                    sendCount = dto.sendCount,
                    isActive = dto.isActive,
                )
            }
        )
    }

    suspend fun incrementEmojiSendCount(id: String) = emojiDao.incrementSendCount(id)

    suspend fun syncPendingMessages() {
        messageDao.getUnsynced()
            .filter { it.role == "user" }
            .groupBy { it.conversationId }
            .forEach { (conversationId, messages) ->
                runCatching {
                    conversationApi.syncMessages(
                        conversationId,
                        SyncMessagesRequest(
                            messages.map { message ->
                                SyncMessageRequest(
                                    clientId = message.clientId ?: message.id,
                                    content = message.content,
                                    contentType = message.contentType,
                                    mediaUrls = decodeUrls(message.mediaUrls).takeUnless { message.emojiId != null },
                                    emojiId = message.emojiId,
                                )
                            }
                        )
                    ).synced.forEach { synced ->
                        messageDao.getByClientId(synced.clientId)?.let { local ->
                            messageDao.markSynced(local.id, synced.serverId)
                        }
                    }
                }
            }
    }

    fun decodeUrls(value: String?): List<String>? {
        if (value.isNullOrBlank()) return null
        return runCatching { gson.fromJson(value, Array<String>::class.java).toList() }
            .getOrElse { listOf(value) }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
    }

    private fun encodeUrls(urls: List<String>?): String? =
        urls?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let(gson::toJson)

    private fun ConversationResponse.toEntity() = ConversationEntity(
        id = id,
        title = title ?: "新会话",
        modelProvider = modelProvider,
        modelId = modelId,
        personaId = personaId,
        userAvatarUrl = userAvatarUrl,
        aiAvatarUrl = aiAvatarUrl,
        temperature = temperature,
        maxTokens = maxTokens,
        contextTurns = contextTurns,
        isPinned = isPinned,
        unreadCount = unreadCount,
        isWechat = isWechat,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = parseServerTime(lastMessageAt),
        createdAt = parseServerTime(createdAt).takeIf { it > 0 } ?: System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    private fun MessageResponse.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content.orEmpty(),
        contentType = contentType,
        mediaUrls = encodeUrls(mediaUrls),
        emojiId = emojiId,
        emotion = emotion,
        actionText = actionText,
        source = source ?: "app",
        isError = isError,
        isRecalled = isRecalled,
        clientId = clientId,
        replyToClientId = replyToClientId,
        serverId = id,
        isSynced = true,
        createdAt = parseServerTime(createdAt).takeIf { it > 0 } ?: System.currentTimeMillis(),
    )

    private fun parseServerTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        value.toLongOrNull()?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        } ?: 0L
    }
}

internal fun avatarUpdateBody(isUser: Boolean, avatarUrl: String?): Map<String, Any?> {
    val key = if (isUser) "userAvatarUrl" else "aiAvatarUrl"
    // Default Gson omits null map values. The API treats an empty string as an
    // explicit clear, so keep the field present when restoring the default avatar.
    return mapOf(key to avatarUrl.orEmpty())
}

internal fun buildChatRequest(
    content: String,
    mediaUrls: List<String>?,
    contextClear: Boolean,
    clientId: String,
    emojiId: String?,
) = ChatRequest(
    content = content,
    mediaUrls = mediaUrls?.takeIf { it.isNotEmpty() && emojiId == null },
    contextClear = contextClear,
    clientId = clientId,
    emojiId = emojiId,
)

internal fun successfulReplyClientIds(messages: List<MessageResponse>): Set<String> = buildSet {
    messages.forEach { message ->
        message.replyToClientId
            ?.takeIf { message.role == "assistant" && !message.isError && it.isNotBlank() }
            ?.let(::add)
    }
}
