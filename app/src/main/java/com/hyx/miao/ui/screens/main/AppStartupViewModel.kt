package com.hyx.miao.ui.screens.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyx.miao.BuildConfig
import com.hyx.miao.data.local.AnnouncementReadStore
import com.hyx.miao.data.remote.ApiEndpointProvider
import com.hyx.miao.data.remote.api.AppAnnouncement
import com.hyx.miao.data.remote.api.AppApi
import com.hyx.miao.data.remote.api.AppVersionResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppStartupState(
    val isLoading: Boolean = false,
    val update: AppVersionResponse? = null,
    val announcements: List<AppAnnouncement> = emptyList(),
)

@HiltViewModel
class AppStartupViewModel @Inject constructor(
    private val appApi: AppApi,
    private val endpointProvider: ApiEndpointProvider,
    private val announcementReadStore: AnnouncementReadStore,
) : ViewModel() {
    private val _state = MutableStateFlow(AppStartupState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.isLoading) return
        val previous = _state.value
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val updateResult = runCatching {
                appApi.getVersion(BuildConfig.VERSION_CODE)
                    .resolveDownloadUrl(endpointProvider.baseUrl)
            }
            val update = updateResult.fold(
                onSuccess = { version ->
                    version.takeIf { isUpdateAvailable(it, BuildConfig.VERSION_CODE) }
                },
                onFailure = { previous.update },
            )
            val announcementResult = runCatching {
                appApi.getAnnouncements()
            }
            val announcements = announcementResult.getOrElse { previous.announcements }
            _state.value = AppStartupState(
                update = update,
                announcements = selectUnreadAnnouncements(
                    announcements = announcements,
                    readIds = announcementReadStore.readIds(),
                ),
            )
        }
    }

    fun postponeUpdate() {
        val version = _state.value.update ?: return
        val canSafelyDismiss = !shouldLockUpdateDialog(version, BuildConfig.VERSION_CODE)
        if (canSafelyDismiss) {
            _state.value = _state.value.copy(update = null)
        }
    }

    fun markAnnouncementRead(id: String) {
        announcementReadStore.markRead(id)
        _state.value = _state.value.copy(
            announcements = _state.value.announcements.filterNot { it.id == id },
        )
    }
}
