package com.hyx.miao.data.remote.api

import com.hyx.miao.data.remote.dto.AuthResponse
import com.hyx.miao.data.remote.dto.ForgotPasswordRequest
import com.hyx.miao.data.remote.dto.ForgotPasswordResponse
import com.hyx.miao.data.remote.dto.LoginRequest
import com.hyx.miao.data.remote.dto.RefreshRequest
import com.hyx.miao.data.remote.dto.RegisterRequest
import com.hyx.miao.data.remote.dto.ResetPasswordRequest
import com.hyx.miao.data.remote.dto.SuccessResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): AuthResponse

    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body req: RefreshRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout()

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ForgotPasswordResponse

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): SuccessResponse
}
