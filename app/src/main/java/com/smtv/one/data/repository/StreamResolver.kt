package com.smtv.one.data.repository

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Oynatma oncesi URL cozucu.
 *
 * - **Vavoo (geil.to)**: `geil.to/live/play/{id}` linkleri dogrudan HLS degildir;
 *   `geil.to/live/resolve?url=...` ile canli HLS master playlist'ine cevrilir.
 * - **NTV Stream (cdnlivetv.tv)**: kanal listesi JSON'u player sayfasi (HTML)
 *   verir; sayfadaki obfuscate edilmis script stream URL'ini base64 parcalarindan
 *   kurar (decoder/degisken adlari her istekte rotasyonludur).
 *
 * Her iki cozum de her oynatista taze yapilir (token zamanla yenilenir).
 * Basarisizlikta null donulur; cagiran orijinal URL ile devam ederek mevcut
 * "sonraki adaya gec" mekanizmasina birakir.
 */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val GEIL_PLAY_PREFIX = "https://geil.to/live/play/"
    private const val GEIL_RESOLVE_BASE = "https://geil.to/live/resolve?url="
    private const val NTV_PLAYER_PREFIX = "https://cdnlivetv.tv/api/v1/channels/player/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 10_000

    private val FN_REGEX = Regex("""function\s+(\w+)\s*\(\s*\w\s*\)\s*\{""")
    private val VAR_REGEX = Regex("""var\s+(\w+)\s*=\s*['"]([^'"]*)['"]""")

    fun needsResolve(url: String?): Boolean =
        url != null && (url.startsWith(GEIL_PLAY_PREFIX) || url.startsWith(NTV_PLAYER_PREFIX))

    /** URL'i oynatilabilir HLS'e cevirir; basarisizsa null. */
    suspend fun resolve(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.startsWith(GEIL_PLAY_PREFIX) -> resolveGeil(url)
                url.startsWith(NTV_PLAYER_PREFIX) -> resolveNtvStream(url)
                else -> null
            }
        }.getOrElse { error ->
            Log.w(TAG, "resolve hatali: ${error.message}")
            null
        }
    }

    // ---------------------------------------------------------------------
    // Vavoo: geil.to resolve ucu
    // ---------------------------------------------------------------------

    private fun resolveGeil(playUrl: String): String? {
        val endpoint = GEIL_RESOLVE_BASE + URLEncoder.encode(playUrl, "UTF-8")
        val body = httpGet(endpoint, referer = null) ?: return null
        return runCatching { JSONObject(body).optString("url").trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------------
    // NTV Stream: player sayfasindaki obfuscate stream URL'i
    // ---------------------------------------------------------------------

    private fun resolveNtvStream(playerUrl: String): String? {
        val html = httpGet(playerUrl, referer = "https://ntv.st/") ?: return null
        return extractNtvStreamUrl(html)
    }

    /**
     * Player HTML'inden stream URL'ini cozer.
     *
     * Sayfa URL'i `AD(a)+AD(b)+...` biciminde birlestirir; decoder fonksiyon
     * adi ve degisken adlari her istekte rotasyonludur. `atob(` konumundan
     * geriye bakilarak decoder bulunur, concat ifadesi cozulur ve base64url
     * parcalar sirayla birlestirilir.
     */
    private fun extractNtvStreamUrl(html: String): String? {
        val atobIndex = html.indexOf("atob(")
        if (atobIndex < 0) return null
        val before = html.substring(maxOf(0, atobIndex - 500), atobIndex)
        val decoder = FN_REGEX.findAll(before).lastOrNull()?.groupValues?.get(1) ?: return null
        val escaped = Regex.escape(decoder)
        val callRegex = Regex("""var\s+(\w+)\s*=\s*(?:$escaped\(\w+\)\s*\+\s*)+$escaped\(\w+\)""")
        val call = callRegex.find(html) ?: return null
        val decls = mutableMapOf<String, String>()
        for (match in VAR_REGEX.findAll(html)) {
            decls[match.groupValues[1]] = match.groupValues[2]
        }
        val argRegex = Regex("""$escaped\((\w+)\)""")
        val result = StringBuilder()
        for (match in argRegex.findAll(call.value)) {
            val encoded = decls[match.groupValues[1]] ?: return null
            result.append(decodeBase64Url(encoded) ?: return null)
        }
        val streamUrl = result.toString()
        if (!streamUrl.startsWith("http")) {
            Log.w(TAG, "ntvstream beklenmeyen stream URL: $streamUrl")
            return null
        }
        return streamUrl
    }

    private fun decodeBase64Url(value: String): String? = runCatching {
        var normalized = value.replace('-', '+').replace('_', '/')
        while (normalized.length % 4 != 0) normalized += "="
        String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    // ---------------------------------------------------------------------

    private fun httpGet(url: String, referer: String?): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        referer?.let { connection.setRequestProperty("Referer", it) }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GET HTTP ${connection.responseCode}: $url")
                return null
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
