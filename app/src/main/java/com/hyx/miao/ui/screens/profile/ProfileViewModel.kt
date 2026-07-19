package com.hyx.miao.ui.screens.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.remote.api.MediaApi
import com.hyx.miao.data.remote.api.UserApi
import com.hyx.miao.data.remote.api.WechatApi
import com.hyx.miao.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject

data class ProfileUiState(
    val username: String? = null,
    val email: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val dailyQuota: Int = 0,
    val quotaUsed: Int = 0,
    val wechatBound: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userApi: UserApi,
    private val mediaApi: MediaApi,
    private val wechatApi: WechatApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val user = userApi.getProfile()
                val binding = runCatching { wechatApi.getBindStatus() }.getOrNull()
                authRepository.updateCurrentUser(user)
                _uiState.update {
                    it.copy(
                        username = user.username,
                        email = user.email,
                        nickname = user.nickname,
                        avatarUrl = user.avatarUrl,
                        dailyQuota = user.dailyQuota,
                        quotaUsed = user.quotaUsed,
                        wechatBound = binding?.bound == true,
                        isLoading = false,
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "资料刷新失败") }
            }
        }
    }

    fun updateNickname(nickname: String) {
        val clean = nickname.trim()
        if (clean.length !in 1..32) {
            _uiState.update { it.copy(error = "昵称长度需为 1-32 个字符") }
            return
        }
        saveProfile(mapOf("nickname" to clean), "昵称已更新")
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            runCatching {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选图片")
                require(bytes.size <= 10 * 1024 * 1024) { "头像图片不能超过 10 MB" }
                val extension = when (mime) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                val body = bytes.toRequestBody(mime.toMediaType())
                val part = MultipartBody.Part.createFormData("file", "avatar-${UUID.randomUUID()}.$extension", body)
                val upload = mediaApi.upload(part)
                val url = upload["file_url"] ?: upload["fileUrl"] ?: error("服务器未返回头像地址")
                val user = userApi.updateProfile(mapOf("avatarUrl" to url))
                authRepository.updateCurrentUser(user)
                user
            }.fold(
                onSuccess = { user ->
                    _uiState.update { it.copy(avatarUrl = user.avatarUrl, isSaving = false, message = "头像已更新") }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSaving = false, error = error.message ?: "头像上传失败") }
                },
            )
        }
    }

    private fun saveProfile(body: Map<String, Any?>, successMessage: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            runCatching { userApi.updateProfile(body) }.fold(
                onSuccess = { user ->
                    authRepository.updateCurrentUser(user)
                    _uiState.update {
                        it.copy(nickname = user.nickname, avatarUrl = user.avatarUrl, isSaving = false, message = successMessage)
                    }
                },
                onFailure = { error -> _uiState.update { it.copy(isSaving = false, error = error.message ?: "资料保存失败") } },
            )
        }
    }

    suspend fun logout() = authRepository.logout()

    fun clearNotice() = _uiState.update { it.copy(error = null, message = null) }
}
