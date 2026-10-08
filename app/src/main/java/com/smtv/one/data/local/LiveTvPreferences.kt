package com.smtv.one.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.liveTvDataStore by preferencesDataStore(
    name = "live_tv_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Canlı TV tercihleri: favoriler, son kanal ve açılış/gizleme anahtarları. */
@Singleton
class LiveTvPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lastChannelIdKey = stringPreferencesKey("last_channel_id")
    private val favoritesKey = stringSetPreferencesKey("favorite_channel_ids")
    private val autoStartOnLaunchKey = booleanPreferencesKey("auto_start_last_channel")
    private val autoHideListKey = booleanPreferencesKey("auto_hide_channel_list")

    val lastChannelId: Flow<String> =
        context.liveTvDataStore.data.map { it[lastChannelIdKey].orEmpty() }

    val favoriteChannelIds: Flow<Set<String>> =
        context.liveTvDataStore.data.map { it[favoritesKey].orEmpty() }

    val autoStartOnLaunch: Flow<Boolean> =
        context.liveTvDataStore.data.map { it[autoStartOnLaunchKey] ?: false }

    val autoHideChannelList: Flow<Boolean> =
        context.liveTvDataStore.data.map { it[autoHideListKey] ?: true }

    suspend fun setLastChannelId(id: String) {
        context.liveTvDataStore.edit { it[lastChannelIdKey] = id }
    }

    suspend fun setFavoriteChannelIds(ids: Set<String>) {
        context.liveTvDataStore.edit { it[favoritesKey] = ids }
    }

    suspend fun setAutoStartOnLaunch(enabled: Boolean) {
        context.liveTvDataStore.edit { it[autoStartOnLaunchKey] = enabled }
    }

    suspend fun setAutoHideChannelList(enabled: Boolean) {
        context.liveTvDataStore.edit { it[autoHideListKey] = enabled }
    }
}
