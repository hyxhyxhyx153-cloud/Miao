package com.hyx.miao.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    @field:SerializedName("email") val email: String,
    @field:SerializedName("password") val password: String
)

data class RegisterRequest(
    @field:SerializedName("username") val username: String,
    @field:SerializedName("email") val email: String,
    @field:SerializedName("password") val password: String
)

data class AuthResponse(
    @field:SerializedName("accessToken") val accessToken: String,
    @field:SerializedName("refreshToken") val refreshToken: String,
    @field:SerializedName("user") val user: UserDto
)

data class RefreshRequest(
    @field:SerializedName("refreshToken") val refreshToken: String
)

data class ForgotPasswordRequest(
    @field:SerializedName("email") val email: String,
)

data class ForgotPasswordResponse(
    @field:SerializedName("success") val success: Boolean,
    @field:SerializedName("emailSent") val emailSent: Boolean? = null,
    @field:SerializedName("resetToken") val resetToken: String? = null,
)

data class ResetPasswordRequest(
    @field:SerializedName("token") val token: String,
    @field:SerializedName("password") val password: String,
)

data class SuccessResponse(
    @field:SerializedName("success") val success: Boolean,
)

data class UserDto(
    @field:SerializedName("id") val id: String,
    @field:SerializedName("username") val username: String,
    @field:SerializedName("email") val email: String,
    @field:SerializedName("nickname") val nickname: String?,
    @field:SerializedName("avatarUrl") val avatarUrl: String?,
    @field:SerializedName("dailyQuota") val dailyQuota: Int,
    @field:SerializedName("quotaUsed") val quotaUsed: Int
)
