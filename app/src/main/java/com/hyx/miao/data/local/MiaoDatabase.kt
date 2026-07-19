package com.hyx.miao.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hyx.miao.data.local.dao.ConversationDao
import com.hyx.miao.data.local.dao.EmojiDao
import com.hyx.miao.data.local.dao.MemoryDao
import com.hyx.miao.data.local.dao.MessageDao
import com.hyx.miao.data.local.entity.ConversationEntity
import com.hyx.miao.data.local.entity.EmojiEntity
import com.hyx.miao.data.local.entity.MemoryEntity
import com.hyx.miao.data.local.entity.MessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        EmojiEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class MiaoDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun emojiDao(): EmojiDao

    companion object {
        private const val DATABASE_NAME = "miao.db"

        fun create(context: Context): MiaoDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MiaoDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN unreadCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN isWechat INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastMessagePreview TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN isRecalled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN clientId TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN temperature REAL NOT NULL DEFAULT 0.8")
                db.execSQL("ALTER TABLE conversations ADD COLUMN maxTokens INTEGER NOT NULL DEFAULT 4096")
                db.execSQL("ALTER TABLE conversations ADD COLUMN contextTurns INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE messages ADD COLUMN serverId TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN userAvatarUrl TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN aiAvatarUrl TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToClientId TEXT")
            }
        }
    }
}
