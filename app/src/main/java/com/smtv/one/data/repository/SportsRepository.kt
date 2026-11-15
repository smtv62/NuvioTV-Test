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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spor kaynaklari deposu.
 *
 * Sabit M3U kaynaklarini (TRGoals, AtomSpor, Birazspor, SLCK, SportsBite)
 * paralel çeker (SportsBite gomulu listeden gelir), Vavoo (geil.to) katalogdan
 * TR spor kanallarini uretir,
 * her kaynagi ayri dosyaya onbellikler (dosya yokken acilista aninda liste),
 * ardindan ayni kanal adini tasiyan girisleri tek kanal altinda aday olarak
 * birlestirir. Oynatma sirasi adaylar arası otomatik deneme ile cozulur.
 */
@Singleton
class SportsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    data class SportsSource(
        val id: String,
        val name: String,
        val url: String,
        /**
         * Uzak M3U yerine uygulama icinde uretilen liste (orn. SportsBite:
         * sitede toplu M3U yok, her kanal /247/{slug} adresinden canli HLS
         * playlist'i dondurur). Varsayilan null -> uzak HTTP cekilir.
         */
        val embeddedPlaylist: String? = null,
    )

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
                url = "https://raw.githubusercontent.com/smtv62/atomspor/refs/heads/main/atom.m3u",
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
            SportsSource(
                id = "kral",
                name = "KralBozguncu",
                url = "https://raw.githubusercontent.com/smtv62/kralbozguncu/main/kral.m3u",
            ),
            SportsSource(
                id = "sportsbite",
                name = "SportsBite",
                url = "https://sportsbite.org/channels",
                embeddedPlaylist = buildSportsBitePlaylist(),
            ),
            /** NTV Stream (cdnlivetv.tv): beIN SPORTS 1-4 FHD TR + TRT Spor (kanal listesi JSON'u). */
            SportsSource(
                id = NTVSTREAM_SOURCE_ID,
                name = "NTV Stream",
                url = NTVSTREAM_CHANNELS_URL,
            ),
            /**
             * Vavoo (geil.to): katalogdan TR spor kanallari uretilir (search=SPOR,
             * Turkey etiketli). URL'ler `geil.to/live/play/{id}` formatindadir ve
             * oynatmadan once StreamResolver ile HLS'e cevrilir.
             */
            SportsSource(
                id = VAVOO_SOURCE_ID,
                name = "Vavoo",
                url = VAVOO_CATALOG_URL,
            ),
        )

        private const val CACHE_DIR = "sports"

        /** Vavoo kaynagi: geil.to live katalog uci. */
        private const val VAVOO_SOURCE_ID = "vavoo"
        private const val VAVOO_CATALOG_URL = "https://geil.to/live/catalog/live/channels.json"
        private const val VAVOO_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        /** Katalog sayfasi basina 300 kanal; SPOR aramasinda ~5 sayfa. */
        private const val VAVOO_MAX_PAGES = 12
        private const val VAVOO_FETCH_ATTEMPTS = 4

        /**
         * NTV Stream kaynagi: cdnlivetv.tv kanal listesi JSON'u (tek istekte tum
         * kanallar; TR spor kanallari " TR" son ekiyle suzulur). Oynatilacak
         * player sayfasi StreamResolver ile HLS'e cevrilir.
         */
        private const val NTVSTREAM_SOURCE_ID = "ntvstream"
        private const val NTVSTREAM_CHANNELS_URL =
            "https://cdnlivetv.tv/api/v1/channels/?code=us&user=ntvstream&plan=free"

        /**
         * SportsBite TR kanallari icin gomulu M3U.
         *
         * - URL'ler `https://sportsbite.org/247/{slug}` (canli HLS playlist'i;
         *   ExoPlayer periyodik yeniler).
         * - Site tarayici User-Agent'i sartsiz 403 dondurur; UA
         *   `#EXTVLCOPT:http-user-agent` ile gonderilir (M3uParser ->
         *   candidate.headers -> HeaderDataSourceFactory tum isteklere uygular).
         * - Kanal adlari diger kaynaklarla ayni yazimda tutulur ki ayni kanal
         *   adaylar altinda birlesin ("BeIN Sports 1" = "BeIN Sports 1").
         */
        private fun buildSportsBitePlaylist(): String {
            val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            val logos = "https://raw.githubusercontent.com/tv-logo/tv-logos/refs/heads/main/countries"
            val channels = listOf(
                Triple("beinsports1tr", "BeIN Sports 1", "$logos/international/beinsports/old/stacked/bein-sports-1-int.png"),
                Triple("beinsports2tr", "BeIN Sports 2", "$logos/international/beinsports/old/stacked/bein-sports-2-int.png"),
                Triple("beinsports3tr", "BeIN Sports 3", "$logos/international/beinsports/old/stacked/bein-sports-3-int.png"),
                Triple("beinsport4tr", "BeIN Sports 4", "$logos/international/beinsports/old/stacked/bein-sports-4-int.png"),
                Triple("beinsports5tr", "BeIN Sports 5", "$logos/international/beinsports/old/stacked/bein-sports-5-int.png"),
                Triple("asportr", "A Spor", "$logos/turkey/a-spor-tr.png"),
                Triple("euro1tr", "Euro Sport 1", "$logos/france/eurosport-1-fr.png"),
                Triple("euro2tr", "Euro Sport 2", "$logos/france/eurosport-2-fr.png"),
                Triple("tivibuspor", "Tivibu Spor 1", "https://i.imgur.com/izEOHO0.png"),
                Triple("tivibuspor2", "Tivibu Spor 2", "https://i.imgur.com/RB9pUup.png"),
                Triple("tivibuspor3", "Tivibu Spor 3", "https://i.imgur.com/nj3dJ3H.png"),
                Triple("trtspor", "TRT Spor", "$logos/turkey/trt-spor-tr.png"),
                Triple("trtspory", "TRT Spor Yildiz", "$logos/turkey/trt-spor-yildiz-tr.png"),
                Triple("sportstv", "Sports Tv", "$logos/turkey/sports-tv-tr.png"),
            )
            return buildString {
                appendLine("#EXTM3U")
                channels.forEach { (slug, name, logo) ->
                    appendLine("#EXTINF:-1 tvg-id=\"$name\" tvg-logo=\"$logo\" group-title=\"SportsBite\",$name")
                    appendLine("#EXTVLCOPT:http-user-agent=$ua")
                    appendLine("https://sportsbite.org/247/$slug")
                    appendLine()
                }
            }
        }
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

    /** Oncelikle onbellegi okur (aninda liste); gomulu liste varsa onu yedek alir. */
    fun readCache(): LoadResult {
        val parsed = DEFAULT_SOURCES.mapNotNull { source ->
            val entries = readSourceCache(source)
                ?: source.embeddedPlaylist?.let { M3uParser.parse(it) }?.takeIf { it.isNotEmpty() }
            entries?.let { source to it }
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
                        // Gomulu liste (SportsBite) uzak cekim yerine kullanilir;
                        // degistirilebilir olmadigi icin onbellege de yazilmaz.
                        val body = source.embeddedPlaylist ?: run {
                            val decoded = when (source.id) {
                                // Vavoo: katalogdan TR spor kanallari uretilir.
                                VAVOO_SOURCE_ID -> buildVavooPlaylist()
                                // NTV Stream: kanal listesi JSON'indan TR spor kanallari
                                // uretilir; oynatmada player sayfasi StreamResolver ile
                                // HLS'e cevrilir.
                                NTVSTREAM_SOURCE_ID -> buildNtvStreamPlaylist()
                                else -> {
                                    val request = Request.Builder()
                                        .url(source.url)
                                        .header("User-Agent", "smTV-One/1.0")
                                        .build()
                                    okHttpClient.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                                        val raw = response.body?.string().orEmpty()
                                        // Bazi kaynaklar (orn. AtomSpor) playlist'i karakter
                                        // kodlari dizisi olarak verir; oynatmadan once coz.
                                        decodeIfObfuscated(raw)
                                    }
                                }
                            }
                            if (decoded.isBlank()) throw IOException("Bos liste govdesi")
                            runCatching {
                                cacheFile(source.id).parentFile?.mkdirs()
                                cacheFile(source.id).writeText(decoded, Charsets.UTF_8)
                            }
                            decoded
                        }
                        val parsedEntries = M3uParser.parse(body)
                        android.util.Log.i(
                            "SportsRepo",
                            "fetch ${source.id}: body=${body.length}B " +
                                "satirSayisi=${body.lines().size} parse=${parsedEntries.size} " +
                                "adlar=${parsedEntries.joinToString("|") { it.name }}",
                        )
                        parsedEntries
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

    // ---------------------------------------------------------------------
    // Vavoo (geil.to)
    // ---------------------------------------------------------------------

    private data class VavooItem(
        val id: String,
        val name: String,
        val url: String,
        val logo: String?,
    )

    /**
     * Vavoo katalogdan TR spor kanallarini cekip M3U uretir.
     *
     * - `?search=SPOR` ile spor kanallari (300/sayfa, `nextCursor` sayfalama),
     *   `tags` icinde "Turkey" olanlar suzulur.
     * - Cursor istekleri hiz limitinde 404 dondurur; bekleme + tekrar ile asilir.
     * - URL'ler `https://geil.to/live/play/{id}`; bunlar HLS degildir,
     *   oynatmada StreamResolver `geil.to/live/resolve` ile cevirir.
     * - Kanal adlari diger kaynaklarla ayni yazimda tutulur ki ayni kanal
     *   adaylar altinda birlesin.
     */
    private suspend fun buildVavooPlaylist(): String {
        val items = LinkedHashMap<String, VavooItem>()
        var cursor: String? = null
        var page = 0
        while (page < VAVOO_MAX_PAGES) {
            val pageUrl = buildString {
                append(VAVOO_CATALOG_URL)
                append("?search=SPOR")
                cursor?.let { append("&cursor=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val json = JSONObject(fetchPageWithRetry(pageUrl))
            val array = json.optJSONArray("items") ?: break
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.optString("id")
                val url = entry.optString("url")
                if (id.isEmpty() || url.isEmpty()) continue
                if (!jsonArrayContains(entry.optJSONArray("tags"), "Turkey")) continue
                val logo = entry.optJSONObject("images")?.optString("logo")
                    ?.takeIf { it.isNotBlank() }
                items[id] = VavooItem(id, entry.optString("name"), url, logo)
            }
            page++
            val next = json.opt("nextCursor")
            if (next == null || next === JSONObject.NULL) break
            cursor = next.toString()
            delay(1_500) // hiz limiti: sayfalar arasi bekleme
        }
        if (items.isEmpty()) throw IOException("Vavoo katalogunda TR kanal yok")
        android.util.Log.i("SportsRepo", "vavoo: ${items.size} TR kanal")
        return buildString {
            appendLine("#EXTM3U")
            items.values.forEach { item ->
                val name = sportsDisplayName(item.name)
                val logoUrl = item.logo?.let { if (it.startsWith("http")) it else "https://geil.to$it" }
                val logoAttr = logoUrl?.let { " tvg-logo=\"$it\"" }.orEmpty()
                appendLine("#EXTINF:-1 tvg-id=\"$name\"$logoAttr group-title=\"Vavoo\",$name")
                appendLine(item.url)
                appendLine()
            }
        }
    }

    /**
     * NTV Stream (cdnlivetv.tv) TR spor kanallarini uretir.
     *
     * - Tek istekte gelen kanal listesi JSON'u (`total_channels`/`channels`);
     *   " TR" son eki olan spor kanallari suzulur (beIN SPORTS 1-4 FHD TR,
     *   TRT Spor FHD TR). `status` ALANINA GORE SUZULMEZ: deger dinamiktir
     *   (maclar sirasinda "offline" doner) ve yanilticidir -- oynatilabilirlik
     *   oynatim sirasinda resolve + player hata mekanizmasiyla belirlenir.
     * - URL'ler player sayfasidir (HTML); oynatmada StreamResolver sayfayi
     *   indirip gizli HLS stream URL'ini cozer. Token zamanla yenilendigi icin
     *   cozum her oynatista taze yapilir.
     */
    private suspend fun buildNtvStreamPlaylist(): String {
        val json = JSONObject(fetchPageWithRetry(NTVSTREAM_CHANNELS_URL))
        val array = json.optJSONArray("channels")
            ?: throw IOException("NTV Stream kanal listesi yok")
        var count = 0
        val playlist = buildString {
            appendLine("#EXTM3U")
            for (index in 0 until array.length()) {
                val channel = array.optJSONObject(index) ?: continue
                val rawName = channel.optString("name")
                val url = channel.optString("url")
                if (rawName.isEmpty() || url.isEmpty()) continue
                if (!isNtvTrSportsChannel(rawName)) continue
                val name = sportsDisplayName(rawName)
                val logo = channel.optString("image").takeIf { it.isNotBlank() }
                val logoAttr = logo?.let { " tvg-logo=\"$it\"" }.orEmpty()
                appendLine("#EXTINF:-1 tvg-id=\"$name\"$logoAttr group-title=\"NTV Stream\",$name")
                appendLine(url)
                appendLine()
                count++
            }
        }
        if (count == 0) throw IOException("NTV Stream'de TR spor kanal yok")
        android.util.Log.i("SportsRepo", "ntvstream: $count TR spor kanal")
        return playlist
    }

    /** "beIN SPORTS 1 FHD TR" / "TRT Spor FHD TR" gibi TR spor kanallari. */
    private fun isNtvTrSportsChannel(name: String): Boolean =
        name.endsWith(" TR", ignoreCase = true) &&
            Regex("bein|spor|sport|tivibu|euro|nba|tabii|smart", RegexOption.IGNORE_CASE)
                .containsMatchIn(name)

    /** Uzak sayfayi ceker; 404/503 (hiz limiti) ve ag hatalarinda artan bekleme ile tekrar dener. */
    private suspend fun fetchPageWithRetry(url: String): String {
        var lastError = "bilinmiyor"
        repeat(VAVOO_FETCH_ATTEMPTS) { attempt ->
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", VAVOO_USER_AGENT)
                if (url.contains("cdnlivetv.tv")) builder.header("Referer", "https://ntv.st/")
                val request = builder.build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        if (body.isNotBlank()) return body
                        lastError = "bos govde"
                    } else {
                        lastError = "HTTP ${response.code}"
                    }
                }
            } catch (error: IOException) {
                lastError = error.message ?: "IO hatasi"
            }
            delay(3_000L * (attempt + 1))
        }
        throw IOException("Katalog sayfasi alinamadi ($lastError)")
    }

    private fun jsonArrayContains(array: JSONArray?, value: String): Boolean {
        if (array == null) return false
        for (index in 0 until array.length()) {
            if (array.optString(index) == value) return true
        }
        return false
    }

    /**
     * Spor kanal adini diger kaynaklarla ayni yazima cevirir (vavoo + ntvstream).
     *
     * Ham adlar ornek: "4K TR: A SPOR HD (1)", "BEIN SPORTS 1 [LIVE DURING
     * EVENTS ONLY] (7)", "beIN SPORTS 1 FHD TR". Once onek/kose/sufe/etiketler
     * temizlenir, sonra bilinen kanallar icin sabit yazim kullanilir
     * ("BeIN Sports 1", "A Spor"...); bilinmeyenler bas harf buyuk sekline
     * cevrilir.
     */
    private fun sportsDisplayName(rawName: String): String {
        var name = rawName.trim().removePrefix("4K TR: ")
        name = name.replace('-', ' ') // "S-SPORT 1" -> "S SPORT 1"
        name = name.replace(Regex("\\[[^\\]]*\\]"), " ") // [LIVE DURING EVENTS ONLY]
        name = name.replace(Regex("\\s*\\([^)]*\\)"), " ") // (1) / (720P)
        name = name.replace(
            Regex(
                "\\b(?:FHD|UHD|4K|8K|HD|SD|HEVC|H\\.?26[45]|RAW|FEED|BACKUP|" +
                    "50\\s+FPS|60\\s+FPS|720P|1080P|1080I|360P|TR)\\b",
            ),
            " ",
        )
        name = name.replace(Regex("\\s+"), " ").trim().trimEnd('+', '-', ' ').trim()
        if (name.isEmpty()) return rawName.trim()
        val upper = name.uppercase(Locale.ROOT)
        VAVOO_CANONICAL_NAMES[upper]?.let { return it }
        Regex("^BEIN SPORTS MAX ?(\\d+)$").matchEntire(upper)?.let {
            return "BeIN Sports Max ${it.groupValues[1]}"
        }
        Regex("^BEIN SPORTS (\\d+)$").matchEntire(upper)?.let {
            return "BeIN Sports ${it.groupValues[1]}"
        }
        Regex("^(?:TIVIBUSPOR|TIVIBU SPOR) ?(\\d+)$").matchEntire(upper)?.let {
            return "Tivibu Spor ${it.groupValues[1]}"
        }
        Regex("^(?:SPOR SMART|SMART SPOR) ?(\\d+)$").matchEntire(upper)?.let {
            return "Smart Spor ${it.groupValues[1]}"
        }
        return titleCaseAscii(name)
    }

    /** Bilinen TR spor kanallari icin diger kaynaklarla ayni yazim. */
    private val VAVOO_CANONICAL_NAMES = mapOf(
        "A SPOR" to "A Spor",
        "S SPORT" to "S Sport",
        "S SPORT 1" to "S Sport",
        "S SPORT 2" to "S Sport 2",
        "S SPORTS 1" to "S Sport",
        "S SPORTS 2" to "S Sport 2",
        "SPOR SMART" to "Smart Spor",
        "SMART SPOR" to "Smart Spor",
        "TRT SPOR" to "TRT Spor",
        "TRT SPOR YILDIZ" to "TRT Spor Yildiz",
        "EUROSPORT 1" to "Euro Sport 1",
        "EUROSPORT 2" to "Euro Sport 2",
        "SPORTS TV" to "Sports Tv",
        "TIVIBUSPOR SPOR" to "Tivibu Spor",
    )

    /** Kisa marka/kratlamlar buyuk harf kalir (TRT, TV, HT, S, MAX). */
    private val VAVOO_KEEP_UPPERCASE = setOf("TRT", "TV", "HT", "S", "MAX")

    /** Marka yazimlari bas harf buyukte bozulmasin. */
    private val VAVOO_WORD_CANONICAL = mapOf("BEIN" to "BeIN")

    private fun titleCaseAscii(text: String): String =
        text.split(Regex("\\s+")).joinToString(" ") { word ->
            val upper = word.uppercase(Locale.ROOT)
            when {
                upper in VAVOO_KEEP_UPPERCASE -> upper
                else -> VAVOO_WORD_CANONICAL[upper]
                    ?: word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
        }
}
