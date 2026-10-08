package com.smtv.one.core.di

import com.smtv.one.core.sync.library.LibrarySyncLocalStore
import com.smtv.one.core.sync.library.LibrarySyncRemoteDataSource
import com.smtv.one.data.local.LibraryPreferences
import com.smtv.one.data.remote.supabase.SupabaseLibrarySyncRemoteDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LibrarySyncModule {
    @Binds
    @Singleton
    abstract fun bindLibrarySyncLocalStore(
        implementation: LibraryPreferences
    ): LibrarySyncLocalStore

    @Binds
    @Singleton
    abstract fun bindLibrarySyncRemoteDataSource(
        implementation: SupabaseLibrarySyncRemoteDataSource
    ): LibrarySyncRemoteDataSource
}
