package com.smtv.one.ui.screens.spor

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smtv.one.data.repository.SportsRepository
import com.smtv.one.domain.model.SportsChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tek bir spor kaynaginin durumu (cikti: chip rozeti + filtre). */
data class SportsSourceStatus(
    val id: String,
    val name: String,
    val ok: Boolean,
    val channelCount: Int,
    val error: String? = null,
)

/** Spor ekraninin durumu. */
data class SportsUiState(
    val loading: Boolean = true,
    /** Manuel yenileme suruyor (liste gorunur kalmaya devam eder). */
    val refreshing: Boolean = false,
    /** Ag ve onbellek de bossa true -> hata/tekrar dene durumu. */
    val error: Boolean = false,
    /** Yenileme basarisiz (eski liste korunur); ekran toast atar. */
    val refreshFailedAt: Long = 0L,
    val channels: List<SportsChannel> = emptyList(),
    val sources: List<SportsSourceStatus> = emptyList(),
    /** Secili kaynak filtresi; null = tumu. */
    val selectedSourceId: String? = null,
) {
    val filteredChannels: List<SportsChannel>
        get() = selectedSourceId
            ?.let { sid -> channels.filter { channel -> channel.candidates.any { it.sourceId == sid } } }
            ?: channels
}

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repository: SportsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SportsUiState())
    val uiState: StateFlow<SportsUiState> = _uiState.asStateFlow()

    /** Kanal bazinda son basarili aday indeksi (sonraki acilista oradan dener). */
    private val preferredCandidate = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch {
            // Once onbelleggik (aninda liste), ardindan agdan taze liste.
            val cached = repository.readCache()
            if (cached.channels.isNotEmpty()) {
                applyLoad(cached)
                _uiState.update { it.copy(loading = false) }
                refresh(fromInit = true)
            } else {
                refresh(fromInit = true)
            }
        }
    }

    /** Manuel/acilis yenilemesi. Basarisizsa mevcut liste korunur. */
    fun refresh(fromInit: Boolean = false) {
        if (_uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            runCatching { repository.fetchFresh() }
                .onSuccess { result ->
                    applyLoad(result)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = result.channels.isEmpty(),
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(
                            loading = false,
                            refreshing = false,
                            error = state.channels.isEmpty(),
                            // Liste duruyorsa ekran toast ile bilsin.
                            refreshFailedAt = if (state.channels.isNotEmpty()) SystemClock.elapsedRealtime() else 0L,
                        )
                    }
                }
        }
    }

    fun selectSource(sourceId: String?) {
        _uiState.update { it.copy(selectedSourceId = sourceId) }
    }

    /** Son otomatik (failover turu) yenileme ani; 60 sn alti tekrarlar elenir. */
    private var lastAutoRefreshAt = 0L

    /** Tum adaylar tukendiginde failover'in cagirdigi tur yenilemesi (throttle'li). */
    fun autoRefreshThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoRefreshAt < 60_000L) return
        lastAutoRefreshAt = now
        refresh()
    }

    fun preferredIndexFor(channelId: String?): Int =
        if (channelId == null) 0 else preferredCandidate[channelId] ?: 0

    fun rememberCandidate(channelId: String, index: Int) {
        preferredCandidate[channelId] = index
    }

    private fun applyLoad(result: SportsRepository.LoadResult) {
        val selected = _uiState.value.selectedSourceId
        val statuses = result.statuses.map {
            SportsSourceStatus(
                id = it.id,
                name = it.name,
                ok = it.ok,
                channelCount = it.channelCount,
                error = it.error,
            )
        }
        _uiState.update {
            it.copy(
                channels = result.channels,
                sources = statuses,
                selectedSourceId = selected?.takeIf { sid -> statuses.any { s -> s.id == sid } },
            )
        }
    }
}
