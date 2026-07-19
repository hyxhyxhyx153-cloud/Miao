package com.hyx.miao.di

import com.hyx.miao.BuildConfig
import com.hyx.miao.data.remote.AuthInterceptor
import com.hyx.miao.data.remote.api.AuthApi
import com.hyx.miao.data.remote.api.AppApi
import com.hyx.miao.data.remote.api.ChatApi
import com.hyx.miao.data.remote.api.ConversationApi
import com.hyx.miao.data.remote.api.EmojiApi
import com.hyx.miao.data.remote.api.MediaApi
import com.hyx.miao.data.remote.api.MemoryApi
import com.hyx.miao.data.remote.api.PersonaApi
import com.hyx.miao.data.remote.api.UserApi
import com.hyx.miao.data.remote.api.ModelApi
import com.hyx.miao.data.remote.api.WechatApi
import com.hyx.miao.data.remote.ApiEndpointProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    redactHeader("Authorization")
                    level = if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                }
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        endpointProvider: ApiEndpointProvider,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(endpointProvider.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides @Singleton
    fun provideAppApi(retrofit: Retrofit): AppApi = retrofit.create(AppApi::class.java)

    @Provides @Singleton
    fun provideConversationApi(retrofit: Retrofit): ConversationApi = retrofit.create(ConversationApi::class.java)

    @Provides @Singleton
    fun provideChatApi(retrofit: Retrofit): ChatApi = retrofit.create(ChatApi::class.java)

    @Provides @Singleton
    fun provideMemoryApi(retrofit: Retrofit): MemoryApi = retrofit.create(MemoryApi::class.java)

    @Provides @Singleton
    fun provideEmojiApi(retrofit: Retrofit): EmojiApi = retrofit.create(EmojiApi::class.java)

    @Provides @Singleton
    fun provideMediaApi(retrofit: Retrofit): MediaApi = retrofit.create(MediaApi::class.java)

    @Provides @Singleton
    fun providePersonaApi(retrofit: Retrofit): PersonaApi = retrofit.create(PersonaApi::class.java)

    @Provides @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)

    @Provides @Singleton
    fun provideModelApi(retrofit: Retrofit): ModelApi = retrofit.create(ModelApi::class.java)

    @Provides @Singleton
    fun provideWechatApi(retrofit: Retrofit): WechatApi = retrofit.create(WechatApi::class.java)
}
