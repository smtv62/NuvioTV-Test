package com.smtv.one.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smtv.one.core.profile.ProfileManager
import com.smtv.one.core.tracking.TrackingProviderId
import com.smtv.one.core.tracking.TrackingSourceController
import com.smtv.one.core.tracking.TrackingSourceSelection
import com.smtv.one.core.tracking.availableLibrarySourceModes
import com.smtv.one.core.tracking.availableWatchProgressSources
import com.smtv.one.core.tracking.effectiveTrackingSourceSelection
import com.smtv.one.data.local.TraktAuthDataStore
import com.smtv.one.data.local.TraktSettingsDataStore
import com.smtv.one.data.local.WatchProgressSource
import com.smtv.one.data.simkl.SimklAnimeIdPreference
import com.smtv.one.data.simkl.SimklAuthRepository
import com.smtv.one.data.simkl.SimklSyncRepository
import com.smtv.one.data.mdblist.MdbListAuthStore
import com.smtv.one.domain.model.LibrarySourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackingSettingsUiState(
    val watchProgressSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val connectedProviderIds: Set<TrackingProviderId> = emptySet(),
    val simklAnimeIdPreference: SimklAnimeIdPreference = SimklAnimeIdPreference.DEFAULT,
    val isReady: Boolean = false
) {
    val availableWatchProgressSources: List<WatchProgressSource>
        get() = availableWatchProgressSources(connectedProviderIds)

    val availableLibrarySourceModes: List<LibrarySourceMode>
        get() = availableLibrarySourceModes(connectedProviderIds)
}

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    private val sourceController: TrackingSourceController,
    private val settingsDataStore: TraktSettingsDataStore,
    private val simklSyncRepository: SimklSyncRepository,
    traktAuthDataStore: TraktAuthDataStore,
    simklAuthRepository: SimklAuthRepository,
    mdbListAuth: MdbListAuthStore,
    profiles: ProfileManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val connected = combine(traktAuthDataStore.state, simklAuthRepository.state, mdbListAuth.state, profiles.activeProfileId) { trakt, simkl, mdblist, profileId ->
                if (mdblist.scope.profileId != profileId) null else buildSet<TrackingProviderId> {
                    if (trakt.isAuthenticated) add(TrackingProviderId.TRAKT)
                    if (simkl.isAuthenticated) add(TrackingProviderId.SIMKL)
                    if (mdblist.isAuthenticated) add(TrackingProviderId.MDBLIST)
                }
            }
            combine(
                sourceController.watchProgressSource,
                sourceController.librarySourceMode,
                connected,
                settingsDataStore.simklAnimeIdPreference
            ) { watchProgressSource, librarySourceMode, connectedProviderIds, animeIdPref ->
                if (connectedProviderIds == null) return@combine TrackingSettingsUiState()
                val effective = effectiveTrackingSourceSelection(
                    requested = TrackingSourceSelection(watchProgressSource, librarySourceMode),
                    connectedProviderIds = connectedProviderIds
                )
                TrackingSettingsUiState(
                    watchProgressSource = effective.watchProgressSource,
                    librarySourceMode = effective.librarySourceMode,
                    connectedProviderIds = connectedProviderIds,
                    simklAnimeIdPreference = animeIdPref,
                    isReady = true
                )
            }.collect { state ->
                _uiState.value = state
                if (state.isReady && mdbListAuth.scope().profileId == profiles.activeProfileId.value) {
                    sourceController.reconcileConnectedProviders(state.connectedProviderIds)
                }
            }
        }
    }

    fun selectWatchProgressSource(source: WatchProgressSource) {
        viewModelScope.launch {
            sourceController.selectWatchProgressSource(source)
        }
    }

    fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        viewModelScope.launch {
            sourceController.selectLibrarySourceMode(mode)
        }
    }

    fun selectSimklAnimeIdPreference(preference: SimklAnimeIdPreference) {
        viewModelScope.launch {
            settingsDataStore.setSimklAnimeIdPreference(preference)
            simklSyncRepository.invalidateProjections(preference)
        }
    }
}
