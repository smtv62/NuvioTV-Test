package com.smtv.one.data.repository

import android.content.Context
import com.smtv.one.data.m3u.M3uParser
import com.smtv.one.domain.model.LiveChannelFormatting
import com.smtv.one.domain.model.LiveLogoStyle
import com.smtv.one.domain.model.SportsCandidate
import com.smtv.one.domain.model.SportsChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spor kaynaklari deposu.
 *
 * Sabit M3U kaynaklarini (TRGoals, AtomSpor, Birazspor, SLCK) paralel çeker,
 * her kaynagi ayri dosyaya onbellikler (dosya yokken acilista aninda liste),
 * ardindan ayni kanal adini tasiyan girisleri tek kanal altinda aday olarak
 * birlestirir. Oynatma sirasi adaylar arası otomatik deneme ile cozulur.
 */
@Singleton
class SportsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    data class SportsSource(val id: String, val name: String, val url: String)

    data class SourceStatus(
        val id: String,
        val name: String,
        val ok: Boolean,
        val channelCount: Int,
        val error: String? = null,
    )

    data class LoadResult(
        val channels: List<SportsChannel>,
        val statuses: List<SourceStatus>,
    )

    companion object {
        /**
         * Spor kaynaklari (v1: sabit liste; kaynaklar kullanici tarafindan verilir).
         * Siralama onemli: aday denemede ilk siradaki kaynak once denenir.
         */
        val DEFAULT_SOURCES = listOf(
            SportsSource(
                id = "trgoals",
                name = "TRGoals",
                url = "https://raw.githubusercontent.com/smtv62/trgols/refs/heads/main/taraftarium_canli.m3u",
            ),
            SportsSource(
                id = "atomspor",
                name = "AtomSpor",
                url = "https://raw.githubusercontent.com/panorea744/stawerlo/refs/heads/main/atom.m3u",
            ),
            SportsSource(
                id = "birazspor",
                name = "BirazSpor",
                url = "https://raw.githubusercontent.com/smtv62/birazspor/refs/heads/main/liste.m3u",
            ),
            SportsSource(
                id = "slck",
                name = "SLCK",
                url = "https://raw.githubusercontent.com/smtv62/slck/refs/heads/main/5.m3u",
            ),
        )

        private const val CACHE_DIR = "sports"
    }

    private fun cacheFile(id: String): File =
        File(File(context.filesDir, CACHE_DIR), "$id.m3u")

    private fun readSourceCache(source: SportsSource): List<M3uParser.Entry>? {
        val content = runCatching { cacheFile(source.id).takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val entries = M3uParser.parse(decodeIfObfuscated(content))
        return entries.takeIf { it.isNotEmpty() }
    }

    /** Oncelikle onbellegi okur (aninda liste). */
    fun readCache(): LoadResult {
        val parsed = DEFAULT_SOURCES.mapNotNull { source ->
            readSourceCache(source)?.let { source to it }
        }
        val statuses = DEFAULT_SOURCES.map { source ->
            val entries = parsed.firstOrNull { it.first.id == source.id }?.second
            SourceStatus(
                id = source.id,
                name = source.name,
                ok = entries != null,
                channelCount = entries?.let { countUnique(it) } ?: 0,
                error = if (entries == null) "Onbellegi yok" else null,
            )
        }
        return assemble(parsed, statuses)
    }

    /** Kaynaklari paralel tazeler; basarisiz kaynakta eski onbellegi korur. */
    suspend fun fetchFresh(): LoadResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = DEFAULT_SOURCES.map { source ->
                async {
                    val outcome = runCatching {
                        val request = Request.Builder()
                            .url(source.url)
                            .header("User-Agent", "smTV-One/1.0")
                            .build()
                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val raw = response.body?.string().orEmpty()
                            // Bazi kaynaklar (orn. AtomSpor) playlist'i karakter
                            // kodlari dizisi olarak verir; oynatmadan once coz.
                            val body = decodeIfObfuscated(raw)
                            if (body.isBlank()) throw IOException("Bos liste govdesi")
                            runCatching {
                                cacheFile(source.id).parentFile?.mkdirs()
                                cacheFile(source.id).writeText(body, Charsets.UTF_8)
                            }
                            M3uParser.parse(body)
                        }
                    }
                    source to outcome
                }
            }.awaitAll()

            val statuses = results.map { (source, outcome) ->
                outcome.fold(
                    onSuccess = { entries ->
                        SourceStatus(source.id, source.name, ok = true, channelCount = countUnique(entries))
                    },
                    onFailure = { error ->
                        SourceStatus(source.id, source.name, ok = false, channelCount = 0, error = error.message)
                    },
                )
            }

            // Basarisiz kaynaklarda eski onbellege dus: bir kaynak olurse digerleri kaybolmasin.
            val parsed = results.mapNotNull { (source, outcome) ->
                outcome.getOrNull()?.let { source to it }
                    ?: readSourceCache(source)?.let { source to it }
            }
            assemble(parsed, statuses)
        }
    }

    /** Girileri kanal adina gore birlestirir (ayni ad = ayni kanal, adaylar eklenir). */
    private fun assemble(
        parsed: List<Pair<SportsSource, List<M3uParser.Entry>>>,
        statuses: List<SourceStatus>,
    ): LoadResult {
        class Holder(var name: String, var logoUrl: String?, val candidates: MutableList<SportsCandidate>)

        val merged = LinkedHashMap<String, Holder>()
        parsed.forEach { (source, entries) ->
            entries.forEach { entry ->
                val name = entry.name.trim().ifEmpty { return@forEach }
                val key = normalizeKey(name)
                if (key.isEmpty()) return@forEach
                val holder = merged.getOrPut(key) {
                    Holder(name, entry.logoUrl?.takeIf { it.isNotBlank() }, mutableListOf())
                }
                // Daha "duzgun" (uzun) gorunen adi tercih et: "BeIN Sports 1" > "beinsports1".
                if (name.length > holder.name.length) holder.name = name
                if (holder.logoUrl == null) holder.logoUrl = entry.logoUrl?.takeIf { it.isNotBlank() }
                // Ayni kaynak ayni URL'yi iki kez listeliyorsa (yedek giris) tekrarlama:
                if (holder.candidates.none { it.url == entry.url }) {
                    holder.candidates += SportsCandidate(
                        sourceId = source.id,
                        sourceName = source.name,
                        url = entry.url,
                        headers = entry.headers,
                    )
                }
            }
        }

        val channels = merged.entries.mapIndexed { index, (key, holder) ->
            val colors = LiveChannelFormatting.colorPairFor(holder.name)
            SportsChannel(
                id = "spor-$key",
                number = index + 1,
                name = holder.name,
                shortLabel = LiveChannelFormatting.shortLabelFor(holder.name),
                logo = LiveLogoStyle(colors.first, colors.second),
                logoUrl = holder.logoUrl,
                sourceNames = holder.candidates.map { it.sourceName }.distinct(),
                candidates = holder.candidates,
            )
        }
        return LoadResult(channels, statuses)
    }

    private fun countUnique(entries: List<M3uParser.Entry>): Int =
        entries.map { normalizeKey(it.name) }.filter { it.isNotEmpty() }.distinct().size

    /** "BeIN Sports 1" / "beinsports1" ayni anahtara dusmeli. */
    private fun normalizeKey(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    /**
     * Obfuscate edilmis playlist'leri cozer.
     *
     * Ornek (AtomSpor): govde satir satir ondalik karakter kodlarindan olusur:
     * `35 69 88 84 77 51 85 10` -> "#EXTM3U\n". Tum satirlar sayi ve cozulen
     * metin "#EXTM3U" ile basliyorsa cozulmus kabul edilir; degilse aynen birakilir.
     */
    private fun decodeIfObfuscated(raw: String): String {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 10 || lines.any { !it.all { ch -> ch.isDigit() } }) return raw
        val decoded = runCatching {
            buildString { lines.forEach { append(Char(it.toInt())) } }
        }.getOrNull() ?: return raw
        return if (decoded.startsWith("#EXTM3U", ignoreCase = true)) decoded else raw
    }
}
