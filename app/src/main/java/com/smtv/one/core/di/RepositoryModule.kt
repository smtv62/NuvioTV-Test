package com.smtv.one.core.di

import com.smtv.one.data.repository.AddonRepositoryImpl
import com.smtv.one.data.repository.CatalogRepositoryImpl
import com.smtv.one.data.repository.LibraryRepositoryImpl
import com.smtv.one.data.repository.MetaRepositoryImpl
import com.smtv.one.data.repository.StreamRepositoryImpl
import com.smtv.one.data.repository.SubtitleRepositoryImpl
import com.smtv.one.data.repository.SyncRepositoryImpl
import com.smtv.one.data.repository.WatchProgressRepositoryImpl
import com.smtv.one.domain.repository.AddonRepository
import com.smtv.one.domain.repository.CatalogRepository
import com.smtv.one.domain.repository.LibraryRepository
import com.smtv.one.domain.repository.MetaRepository
import com.smtv.one.domain.repository.StreamRepository
import com.smtv.one.domain.repository.SubtitleRepository
import com.smtv.one.domain.repository.SyncRepository
import com.smtv.one.domain.repository.WatchProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAddonRepository(impl: AddonRepositoryImpl): AddonRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindMetaRepository(impl: MetaRepositoryImpl): MetaRepository

    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: StreamRepositoryImpl): StreamRepository

    @Binds
    @Singleton
    abstract fun bindSubtitleRepository(impl: SubtitleRepositoryImpl): SubtitleRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindWatchProgressRepository(impl: WatchProgressRepositoryImpl): WatchProgressRepository
}
