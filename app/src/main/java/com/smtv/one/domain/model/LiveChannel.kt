package com.smtv.one.domain.model

/** Logo plakası için zemin/ön plan renkleri (ARGB, Long). */
data class LiveLogoStyle(val background: Long, val foreground: Long)

/** Canlı yayın kanalı (M3U listesinden türetilir). */
data class LiveChannel(
    val id: String,
    val number: Int,
    val name: String,
    /** Logo görseli yoksa plakada gösterilecek kısa yazı. */
    val shortLabel: String,
    val logo: LiveLogoStyle,
    val logoUrl: String? = null,
    val group: String = "Genel",
    /** Yayın stream adresi. */
    val url: String? = null,
    /** Yayın için gereken HTTP header'ları (Referer / User-Agent vb.). */
    val headers: Map<String, String> = emptyMap(),
)

/** Kanal adından marka plakası üreten yardımcılar (eski smTV uygulamasından port). */
object LiveChannelFormatting {

    fun shortLabelFor(name: String): String {
        val compact = name.trim()
        if (compact.length <= 8) return compact.uppercase()
        val words = compact.split(Regex("[\\s\\-]+")).filter { it.isNotBlank() }
        return if (words.size >= 2) {
            words.take(3).joinToString("") { it.first().uppercase() }
        } else {
            compact.take(7).uppercase()
        }
    }

    /** İsimden kararlı (aynı isim → aynı renk) marka rengi üretir. */
    fun colorPairFor(name: String): Pair<Long, Long> {
        val hash = name.lowercase().hashCode().toLong()
        val size = CHANNEL_TOPS.size
        val index = (hash % size).toInt().let { if (it < 0) it + size else it }
        return CHANNEL_TOPS[index] to CHANNEL_BOTTOMS[index]
    }

    private val CHANNEL_TOPS = listOf(
        0xFF10203CL, 0xFF243B55L, 0xFF3A1F3DL, 0xFF123A34L, 0xFF3D2A14L,
        0xFF2B2B33L, 0xFF0B2E66L, 0xFF402020L,
    )
    private val CHANNEL_BOTTOMS = listOf(
        0xFF060C18L, 0xFF081726L, 0xFF140814L, 0xFF04120FL, 0xFF150D04L,
        0xFF0C0C11L, 0xFF031024L, 0xFF160707L,
    )
}
