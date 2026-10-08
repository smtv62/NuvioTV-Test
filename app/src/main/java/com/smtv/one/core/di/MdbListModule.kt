package com.smtv.one.core.di

import com.smtv.one.BuildConfig
import com.smtv.one.core.profile.ProfileScopedCredentialStore
import com.smtv.one.core.profile.ProfileManager
import com.smtv.one.data.local.ProfileDataStore
import com.smtv.one.data.mdblist.AndroidMdbListAuthPersistence
import com.smtv.one.data.mdblist.AndroidMdbListSyncStorage
import com.smtv.one.data.mdblist.MdbListSyncRepository
import com.smtv.one.data.mdblist.MdbListApiClient
import com.smtv.one.data.mdblist.MdbListAuthRepository
import com.smtv.one.data.mdblist.MdbListAuthStore
import com.smtv.one.data.mdblist.MdbListConfiguration
import com.smtv.one.data.mdblist.MdbListHttpClient
import com.smtv.one.data.mdblist.OkHttpMdbListEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object MdbListModule {
    @Provides
    @Singleton
    fun library(
        api: MdbListApiClient,
        sync: MdbListSyncRepository,
        auth: MdbListAuthStore,
        profiles: ProfileManager
    ) = com.smtv.one.data.mdblist.MdbListLibraryService(
        api, sync, auth, profiles.activeProfileId, CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    @Provides
    @Singleton
    fun configuration(): MdbListConfiguration = MdbListConfiguration(
        clientId = BuildConfig.MDBLIST_CLIENT_ID,
        appVersion = BuildConfig.VERSION_NAME
    )

    @Provides
    @Singleton
    fun http(configuration: MdbListConfiguration): MdbListHttpClient = MdbListHttpClient(
        OkHttpMdbListEngine(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build(),
            configuration
        )
    )

    @Provides
    @Singleton
    fun authStore(persistence: AndroidMdbListAuthPersistence, profileDataStore: ProfileDataStore): MdbListAuthStore {
        val store = MdbListAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: MdbListAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun auth(http: MdbListHttpClient, configuration: MdbListConfiguration, store: MdbListAuthStore) =
        MdbListAuthRepository(http, configuration, store)

    @Provides
    @Singleton
    fun api(http: MdbListHttpClient, auth: MdbListAuthRepository, store: MdbListAuthStore) =
        MdbListApiClient(http, auth, store)

    @Provides
    @Singleton
    fun syncRepository(
        storage: AndroidMdbListSyncStorage,
        auth: MdbListAuthStore,
        api: MdbListApiClient,
        profiles: ProfileManager
    ) = MdbListSyncRepository(
        storage, auth, api, profiles.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )
}
