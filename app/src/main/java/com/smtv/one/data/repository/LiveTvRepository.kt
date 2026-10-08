package com.smtv.one.data.repository

import android.content.Context
import com.smtv.one.data.m3u.M3uParser
import com.smtv.one.domain.model.LiveChannel
import com.smtv.one.domain.model.LiveChannelFormatting
import com.smtv.one.domain.model.LiveLogoStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canlı TV playlist kaynağı. Tek sabit M3U adresinden kanalları okur,
 * dosya önbelleğine yazar (ağ yokken açılışta anında liste) ve
 * [LiveChannel] modeline dönüştürür.
 */
@Singleton
class LiveTvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        /** Sabit canlı TV listesi (v1: düzenleme arayüzü yok). */
        const val PLAYLIST_URL = "https://bit.ly/smtv6"
        private const val CACHE_DIR = "livetv"
        private const val CACHE_FILE = "playlist.m3u"
    }

    private val cacheFile: File
        get() = File(File(context.filesDir, CACHE_DIR), CACHE_FILE)

    /** Önbellekteki listeyi okur; yoksa/bozuksa null. */
    fun readCache(): List<LiveChannel>? {
        if (!cacheFile.exists()) return null
        val content = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return parse(content).takeIf { it.isNotEmpty() }
    }

    /**
     * Listeyi ağdan tazeler. Başarısızsa fırlatır; başarıda önbelleği günceller.
     */
    suspend fun fetchFresh(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(PLAYLIST_URL)
            .header("User-Agent", "smTV-One/1.0")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("Boş playlist gövdesi")
            val channels = parse(body)
            if (channels.isEmpty()) throw IOException("Playlistte kanal yok")
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(body, Charsets.UTF_8)
            }
            channels
        }
    }

    private fun parse(content: String): List<LiveChannel> {
        val entries = M3uParser.parse(content)
        return entries.mapIndexed { index, entry ->
            val name = entry.name.trim().ifEmpty { entry.url.substringAfterLast('/') }
            val colors = LiveChannelFormatting.colorPairFor(name)
            LiveChannel(
                id = "live-$index",
                number = index + 1,
                name = name,
                shortLabel = LiveChannelFormatting.shortLabelFor(name),
                logo = LiveLogoStyle(colors.first, colors.second),
                logoUrl = entry.logoUrl?.takeIf { it.isNotBlank() },
                group = entry.group?.trim()?.takeIf { it.isNotEmpty() } ?: "Genel",
                url = entry.url,
                headers = entry.headers,
            )
        }
    }
}
