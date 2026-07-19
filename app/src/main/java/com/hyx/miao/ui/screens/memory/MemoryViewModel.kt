package com.hyx.miao.ui.screens.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.local.entity.MemoryEntity
import com.hyx.miao.data.repository.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSearching: Boolean = false,
    val error: String? = null,
    val searchResults: List<MemoryEntity>? = null,
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
) : ViewModel() {

    val memories = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState = _uiState.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.sync()
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "同步记忆失败，正在使用本地数据") }
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun add(content: String, onSuccess: () -> Unit = {}) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = repository.add(content)
            _uiState.update { state ->
                state.copy(
                    isSaving = false,
                    error = result.exceptionOrNull()?.message ?: state.error,
                )
            }
            result.onSuccess { onSuccess() }
        }
    }

    fun update(id: String, content: String, onSuccess: () -> Unit = {}) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = repository.update(id, content)
            _uiState.update { state ->
                state.copy(
                    isSaving = false,
                    searchResults = if (result.isSuccess) {
                        state.searchResults?.map { item ->
                            if (item.id == id) item.copy(content = content) else item
                        }
                    } else {
                        state.searchResults
                    },
                    error = result.exceptionOrNull()?.message ?: state.error,
                )
            }
            result.onSuccess { onSuccess() }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val result = repository.delete(id)
            _uiState.update { state ->
                state.copy(
                    searchResults = if (result.isSuccess) {
                        state.searchResults?.filterNot { it.id == id }
                    } else {
                        state.searchResults
                    },
                    error = result.exceptionOrNull()?.message ?: state.error,
                )
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null, isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            delay(350)
            val result = repository.semanticSearch(query)
            currentCoroutineContext().ensureActive()
            result
                .onSuccess { results ->
                    _uiState.update { it.copy(searchResults = results, isSearching = false) }
                }
                .onFailure { error ->
                    val local = memories.value.filter {
                        it.content.contains(query, ignoreCase = true) ||
                            it.summary.contains(query, ignoreCase = true)
                    }
                    _uiState.update {
                        it.copy(searchResults = local, error = error.message, isSearching = false)
                    }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
