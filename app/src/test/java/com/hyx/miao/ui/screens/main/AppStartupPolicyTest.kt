package com.hyx.miao.ui.screens.main

import com.hyx.miao.data.remote.api.AppAnnouncement
import com.hyx.miao.data.remote.api.AppVersionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class AppStartupPolicyTest {
    @Test
    fun `update availability only uses version code`() {
        assertFalse(
            isUpdateAvailable(
                AppVersionResponse(latestVersion = "99.0", latestVersionCode = 2),
                currentVersionCode = 2,
            ),
        )
        assertTrue(
            isUpdateAvailable(
                AppVersionResponse(latestVersion = "1.0", latestVersionCode = 3),
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `minimum supported version makes update mandatory`() {
        assertTrue(
            requiresForceUpdate(
                AppVersionResponse(
                    latestVersionCode = 4,
                    minSupportedVersionCode = 3,
                ),
                currentVersionCode = 2,
            ),
        )
        assertFalse(
            requiresForceUpdate(
                AppVersionResponse(
                    latestVersionCode = 4,
                    minSupportedVersionCode = 2,
                ),
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `explicit force update remains mandatory`() {
        assertTrue(
            requiresForceUpdate(
                AppVersionResponse(latestVersionCode = 3, forceUpdate = true),
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `mandatory update only locks dialog with a download url`() {
        assertFalse(
            shouldLockUpdateDialog(
                AppVersionResponse(latestVersionCode = 3, forceUpdate = true, downloadUrl = ""),
                currentVersionCode = 2,
            ),
        )
        assertTrue(
            shouldLockUpdateDialog(
                AppVersionResponse(
                    latestVersionCode = 3,
                    forceUpdate = true,
                    downloadUrl = "https://example.com/miao.apk",
                ),
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `relative download url resolves against current api host`() {
        val baseUrl = "http://192.168.2.2:3000/api/v1/".toHttpUrl()

        assertEquals(
            "http://192.168.2.2:3000/api/v1/uploads/releases/miao.apk",
            resolveDownloadUrl("/api/v1/uploads/releases/miao.apk", baseUrl),
        )
        assertEquals(
            "http://192.168.2.2:3000/api/v1/uploads/releases/miao.apk",
            resolveDownloadUrl("uploads/releases/miao.apk", baseUrl),
        )
    }

    @Test
    fun `absolute download url is retained and unsafe scheme is rejected`() {
        val baseUrl = "http://192.168.2.2:3000/api/v1/".toHttpUrl()

        assertEquals(
            "https://downloads.example.com/miao.apk",
            resolveDownloadUrl("https://downloads.example.com/miao.apk", baseUrl),
        )
        assertEquals(null, resolveDownloadUrl("javascript:alert(1)", baseUrl))
        assertEquals(null, resolveDownloadUrl("  ", baseUrl))
    }

    @Test
    fun `announcement selection removes read and invalid entries`() {
        val result = selectUnreadAnnouncements(
            announcements = listOf(
                AppAnnouncement(id = "read", title = "已读"),
                AppAnnouncement(id = "", title = "无 ID"),
                AppAnnouncement(id = "new", title = "新公告"),
            ),
            readIds = setOf("read"),
        )

        assertEquals(listOf("new"), result.map { it.id })
    }

    @Test
    fun `pinned announcements lead then newest starts first`() {
        val result = selectUnreadAnnouncements(
            announcements = listOf(
                AppAnnouncement(id = "normal-new", startsAt = "2026-07-16T10:00:00Z"),
                AppAnnouncement(id = "pinned-old", isPinned = true, startsAt = "2026-07-14T10:00:00Z"),
                AppAnnouncement(id = "pinned-new", isPinned = true, startsAt = "2026-07-15T10:00:00Z"),
                AppAnnouncement(id = "normal-old", startsAt = "2026-07-13T10:00:00Z"),
            ),
            readIds = emptySet(),
        )

        assertEquals(
            listOf("pinned-new", "pinned-old", "normal-new", "normal-old"),
            result.map { it.id },
        )
    }
}
