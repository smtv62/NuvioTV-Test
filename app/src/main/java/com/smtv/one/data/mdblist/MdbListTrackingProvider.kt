package com.smtv.one.data.mdblist

import com.smtv.one.core.profile.ProfileManager
import com.smtv.one.core.tracking.TrackingCapability
import com.smtv.one.core.tracking.TrackingHistoryItem
import com.smtv.one.core.tracking.TrackingHistoryWriter
import com.smtv.one.core.tracking.TrackingMediaReference
import com.smtv.one.core.tracking.TrackingMutationResult
import com.smtv.one.core.tracking.TrackingProvider
import com.smtv.one.core.tracking.TrackingProviderDescriptor
import com.smtv.one.core.tracking.TrackingProviderId
import com.smtv.one.core.tracking.TrackingScrobbleAction
import com.smtv.one.core.tracking.TrackingScrobbleEvent
import com.smtv.one.core.tracking.TrackingScrobbler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Singleton
class MdbListTrackingProvider @Inject constructor(
    auth: MdbListAuthStore,
    profiles: ProfileManager,
    override val scrobbler: MdbListTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.MDBLIST,
        "MDBList",
        setOf(TrackingCapability.AUTHENTICATION, TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE, TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE, TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE, TrackingCapability.SCROBBLE)
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}

@Singleton
class MdbListTrackingScrobbler @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val service: MdbListScrobbleService
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.MDBLIST

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        service.scrobble(sync.currentScope(), action, event)
    }
}

@Singleton
class MdbListTrackingHistoryWriter @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val service: MdbListHistoryService
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.MDBLIST

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        return service.add(scope, items)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        return service.remove(scope, items)
    }
}
