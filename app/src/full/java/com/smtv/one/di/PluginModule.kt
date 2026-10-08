package com.smtv.one.di

import com.smtv.one.core.auth.AuthManager
import com.smtv.one.core.plugin.PluginManager
import com.smtv.one.core.plugin.PluginRuntime
import com.smtv.one.core.profile.ProfileManager
import com.smtv.one.core.plugin.cloudstream.ExternalExtensionLoader
import com.smtv.one.core.plugin.cloudstream.ExternalExtensionRunner
import com.smtv.one.core.plugin.cloudstream.ExternalRepoParser
import com.smtv.one.core.sync.PluginSyncService
import com.smtv.one.data.local.PluginDataStore
import com.smtv.one.data.local.AddonPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun providePluginRuntime(): PluginRuntime {
        return PluginRuntime()
    }

    @Provides
    @Singleton
    fun providePluginManager(
        dataStore: PluginDataStore,
        addonPreferences: AddonPreferences,
        profileManager: ProfileManager,
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager,
        externalRepoParser: ExternalRepoParser,
        externalExtensionLoader: ExternalExtensionLoader,
        externalExtensionRunner: ExternalExtensionRunner
    ): PluginManager {
        return PluginManager(
            dataStore, addonPreferences, profileManager, runtime, pluginSyncService, authManager,
            externalRepoParser, externalExtensionLoader, externalExtensionRunner
        )
    }
}
