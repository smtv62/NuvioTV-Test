package com.smtv.one.core.debrid

import com.smtv.one.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.smtv.one.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumizeDirectDownloadFileSelector @Inject constructor() {
    fun selectFile(
        files: List<PremiumizeDirectDownloadFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): PremiumizeDirectDownloadFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path.orEmpty() },
        isPlayable = { !it.link.isNullOrBlank() && it.displayName().hasDebridVideoExtension() },
        size = { it.size ?: 0L },
    )
}
