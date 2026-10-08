package com.smtv.one.ui.screens.detail

import android.text.format.DateFormat
import com.smtv.one.core.util.parseEpisodeReleaseLocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatReleaseDate(isoDate: String): String {
    val locale = Locale.getDefault()
    val releaseDate = parseEpisodeReleaseLocalDate(isoDate) ?: return ""
    val pattern = DateFormat.getBestDateTimePattern(locale, "dMMMMy")
    return DateTimeFormatter.ofPattern(pattern, locale).format(releaseDate)
}
