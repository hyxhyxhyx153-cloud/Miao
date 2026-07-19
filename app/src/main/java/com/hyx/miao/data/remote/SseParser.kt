package com.hyx.miao.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hyx.miao.data.remote.dto.MemoryItem
import com.hyx.miao.data.remote.dto.MessageResponse
import com.hyx.miao.data.remote.dto.SseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody
import java.io.IOException

object SseParser {
    private val gson = Gson()

    fun parse(body: ResponseBody): Flow<SseEvent> = flow {
        body.use { responseBody ->
            responseBody.byteStream().bufferedReader().use { reader ->
                val dataLines = mutableListOf<String>()
                var receivedTerminalEvent = false

                suspend fun emitPendingData(): Boolean {
                    val data = dataLines.joinToString("\n")
                    dataLines.clear()
                    if (data == "[DONE]") return true
                    emitData(data)?.let { event ->
                        emit(event)
                        if (event is SseEvent.Done) receivedTerminalEvent = true
                    }
                    return false
                }

                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (error: IOException) {
                        // Some HTTP tunnels drop the final chunk terminator even after the
                        // application-level `done` event. The reply is already committed on
                        // the server at that point, so a broken transport EOF is not a chat
                        // failure. Errors before `done` still propagate normally.
                        if (receivedTerminalEvent) break
                        throw error
                    }
                    if (line == null) {
                        if (emitPendingData()) receivedTerminalEvent = true
                        if (!receivedTerminalEvent) {
                            throw IOException("SSE stream ended before a terminal event")
                        }
                        break
                    }
                    if (line.isBlank()) {
                        if (emitPendingData()) {
                            receivedTerminalEvent = true
                            break
                        }
                    } else if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trimStart()
                        // `[DONE]` is the protocol terminator. Stop here instead of waiting
                        // for a clean TCP EOF that proxies are not required to preserve.
                        if (data == "[DONE]" && dataLines.isEmpty()) {
                            receivedTerminalEvent = true
                            break
                        }
                        dataLines += data
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun emitData(data: String): SseEvent? {
        if (data.isBlank()) return null
        return runCatching {
            val obj = gson.fromJson(data, JsonObject::class.java)
            when (obj.string("type")) {
                "thinking_start" -> SseEvent.ThinkingStart
                "memory" -> SseEvent.Memory(
                    count = obj.int("count"),
                    items = obj.getAsJsonArray("items")?.map { item ->
                        val value = item.asJsonObject
                        MemoryItem(value.string("id"), value.string("summary"))
                    }.orEmpty(),
                )
                "message_ack" -> SseEvent.MessageAck(
                    clientId = obj.string("client_id").ifBlank { obj.string("clientId") },
                    messageId = obj.string("message_id").ifBlank { obj.string("messageId") },
                )
                "context_cleared", "context_trimmed" ->
                    SseEvent.SystemNotice(obj.string("message").ifBlank { "上下文已更新" })
                "delta" -> SseEvent.Delta(obj.string("content"))
                "error" -> SseEvent.Error(obj.string("message").ifBlank { "请求失败，请稍后重试" })
                "image_generation_start" -> SseEvent.ImageGenerationStart(
                    obj.string("message").ifBlank { "正在准备绘制图片…" }
                )
                "image_generation_progress" -> SseEvent.ImageGenerationProgress(
                    obj.string("message").ifBlank { "正在绘制图片…" }
                )
                "image_generation_error" -> SseEvent.ImageGenerationError(
                    obj.string("message").ifBlank { "图片生成失败，请稍后重试" }
                )
                "image_generated" -> obj.getAsJsonObject("message")?.let { message ->
                    SseEvent.ImageGenerated(gson.fromJson(message, MessageResponse::class.java))
                }
                "quota" -> SseEvent.Quota(obj.int("quota_used"), obj.int("daily_quota"))
                "done" -> {
                    val usage = obj.getAsJsonObject("usage")
                    SseEvent.Done(
                        emotion = obj.nullableString("emotion"),
                        action = obj.nullableString("action"),
                        emojiId = obj.nullableString("emoji_id"),
                        inputTokens = usage?.int("input_tokens") ?: 0,
                        outputTokens = usage?.int("output_tokens") ?: 0,
                    )
                }
                "emoji" -> SseEvent.Emoji(obj.string("id"), obj.string("url"))
                else -> null
            }
        }.getOrNull()
    }

    private fun JsonObject.string(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.nullableString(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.int(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0
}
