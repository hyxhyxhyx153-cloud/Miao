package com.hyx.miao.data.repository

import android.content.Context
import android.net.Uri
import com.hyx.miao.data.remote.api.MediaApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaApi: MediaApi,
) {
    suspend fun uploadReferenceImages(uris: List<Uri>): List<String> = coroutineScope {
        require(uris.size <= 3) { "人格参考图最多 3 张" }
        val allowedTypes = setOf("image/jpeg", "image/png", "image/webp")
        uris.forEach { uri ->
            val mimeType = context.contentResolver.getType(uri)?.lowercase()
            require(mimeType in allowedTypes) { "人格参考图仅支持 JPEG、PNG 或 WebP" }
        }
        uris.map { uri -> async { upload(uri) } }.awaitAll()
    }

    suspend fun uploadAll(uris: List<Uri>): List<String> = coroutineScope {
        require(uris.size <= 9) { "每次最多选择 9 张图片" }
        uris.map { uri -> async { upload(uri) } }.awaitAll()
    }

    suspend fun upload(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选图片")
        require(bytes.size <= 10 * 1024 * 1024) { "单张图片不能超过 10MB" }
        val mimeType = resolver.getType(uri) ?: "image/jpeg"
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            "仅支持 JPEG、PNG、WebP 或 GIF 图片"
        }
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val part = MultipartBody.Part.createFormData(
            "file",
            "image_${System.currentTimeMillis()}.$extension",
            bytes.toRequestBody(mimeType.toMediaType()),
        )
        mediaApi.upload(part)["file_url"]?.takeIf { it.isNotBlank() }
            ?: error("服务器未返回图片地址")
    }
}
