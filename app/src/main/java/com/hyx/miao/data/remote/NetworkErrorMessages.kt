package com.hyx.miao.data.remote

import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkErrorMessages @Inject constructor(
    private val endpointProvider: ApiEndpointProvider,
) {
    fun from(error: Throwable, fallback: String): String {
        val cause = error.causes().firstOrNull { candidate ->
            candidate is UnknownHostException ||
                candidate is ConnectException ||
                candidate is NoRouteToHostException ||
                candidate is SocketTimeoutException ||
                candidate is IOException
        }
        return when (cause) {
            is UnknownHostException -> connectionHint("无法解析服务器地址")
            is ConnectException, is NoRouteToHostException -> connectionHint("无法连接局域网服务器")
            is SocketTimeoutException -> connectionHint("连接服务器超时")
            is IOException -> connectionHint("与服务器的连接已中断")
            else -> if (error is HttpException) httpMessage(error, fallback) else fallback
        }
    }

    private fun connectionHint(reason: String): String =
        "$reason（${endpointProvider.displayAddress}）。请确认服务已启动，且手机和电脑连接同一局域网；局域网没有互联网也可使用。"

    private fun httpMessage(error: HttpException, fallback: String): String {
        val serverMessage = runCatching {
            val raw = error.response()?.errorBody()?.string().orEmpty()
            JSONObject(raw).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return serverMessage ?: "$fallback（${error.code()}）"
    }

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
}
