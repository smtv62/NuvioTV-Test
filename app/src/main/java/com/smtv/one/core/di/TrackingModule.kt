package com.smtv.one.core.di

import com.smtv.one.data.simkl.AndroidSimklAuthStorage
import com.smtv.one.data.simkl.AndroidSimklSyncStorage
import com.smtv.one.data.simkl.SimklAuthStorage
import com.smtv.one.data.simkl.SimklApiSyncRemote
import com.smtv.one.data.simkl.SimklSyncRemote
import com.smtv.one.data.simkl.SimklSyncStorage
import com.smtv.one.core.tracking.TrackingLibraryProvider
import com.smtv.one.core.tracking.TrackingProvider
import com.smtv.one.data.repository.TraktTrackingLibraryProvider
import com.smtv.one.data.repository.TraktTrackingProvider
import com.smtv.one.data.simkl.SimklLibraryService
import com.smtv.one.core.tracking.TrackingHistoryWriter
import com.smtv.one.core.tracking.TrackingProgressProvider
import com.smtv.one.data.repository.TraktTrackingHistoryWriter
import com.smtv.one.data.repository.TraktTrackingProgressProvider
import com.smtv.one.data.simkl.SimklTrackingHistoryWriter
import com.smtv.one.data.simkl.SimklTrackingProgressProvider
import com.smtv.one.data.simkl.SimklTrackingProvider
import com.smtv.one.core.profile.ProfileScopedCredentialStore
import com.smtv.one.data.mdblist.MdbListTrackingHistoryWriter
import com.smtv.one.data.mdblist.MdbListTrackingProgressProvider
import com.smtv.one.data.mdblist.MdbListTrackingProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {
    @Binds
    @IntoSet
    abstract fun bindMdbListLibrary(provider: com.smtv.one.data.mdblist.MdbListTrackingLibraryProvider): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindMdbListProvider(provider: MdbListTrackingProvider): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindMdbListProgress(provider: MdbListTrackingProgressProvider): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindMdbListHistory(writer: MdbListTrackingHistoryWriter): TrackingHistoryWriter

    @Binds
    @Singleton
    abstract fun bindSimklAuthStorage(storage: AndroidSimklAuthStorage): SimklAuthStorage

    @Binds
    @IntoSet
    abstract fun bindSimklProfileScopedCredentialStore(
        storage: AndroidSimklAuthStorage
    ): ProfileScopedCredentialStore

    @Binds
    @Singleton
    abstract fun bindSimklSyncStorage(storage: AndroidSimklSyncStorage): SimklSyncStorage

    @Binds
    @Singleton
    abstract fun bindSimklSyncRemote(remote: SimklApiSyncRemote): SimklSyncRemote

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingLibraryProvider(
        provider: TraktTrackingLibraryProvider
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingLibraryProvider(
        provider: SimklLibraryService
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProgressProvider(
        provider: TraktTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProgressProvider(
        provider: SimklTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingHistoryWriter(
        writer: TraktTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingHistoryWriter(
        writer: SimklTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProvider(
        provider: TraktTrackingProvider
    ): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProvider(
        provider: SimklTrackingProvider
    ): TrackingProvider
}
