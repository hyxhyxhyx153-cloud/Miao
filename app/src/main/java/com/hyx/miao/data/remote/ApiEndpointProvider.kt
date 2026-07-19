package com.hyx.miao.data.remote

import android.os.Build
import com.hyx.miao.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiEndpointProvider @Inject constructor() {
    val baseUrl: HttpUrl = ApiEndpointSelector.select(
        hasCustomUrl = BuildConfig.API_BASE_URL_CUSTOM,
        customUrl = BuildConfig.API_BASE_URL,
        emulatorUrl = BuildConfig.EMULATOR_API_BASE_URL,
        lanUrl = BuildConfig.LAN_API_BASE_URL,
        isEmulator = isAndroidEmulator(),
    ).toHttpUrl()

    val displayAddress: String = buildString {
        append(baseUrl.host)
        if (baseUrl.port != baseUrl.defaultPort()) append(':').append(baseUrl.port)
    }

    private fun HttpUrl.defaultPort(): Int = if (isHttps) 443 else 80
}

internal object ApiEndpointSelector {
    fun select(
        hasCustomUrl: Boolean,
        customUrl: String,
        emulatorUrl: String,
        lanUrl: String,
        isEmulator: Boolean,
    ): String {
        val selected = when {
            hasCustomUrl -> customUrl
            isEmulator -> emulatorUrl
            else -> lanUrl
        }.trim()
        require(selected.startsWith("http://") || selected.startsWith("https://")) {
            "Miao API 地址必须以 http:// 或 https:// 开头"
        }
        return if (selected.endsWith('/')) selected else "$selected/"
    }
}

private fun isAndroidEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val product = Build.PRODUCT.lowercase()
    return fingerprint.startsWith("generic") ||
        fingerprint.contains("emulator") ||
        model.contains("google_sdk") ||
        model.contains("emulator") ||
        model.contains("android sdk built for") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        product.contains("sdk_gphone")
}
