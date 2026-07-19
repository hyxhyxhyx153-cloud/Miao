package com.hyx.miao.di

import android.content.Context
import com.hyx.miao.data.local.MiaoDatabase
import com.hyx.miao.data.local.dao.ConversationDao
import com.hyx.miao.data.local.dao.EmojiDao
import com.hyx.miao.data.local.dao.MemoryDao
import com.hyx.miao.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MiaoDatabase =
        MiaoDatabase.create(context)

    @Provides fun provideConversationDao(db: MiaoDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: MiaoDatabase): MessageDao = db.messageDao()
    @Provides fun provideMemoryDao(db: MiaoDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideEmojiDao(db: MiaoDatabase): EmojiDao = db.emojiDao()
}
