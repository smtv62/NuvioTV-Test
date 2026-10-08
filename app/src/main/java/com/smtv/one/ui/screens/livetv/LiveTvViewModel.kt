package com.smtv.one.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smtv.one.data.local.LiveTvPreferences
import com.smtv.one.data.repository.LiveTvRepository
import com.smtv.one.domain.model.LiveChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Canlı TV ekranının durumu. */
data class LiveTvUiState(
    val loading: Boolean = true,
    /** Manuel yenileme sürüyor (liste görünür kalmaya devam eder). */
    val refreshing: Boolean = false,
    /** Ağ ve önbellek de boşsa true → hata/tekrar dene durumu. */
    val error: Boolean = false,
    /** Yenileme başarısız (eski liste korunur); ekran toast atar. */
    val refreshFailedAt: Long = 0L,
    val channels: List<LiveChannel> = emptyList(),
    /** M3U group-title değerleri, görünüme sırasıyla. */
    val groups: List<String> = emptyList(),
    val selectedGroup: String = GROUP_ALL,
    val currentChannelId: String = "",
    val highlightedChannelId: String = "",
    val favorites: Set<String> = emptySet(),
    val autoHideList: Boolean = true,
    val autoStartOnLaunch: Boolean = false,
) {
    fun filteredChannels(): List<LiveChannel> = when (selectedGroup) {
        GROUP_ALL -> channels
        GROUP_FAVORITES -> channels.filter { it.id in favorites }
        else -> channels.filter { it.group.equals(selectedGroup, ignoreCase = true) }
    }

    val currentChannel: LiveChannel?
        get() = channels.firstOrNull { it.id == currentChannelId }

    companion object {
        const val GROUP_ALL = "__all__"
        const val GROUP_FAVORITES = "__favorites__"
    }
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val preferences: LiveTvPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val favorites = preferences.favoriteChannelIds.first()
            val autoHide = preferences.autoHideChannelList.first()
            val autoStart = preferences.autoStartOnLaunch.first()
            val lastChannelId = preferences.lastChannelId.first()
            _uiState.update {
                it.copy(
                    favorites = favorites,
                    autoHideList = autoHide,
                    autoStartOnLaunch = autoStart,
                    currentChannelId = lastChannelId,
                    highlightedChannelId = lastChannelId,
                )
            }

            // Önce önbellek (anında liste), ardından ağdan taze liste.
            val cached = repository.readCache()
            if (cached != null) {
                applyChannels(cached)
                _uiState.update { it.copy(loading = false) }
                refresh(fromInit = true)
            } else {
                refresh(fromInit = true)
            }
        }
    }

    /** Manuel/açılış yenilemesi. Başarısızsa mevcut liste korunur. */
    fun refresh(fromInit: Boolean = false) {
        if (_uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            runCatching { repository.fetchFresh() }
                .onSuccess { channels ->
                    applyChannels(channels)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = channels.isEmpty(),
                        )
                    }
                }
                .onFailure {
                    val hasChannels = _uiState.value.channels.isNotEmpty()
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = !hasChannels,
                            refreshFailedAt = if (hasChannels && !fromInit) System.currentTimeMillis() else 0L,
                        )
                    }
                }
        }
    }

    /** Yeni kanal listesini uygular ve seçili/çalan kanalı doğrular. */
    private fun applyChannels(channels: List<LiveChannel>) {
        val groups = channels.map { it.group }.distinct()
        _uiState.update { state ->
            var currentId = state.currentChannelId.takeIf { id -> channels.any { it.id == id } }
                ?: channels.firstOrNull()?.id.orEmpty()
            var selected = state.selectedGroup
            if (selected != LiveTvUiState.GROUP_ALL &&
                selected != LiveTvUiState.GROUP_FAVORITES &&
                groups.none { it.equals(selected, ignoreCase = true) }
            ) {
                selected = LiveTvUiState.GROUP_ALL
            }
            // Seçili grupta çalan kanal yoksa gruptaki ilk kanalı çal (eski davranış).
            val filtered = when (selected) {
                LiveTvUiState.GROUP_ALL -> channels
                LiveTvUiState.GROUP_FAVORITES -> channels.filter { it.id in state.favorites }
                else -> channels.filter { it.group.equals(selected, ignoreCase = true) }
            }
            if (filtered.isNotEmpty() && filtered.none { it.id == currentId }) {
                currentId = filtered.first().id
            }
            state.copy(
                channels = channels,
                groups = groups,
                selectedGroup = selected,
                currentChannelId = currentId,
                highlightedChannelId = currentId,
            )
        }
        val id = _uiState.value.currentChannelId
        if (id.isNotBlank()) {
            viewModelScope.launch { preferences.setLastChannelId(id) }
        }
    }

    fun selectGroup(group: String) {
        if (group == _uiState.value.selectedGroup) return
        _uiState.update { it.copy(selectedGroup = group) }
        val filtered = _uiState.value.filteredChannels()
        val currentId = _uiState.value.currentChannelId
        if (filtered.isNotEmpty() && filtered.none { it.id == currentId }) {
            playChannel(filtered.first().id)
        }
    }

    fun playChannel(id: String) {
        val state = _uiState.value
        if (state.channels.none { it.id == id }) return
        _uiState.update { it.copy(currentChannelId = id, highlightedChannelId = id) }
        viewModelScope.launch { preferences.setLastChannelId(id) }
    }

    /** Listeyi mevcut filtre içinde bir alt/üst kanala taşı (CH+/CH-). */
    fun stepChannel(delta: Int) {
        val filtered = _uiState.value.filteredChannels()
        if (filtered.isEmpty()) return
        val index = filtered.indexOfFirst { it.id == _uiState.value.currentChannelId }
        if (index == -1) {
            playChannel(filtered.first().id)
            return
        }
        val next = (index + delta).coerceIn(0, filtered.lastIndex)
        playChannel(filtered[next].id)
    }

    /** Kumandadan girilen rakamlarla kanal seçimi (örn. 0, 9 → kanal 9). */
    fun tuneToNumber(number: Int): Boolean {
        val channels = _uiState.value.channels
        val channel = channels.firstOrNull { it.number == number }
            ?: channels.firstOrNull { it.number == number % 10 }
            ?: return false
        playChannel(channel.id)
        return true
    }

    fun highlightChannel(id: String) {
        _uiState.update { it.copy(highlightedChannelId = id) }
    }

    /** Vurgulanan kanalı favorilere ekle/çıkar; yeni durumu döndürür. */
    fun toggleFavorite(id: String): Boolean? {
        if (id.isBlank() || _uiState.value.channels.none { it.id == id }) return null
        val nowFavorite: Boolean
        val updated = _uiState.value.favorites.toMutableSet().apply {
            nowFavorite = if (add(id)) true else {
                remove(id)
                false
            }
        }
        _uiState.update { it.copy(favorites = updated) }
        viewModelScope.launch { preferences.setFavoriteChannelIds(updated) }
        return nowFavorite
    }

    fun setAutoHideList(enabled: Boolean) {
        _uiState.update { it.copy(autoHideList = enabled) }
        viewModelScope.launch { preferences.setAutoHideChannelList(enabled) }
    }

    fun setAutoStartOnLaunch(enabled: Boolean) {
        _uiState.update { it.copy(autoStartOnLaunch = enabled) }
        viewModelScope.launch { preferences.setAutoStartOnLaunch(enabled) }
    }
}
