package com.smtv.one.domain.repository

import com.smtv.one.core.network.NetworkResult
import com.smtv.one.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String? = null
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>

    /**
     * Returns cached meta if available (no network call). Useful for
     * reading backdrop URLs synchronously before navigation.
     */
    fun getCachedMeta(type: String, id: String): Meta?
    
    fun clearCache()
}
