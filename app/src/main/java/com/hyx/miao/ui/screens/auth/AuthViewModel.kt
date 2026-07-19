package com.hyx.miao.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.remote.NetworkErrorMessages
import com.hyx.miao.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false,
    val message: String? = null,
    val resetToken: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val networkErrorMessages: NetworkErrorMessages,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state = _state.asStateFlow()

    val isLoggedIn: Boolean get() = authRepository.isLoggedIn

    fun login(account: String, password: String, rememberMe: Boolean) {
        execute(
            operation = { authRepository.login(account.trim(), password, rememberMe) },
            fallback = "登录失败，请稍后重试",
        )
    }

    fun register(username: String, email: String, password: String) {
        execute(
            operation = { authRepository.register(username.trim(), email.trim(), password) },
            fallback = "注册失败，请稍后重试",
        )
    }

    fun forgotPassword(email: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            authRepository.forgotPassword(email).fold(
                onSuccess = { response ->
                    val message = if (response.emailSent == true) {
                        "重置邮件已发送，请在 30 分钟内完成操作"
                    } else {
                        "重置请求已受理"
                    }
                    _state.value = AuthUiState(
                        isSuccess = true,
                        message = message,
                        resetToken = response.resetToken,
                    )
                },
                onFailure = {
                    _state.value = AuthUiState(error = networkErrorMessages.from(it, "发送重置邮件失败"))
                },
            )
        }
    }

    fun resetPassword(token: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            authRepository.resetPassword(token, password).fold(
                onSuccess = {
                    _state.value = AuthUiState(
                        isSuccess = true,
                        message = "密码已重置，请使用新密码登录",
                    )
                },
                onFailure = {
                    _state.value = AuthUiState(error = networkErrorMessages.from(it, "密码重置失败"))
                },
            )
        }
    }

    private fun execute(
        operation: suspend () -> Result<*>,
        fallback: String,
    ) {
        viewModelScope.launch {
            _state.value = AuthUiState(isLoading = true)
            operation().fold(
                onSuccess = { _state.value = AuthUiState(isSuccess = true) },
                onFailure = {
                    _state.value = AuthUiState(error = networkErrorMessages.from(it, fallback))
                },
            )
        }
    }

    fun clearNotice() = _state.update { it.copy(error = null, message = null) }
}
