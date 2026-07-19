package com.hyx.miao.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnouncementReadStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readIds(): Set<String> =
        preferences.getStringSet(KEY_READ_IDS, emptySet()).orEmpty().toSet()

    fun markRead(id: String) {
        if (id.isBlank()) return
        preferences.edit {
            putStringSet(KEY_READ_IDS, readIds() + id)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "miao_announcements"
        const val KEY_READ_IDS = "read_announcement_ids"
    }
}
