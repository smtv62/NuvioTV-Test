package com.smtv.one.domain.model

/**
 * Tek bir spor kaynağından gelen yayın adayı.
 *
 * Aynı kanal/etkinlik birden fazla kaynakta bulunabilir (TRGoals, AtomSpor,
 * BirazSpor, SLCK...); uygulama oynatma anında adayları sırayla dener
 * (film/dizi tarafındaki çoklu-kaynak akış araması gibi).
 */
data class SportsCandidate(
    val sourceId: String,
    val sourceName: String,
    /** Yayin stream adresi. */
    val url: String,
    /** Yayin icin gereken HTTP header'lari (Referer / User-Agent vb.). */
    val headers: Map<String, String> = emptyMap(),
)

/** Tum kaynaklardan birlestirilmis spor kanali / canli etkinlik. */
data class SportsChannel(
    val id: String,
    val number: Int,
    val name: String,
    /** Logo gorseli yoksa plakada gosterilecek kisa yazi. */
    val shortLabel: String,
    val logo: LiveLogoStyle,
    val logoUrl: String? = null,
    /** Bu kanalin geldigi kaynak adlari (gorsel ipucu). */
    val sourceNames: List<String> = emptyList(),
    /** Oynatilabilecek adaylar (ilk once denenir). */
    val candidates: List<SportsCandidate> = emptyList(),
)
