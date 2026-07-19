package com.hyx.miao.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var volatileAccessToken: String? = null
    private var volatileRefreshToken: String? = null

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "miao_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var accessToken: String?
        get() = volatileAccessToken ?: prefs.getString(KEY_ACCESS, null)
        set(value) {
            volatileAccessToken = value
            if (isPersistentSession) prefs.edit().putString(KEY_ACCESS, value).apply()
        }

    var refreshToken: String?
        get() = volatileRefreshToken ?: prefs.getString(KEY_REFRESH, null)
        set(value) {
            volatileRefreshToken = value
            if (isPersistentSession) prefs.edit().putString(KEY_REFRESH, value).apply()
        }

    val isPersistentSession: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)

    fun saveSession(accessToken: String, refreshToken: String, rememberMe: Boolean) {
        volatileAccessToken = accessToken
        volatileRefreshToken = refreshToken
        if (rememberMe) {
            prefs.edit()
                .putString(KEY_ACCESS, accessToken)
                .putString(KEY_REFRESH, refreshToken)
                .putBoolean(KEY_REMEMBER, true)
                .apply()
        } else {
            prefs.edit().clear().apply()
        }
    }

    fun clear() {
        volatileAccessToken = null
        volatileRefreshToken = null
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_REMEMBER = "remember_me"
    }
}
