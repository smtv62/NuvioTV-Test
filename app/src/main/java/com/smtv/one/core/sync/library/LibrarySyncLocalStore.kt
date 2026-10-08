package com.smtv.one.core.sync.library

import com.smtv.one.domain.model.LibraryDeltaApplyResult
import com.smtv.one.domain.model.LibraryDeltaEvent
import com.smtv.one.domain.model.LibrarySnapshotApplyResult
import com.smtv.one.domain.model.LibrarySyncState
import com.smtv.one.domain.model.SavedLibraryItem

interface LibrarySyncLocalStore {
    suspend fun getSyncState(profileId: Int): LibrarySyncState

    suspend fun applyRemoteSnapshot(
        profileId: Int,
        remoteItems: Collection<SavedLibraryItem>,
        cursorEventId: Long
    ): LibrarySnapshotApplyResult

    suspend fun applyRemoteDelta(
        profileId: Int,
        events: Collection<LibraryDeltaEvent>
    ): LibraryDeltaApplyResult

    suspend fun queueAllItemsForPush(profileId: Int): LibrarySyncState

    suspend fun acknowledgePush(
        profileId: Int,
        expectedMutationRevision: Long
    ): Boolean
}
