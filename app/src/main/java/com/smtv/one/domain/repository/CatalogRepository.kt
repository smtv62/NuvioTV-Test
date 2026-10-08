package com.smtv.one.domain.repository

import com.smtv.one.core.network.NetworkResult
import com.smtv.one.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int = 0,
        skipStep: Int = 100,
        extraArgs: Map<String, String> = emptyMap(),
        supportsSkip: Boolean = false,
        posterScreen: com.smtv.one.core.poster.CustomPosterScreen = com.smtv.one.core.poster.CustomPosterScreen.HOME
    ): Flow<NetworkResult<CatalogRow>>
}
