package com.smtv.one.data.m3u

/**
 * Standart M3U / M3U8 playlist çözümleyici.
 *
 * Desteklenen biçim:
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-id="TV8,5" tvg-logo="http://..." group-title="Ulusal",TV 8.5
 * #EXTVLCOPT:http-referrer=https://site.com/
 * #EXTVLCOPT:http-user-agent=Mozilla/5.0 ...
 * http://ornek.com/live/tv85.m3u8
 * ```
 *
 * Dikkat edilenler:
 * - Tırnak içindeki virgüller (`tvg-id="TV8,5"`) başlık ayracına karıştırılmaz.
 * - `#EXTVLCOPT` referrer/user-agent değerleri oynatıcıya header olarak taşınır
 *   (birçok kaynak bu header olmadan stream açmaz).
 * - `#EXTGRP` group-title yerine kullanılır.
 */
object M3uParser {

    data class Entry(
        val name: String,
        val logoUrl: String?,
        val group: String?,
        val tvgId: String?,
        val url: String,
        val durationSeconds: Int,
        /** Oynatıcıya gönderilecek HTTP header'ları (ör. Referer, User-Agent). */
        val headers: Map<String, String>,
    )

    fun parse(content: String): List<Entry> {
        val entries = mutableListOf<Entry>()

        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingId: String? = null
        var pendingDuration = -1
        var pendingExtGrp: String? = null
        var pendingHeaders = linkedMapOf<String, String>()

        fun reset() {
            pendingName = null
            pendingLogo = null
            pendingGroup = null
            pendingId = null
            pendingDuration = -1
            pendingExtGrp = null
            pendingHeaders = linkedMapOf()
        }

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() || line.startsWith("#EXTM3U", ignoreCase = true) -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val info = line.substringAfter(":")
                    pendingDuration = info.takeWhile { it != ' ' && it != ',' }.toIntOrNull() ?: -1

                    // Başlık ayracı: tırnak dışındaki ilk virgül.
                    val sep = separatorIndex(info)
                    val attrs = if (sep >= 0) info.substring(0, sep) else info
                    val title = if (sep >= 0) info.substring(sep + 1).trim() else ""

                    pendingLogo = attribute(attrs, "tvg-logo")
                    pendingGroup = attribute(attrs, "group-title")
                    pendingId = attribute(attrs, "tvg-id")
                    pendingName = title.ifEmpty { pendingId }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingExtGrp = line.substringAfter(":").trim()
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(":")
                    val key = opt.substringBefore("=").trim().lowercase()
                    val value = opt.substringAfter("=", missingDelimiterValue = "").trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "http-referrer", "referrer" -> pendingHeaders["Referer"] = value
                            "http-user-agent", "user-agent" -> pendingHeaders["User-Agent"] = value
                            "http-origin", "origin" -> pendingHeaders["Origin"] = value
                        }
                    }
                }

                line.startsWith("#") -> Unit // diğer etiketler yok sayılır

                else -> {
                    val url = line
                    if (url.startsWith("http", ignoreCase = true)) {
                        val name = pendingName?.takeIf { it.isNotBlank() }
                            ?: pendingId
                            ?: url.substringAfterLast('/')
                        entries += Entry(
                            name = name,
                            logoUrl = pendingLogo,
                            group = pendingGroup ?: pendingExtGrp,
                            tvgId = pendingId,
                            url = url,
                            durationSeconds = pendingDuration,
                            headers = pendingHeaders.toMap(),
                        )
                    }
                    reset()
                }
            }
        }
        return entries
    }

    /** Tırnak dışındaki ilk virgülün indeksi (başlık ayracı); yoksa -1. */
    private fun separatorIndex(info: String): Int {
        var inQuote = false
        var i = 0
        while (i < info.length) {
            when (info[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return i
            }
            i++
        }
        return -1
    }

    private fun attribute(source: String, key: String): String? {
        val quoted = Regex("""$key="([^"]*)"""").find(source)?.groupValues?.get(1)
        if (quoted != null) return quoted.trim().ifEmpty { null }
        // tırnaksız varyant: group-title=Spor
        return Regex("""$key=([^,\s]+)""").find(source)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }
}
