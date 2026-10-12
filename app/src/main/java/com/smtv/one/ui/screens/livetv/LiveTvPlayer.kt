package com.smtv.one.ui.screens.livetv

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.smtv.one.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Liste verilmemişse gönderilecek varsayılan User-Agent. */
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) smTV-One/1.0 ExoPlayer Media3"

/**
 * Bazı yayın sunucuları `Accept-Language` başlığı gelmediğinde Cloudflare
 * üzerinden 403 döner; M3U'daki `#EXTVLCOPT` satırları bu başlığı taşımadığından
 * oynatıcı isteklerine varsayılan olarak eklenir.
 */
private const val DEFAULT_ACCEPT_LANGUAGE = "tr-TR,tr;q=0.9,en;q=0.8"

/** Canli yayin tamponu: allocator segmenti (64 KB) ve native ust sinir (48 MB). */
private const val LIVE_ALLOCATOR_SEGMENT_SIZE = 64 * 1024
private const val LIVE_TARGET_BUFFER_BYTES = 48 * 1024 * 1024

/**
 * Her istekte M3U'dan gelen header'ları (Referer / User-Agent / Origin) ekleyen veri kaynağı.
 * Kanal değiştiğinde [headers] güncellenir; bir sonraki bağlantı yeni header ile açılır.
 */
@OptIn(markerClass = [UnstableApi::class])
class HeaderDataSourceFactory : DataSource.Factory {

    @Volatile
    var headers: Map<String, String> = emptyMap()

    override fun createDataSource(): DataSource {
        val requestHeaders = LinkedHashMap(headers)
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestHeaders["User-Agent"] = DEFAULT_USER_AGENT
        }
        if (requestHeaders.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
            requestHeaders["Accept-Language"] = DEFAULT_ACCEPT_LANGUAGE
        }
        return DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
    }
}

private val probeClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/**
 * Canli yayin (IPTV + Spor) icin ayarli tamponlama.
 *
 * Varsayilan DefaultLoadControl dengesiz IPTV kaynaklarinda tamponu hizli
 * tukettigi icin sik rebuffera (kasma/donma) yol aciyordu. Rebuffer sonrasi
 * esik yuksek tutularak daha fazla veri toplanir; boylece kisa ag
 * dalgalanmalari gorunur takilmaya donusmez. Canli yayinda geri sarma
 * olmadigi icin buyuk tampon yalnizca gecikme artirir, kayip yaratmaz.
 */
@OptIn(markerClass = [UnstableApi::class])
private fun buildLiveLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder()
        // Tampon verisi Java heap'inde degil native bellekte tutulur. TV'nin
        // ~65 MB'lik heap limitinde varsayilan allocator her saniye MB'larca
        // byte[] uretir; 3-7 sn'de bir GC dongusu ve GC yarisi takilmaya
        // yol aciyordu (logda "GC freed 18MB ... total 1.2s" goruluyordu).
        .setAllocator(DefaultAllocator(true, LIVE_ALLOCATOR_SEGMENT_SIZE))
        .setTargetBufferBytes(LIVE_TARGET_BUFFER_BYTES)
        .setBufferDurationsMs(
            /* minBufferMs= */ 30_000,
            /* maxBufferMs= */ 60_000,
            /* bufferForPlaybackMs= */ 2_500,
            /* bufferForPlaybackAfterRebufferMs= */ 8_000,
        )
        // byte hedefi native bellekte sert sinir; sure esikleri oynatma
        // baslama ve rebuffer sonrasi toplama esigini belirler.
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

/** Uzantıdan içerik türü tahmini (.m3u8 → HLS vb.). */
private fun knownMimeTypeFor(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> "application/x-mpegURL"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> "video/mp4"
        path.endsWith(".ts") -> "video/mp2t"
        path.endsWith(".mkv") || path.endsWith(".webm") -> "video/mp4"
        else -> null
    }
}

/** Content-Type başlığından oynatıcı içerik türü üretir. */
private fun mimeFromContentType(raw: String?): String? {
    val contentType = raw?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return when {
        "mpegurl" in contentType -> "application/x-mpegURL"
        "dash" in contentType -> "application/dash+xml"
        "mp4" in contentType -> "video/mp4"
        "mpeg-ts" in contentType || "mp2t" in contentType -> "video/mp2t"
        else -> null
    }
}

/**
 * Uzantısız adresler için Content-Type taranır: önce HEAD, olmazsa Range ile
 * kısa GET. Listeler HLS ağırlıklı olduğundan sonuç alınamazsa HLS varsayılır.
 */
private suspend fun probeMimeType(url: String, headers: Map<String, String>): String? =
    withContext(Dispatchers.IO) {
        fun request(method: String, range: Boolean): Request {
            val builder = Request.Builder().url(url).method(method, null)
            headers.forEach { (key, value) -> builder.header(key, value) }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", DEFAULT_USER_AGENT)
            }
            if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
                builder.header("Accept-Language", DEFAULT_ACCEPT_LANGUAGE)
            }
            if (range) builder.header("Range", "bytes=0-0")
            return builder.build()
        }

        val headResult = runCatching {
            probeClient.newCall(request("HEAD", range = false)).execute().use { response ->
                if (response.isSuccessful) mimeFromContentType(response.header("Content-Type")) else null
            }
        }.getOrNull()
        headResult ?: runCatching {
            probeClient.newCall(request("GET", range = true)).execute().use { response ->
                if (response.isSuccessful) mimeFromContentType(response.header("Content-Type")) else null
            }
        }.getOrNull()
    }

/** Oynatıcı durumunu Compose'a açan tutamak (tek ExoPlayer ömrü boyunca yaşar). */
@Stable
@OptIn(markerClass = [UnstableApi::class])
class LiveTvPlayerHandle internal constructor(
    private val player: ExoPlayer,
    private val headerFactory: HeaderDataSourceFactory,
) {
    var playbackState: Int by mutableStateOf(Player.STATE_IDLE)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Oynatma henüz başlamadı (adres/Content-Type taraması sürüyor). */
    var isPreparing: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    /** Hata sayısı arttıkça arayüz otomatik yeniden dener. */
    var errorCount: Int by mutableStateOf(0)
        private set

    val exoPlayer: ExoPlayer get() = player

    val durationMs: Long
        get() = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state != Player.STATE_IDLE) isPreparing = false
                if (state == Player.STATE_READY) errorMessage = null
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                isPreparing = false
                errorMessage = error.message ?: error.errorCodeName
                Log.w("LiveTvPlayer", "Oynatma hatasi: $errorMessage")
                errorCount++
            }
        })
    }

    /** Son hazırlanan içerik; gözcü IDLE takılmasında bununla yeniden hazırlar. */
    private var lastMediaItem: MediaItem? = null

    /** Yeni kaynağı hazırlar; header'lar bağlantılara uygulanır. */
    suspend fun play(url: String?, headers: Map<String, String>) {
        if (url.isNullOrBlank()) {
            isPreparing = false
            lastMediaItem = null
            player.stop()
            player.clearMediaItems()
            return
        }
        errorMessage = null
        isPreparing = true
        headerFactory.headers = headers

        val mimeType = knownMimeTypeFor(url)
            ?: probeMimeType(url, headers)
            ?: "application/x-mpegURL" // uzantısız uçlar IPTV'de çoğunlukla HLS

        val item = MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
        lastMediaItem = item
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Hata sonrası yeniden dener. Oynatıcı hazirsa (failover calisan adaya
     * gectiyse) dokunmaz: prepare() durum degistirmezse isPreparing takilir kaliyor.
     */
    fun retry() {
        if (player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_READY) return // zaten oynuyor
        errorMessage = null
        isPreparing = true
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Oynatıcı hata ya da ağ kesintisi sonrası IDLE'da takılıp kalmışsa son
     * içerikle yeniden hazırlar (ağ döndüğünde yayın kendiliğinden başlar).
     */
    fun reviveIfIdle() {
        val item = lastMediaItem ?: return
        if (player.playbackState != Player.STATE_IDLE) return
        errorMessage = null
        isPreparing = true
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun release() {
        player.release()
    }
}

/** Oynatıcıyı yaratır ve ekrandan kaldırılınca serbest bırakır. */
@Composable
@OptIn(markerClass = [UnstableApi::class])
fun rememberLiveTvPlayer(): LiveTvPlayerHandle {
    val context = LocalContext.current
    val handle = remember {
        val factory = HeaderDataSourceFactory()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(buildLiveLoadControl())
            .build()
        LiveTvPlayerHandle(player, factory)
    }

    // Hata sonrası 5 sn'de bir otomatik yeniden deneme
    LaunchedEffect(handle.errorCount) {
        if (handle.errorCount > 0) {
            delay(5_000)
            handle.retry()
        }
    }

    // Gözcü: yayın açılışta/ağ kesintisinde IDLE'da takılıp kaldıysa 10 sn'de
    // bir son içerikle yeniden hazırla; ağ döndüğünde yayın kendiliğinden başlar.
    LaunchedEffect(handle) {
        while (true) {
            delay(10_000)
            handle.reviveIfIdle()
        }
    }

    DisposableEffect(handle) {
        onDispose { handle.release() }
    }
    return handle
}

/** Video yüzeyi + tamponlama/hata katmanı. */
@Composable
@OptIn(markerClass = [UnstableApi::class])
fun LiveTvPlayerSurface(
    handle: LiveTvPlayerHandle,
    modifier: Modifier = Modifier,
) {
    val errorRetryText = stringResource(R.string.livetv_error_retry)

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = handle.exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = handle.exoPlayer },
        )

        if (handle.isPreparing || handle.playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(46.dp),
                color = Color(0xFF3D8BFF),
                strokeWidth = 3.dp,
            )
        }

        val error = handle.errorMessage
        if (error != null && handle.playbackState == Player.STATE_IDLE) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.livetv_playback_error),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$error — $errorRetryText",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}
