package com.hyx.miao.ui.screens.main

import com.hyx.miao.data.remote.api.AppAnnouncement
import com.hyx.miao.data.remote.api.AppVersionResponse
import okhttp3.HttpUrl

internal fun isUpdateAvailable(
    version: AppVersionResponse,
    currentVersionCode: Int,
): Boolean = version.latestVersionCode > currentVersionCode

internal fun requiresForceUpdate(
    version: AppVersionResponse,
    currentVersionCode: Int,
): Boolean = version.forceUpdate ||
    (version.minSupportedVersionCode ?: 0) > currentVersionCode

internal fun shouldLockUpdateDialog(
    version: AppVersionResponse,
    currentVersionCode: Int,
): Boolean = requiresForceUpdate(version, currentVersionCode) &&
    !version.downloadUrl.isNullOrBlank()

internal fun resolveDownloadUrl(
    rawUrl: String?,
    apiBaseUrl: HttpUrl,
): String? {
    val raw = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return apiBaseUrl.resolve(raw)
        ?.takeIf { it.scheme == "http" || it.scheme == "https" }
        ?.toString()
}

internal fun AppVersionResponse.resolveDownloadUrl(apiBaseUrl: HttpUrl): AppVersionResponse =
    copy(downloadUrl = resolveDownloadUrl(downloadUrl, apiBaseUrl))

internal fun selectUnreadAnnouncements(
    announcements: List<AppAnnouncement>,
    readIds: Set<String>,
): List<AppAnnouncement> = announcements
    .asSequence()
    .filter { it.id.isNotBlank() && it.id !in readIds }
    .sortedWith(
        compareByDescending<AppAnnouncement> { it.isPinned }
            .thenByDescending { it.startsAt.orEmpty() }
            .thenBy { it.id },
    )
    .toList()
