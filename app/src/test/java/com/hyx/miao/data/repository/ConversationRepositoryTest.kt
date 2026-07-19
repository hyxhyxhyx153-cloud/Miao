package com.hyx.miao.data.repository

import com.google.gson.Gson
import com.hyx.miao.data.remote.dto.MessageResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRepositoryTest {
    @Test
    fun avatarUpdateBody_keepsClearRequestInJson() {
        val body = avatarUpdateBody(isUser = true, avatarUrl = null)

        assertEquals("", body["userAvatarUrl"])
        assertEquals("{\"userAvatarUrl\":\"\"}", Gson().toJson(body))
    }

    @Test
    fun avatarUpdateBody_usesAiAvatarField() {
        val body = avatarUpdateBody(isUser = false, avatarUrl = "https://example.com/ai.png")

        assertEquals(
            mapOf("aiAvatarUrl" to "https://example.com/ai.png"),
            body,
        )
    }

    @Test
    fun chatRequest_includesEmojiIdentityForServerSideContext() {
        val json = Gson().toJson(
            buildChatRequest(
                content = "小猫开心地挥手",
                mediaUrls = listOf("https://example.com/emoji.gif"),
                contextClear = false,
                clientId = "client-message-id",
                emojiId = "emoji-id",
            ),
        )

        assertEquals(true, json.contains("\"emojiId\":\"emoji-id\""))
        assertEquals(false, json.contains("mediaUrls"))
    }

    @Test
    fun chatRequest_keepsMediaForARealImage() {
        val json = Gson().toJson(
            buildChatRequest(
                content = "看图",
                mediaUrls = listOf("https://example.com/image.png"),
                contextClear = false,
                clientId = "client-message-id",
                emojiId = null,
            ),
        )

        assertEquals(true, json.contains("\"mediaUrls\":[\"https://example.com/image.png\"]"))
        assertEquals(false, json.contains("emojiId"))
    }

    @Test
    fun successfulReplyClientIds_onlyUsesExplicitSuccessfulReplyLinks() {
        val messages = listOf(
            message(id = "user-1", role = "user", clientId = "client-1"),
            message(id = "assistant-1", role = "assistant", replyToClientId = "client-1"),
            message(id = "user-2", role = "user", clientId = "client-2"),
            message(
                id = "assistant-error",
                role = "assistant",
                isError = true,
                replyToClientId = "client-2",
            ),
            message(id = "legacy-assistant", role = "assistant"),
        )

        assertEquals(setOf("client-1"), successfulReplyClientIds(messages))
    }

    private fun message(
        id: String,
        role: String,
        clientId: String? = null,
        isError: Boolean = false,
        replyToClientId: String? = null,
    ) = MessageResponse(
        id = id,
        conversationId = "conversation",
        role = role,
        content = id,
        mediaUrls = null,
        emojiId = null,
        emotion = null,
        actionText = null,
        source = "app",
        isError = isError,
        clientId = clientId,
        replyToClientId = replyToClientId,
        createdAt = null,
    )
}
