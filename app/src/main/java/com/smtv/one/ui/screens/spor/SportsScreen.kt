@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.smtv.one.ui.screens.spor

import android.app.Activity
import android.view.KeyEvent as AndroidKeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.smtv.one.R
import com.smtv.one.domain.model.SportsCandidate
import com.smtv.one.domain.model.SportsChannel
import com.smtv.one.ui.screens.livetv.LiveTvPlayerHandle
import com.smtv.one.ui.screens.livetv.rememberLiveTvPlayer
import com.smtv.one.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val StageAccent = Color(0xFF3D8BFF)
private val PanelWidthMax = 440.dp
private val PanelStartPadding = 72.dp

/**
 * Spor ekrani: tum kaynaklardan birlestirilmis kanal/etkinlik listesi +
 * tam ekran yayin sahnesi.
 *
 * - Kullanici sadece kanali/etkinligi secer; uygulama aday kaynaklari
 *   sirayla dener (film/dizi tarafindaki coklu-kaynak aramasi gibi).
 * - Ustte kaynak filtre chipleri (Tumu + TRGoals/AtomSpor/Birazspor/SLCK).
 * - Panel 8 sn hareketsizlikte otomatik gizlenir; BACK gizliyse acar.
 */
@Composable
fun SportsScreen() {
    val viewModel: SportsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Yayin boyunca ekran uyumasin.
    val activity = context as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val handle = rememberLiveTvPlayer()
    val channels = remember(state) { state.filteredChannels }

    // Panel gezinmesi: yon tuslari satir/cip/baslik hedefleri arasinda
    // FocusRequester'larla kendi mantigimizla tasinir.
    val listFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Gezinme gezgini: art arda basılan tuşlarda önceki kayma iptal edilir; böylece
    // liste zıplamak yerine bulunduğumuz yerden hedefe yumuşak kayar.
    var navJob by remember { mutableStateOf<Job?>(null) }

    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val chipRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val refreshFocus = remember { FocusRequester() }
    var focusZone by remember { mutableStateOf(PanelZone.NONE) }
    var rowIndex by remember { mutableIntStateOf(-1) }
    var chipIndex by remember { mutableIntStateOf(0) }
    var headerIndex by remember { mutableIntStateOf(0) }

    var listVisible by remember { mutableStateOf(true) }
    var activityTick by remember { mutableIntStateOf(0) }

    // Secili (calan) kanal + aday kaynak indeksi.
    var selectedId by remember { mutableStateOf<String?>(null) }
    var candidateIndex by remember { mutableIntStateOf(0) }
    var failAttempts by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()

    val currentChannel = state.channels.firstOrNull { it.id == selectedId }
    val candidate = currentChannel?.candidates?.getOrNull(candidateIndex)

    /** Listeyi acan/odagi toparlayan hedef: calan kanal satiri. */
    suspend fun focusRow() {
        val rows = viewModel.uiState.value.filteredChannels
        val targetId = rows.firstOrNull { it.id == selectedId }?.id ?: rows.firstOrNull()?.id
        if (targetId != null) {
            val index = rows.indexOfFirst { it.id == targetId }
            if (index >= 0) runCatching { listState.animateScrollToItem(index) }
            delay(60)
            runCatching { rowRequesters[targetId]?.requestFocus() }
            rowIndex = index
            focusZone = PanelZone.ROW
        }
    }

    fun showList() {
        listVisible = true
        scope.launch {
            delay(80)
            focusRow()
        }
    }

    fun hideList() {
        if (!listVisible) return
        listVisible = false
        focusZone = PanelZone.NONE
        scope.launch {
            delay(140)
            runCatching { rootFocus.requestFocus() }
        }
    }

    fun focusRowByIndex(index: Int) {
        val rows = viewModel.uiState.value.filteredChannels
        if (rows.isEmpty()) return
        val clamped = index.coerceIn(rows.indices)
        val id = rows[clamped].id
        // Odak hedefi anında guncellenir (tus gecikmesi olmasin). Kayma iptal
        // edilebilir oldugundan satır indeksi coroutine disinda sabitlenir:
        // boylece art arda basilan tuslar her seferinde dogru satiri hedefler.
        rowIndex = clamped
        focusZone = PanelZone.ROW
        navJob?.cancel()
        navJob = scope.launch {
            // Satır zaten ekrandaysa odak aninda gitsin (halka kaymayla kaybolmasin);
            // gorunmuyorsa bos yere requestFocus denemesi yapilmaz.
            val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == clamped }
            if (alreadyVisible) runCatching { rowRequesters[id]?.requestFocus() }
            runCatching { listState.animateScrollToItem(clamped) }
            delay(40) // satırın LazyColumn'a bağlanması için
            runCatching { rowRequesters[id]?.requestFocus() }
        }
    }

    /** Cip gezinmesi: 0=Tumu, 1..=kaynaklar. */
    fun focusChipByIndex(index: Int) {
        val chips = rememberChips(viewModel.uiState.value)
        if (chips.isEmpty()) return
        val clamped = index.coerceIn(chips.indices)
        chipIndex = clamped
        runCatching { chipRequesters[chips[clamped] ?: "__all__"]?.requestFocus() }
        focusZone = PanelZone.CHIP
    }

    fun focusHeader() {
        headerIndex = 0
        runCatching { refreshFocus.requestFocus() }
        focusZone = PanelZone.HEADER
    }

    fun playChannel(id: String) {
        candidateIndex = viewModel.preferredIndexFor(id)
        failAttempts = 0
        selectedId = id
        hideList()
    }

    fun stepChannel(delta: Int) {
        val rows = viewModel.uiState.value.filteredChannels
        if (rows.isEmpty()) return
        val currentIndex = rows.indexOfFirst { it.id == selectedId }
        val next = if (currentIndex < 0) 0 else (currentIndex + delta).coerceIn(rows.indices)
        candidateIndex = viewModel.preferredIndexFor(rows[next].id)
        failAttempts = 0
        selectedId = rows[next].id
        hideList()
    }

    // Secilen adayi oynat.
    LaunchedEffect(currentChannel?.id, candidateIndex) {
        handle.play(candidate?.url, candidate?.headers.orEmpty())
    }

    // Aday hatasi: kisa bekleme sonrasi sonraki kaynaga gec. Kaynaklar donenici
    // oldugu icin tur sonunda durma: tum adaylar bitince basa don, tam tur
    // sonunda kaynaklari tazele (domain/URL donmus olabilir).
    LaunchedEffect(handle.errorCount) {
        val channel = currentChannel ?: return@LaunchedEffect
        if (handle.errorCount <= 0 || channel.candidates.size <= 1) return@LaunchedEffect
        failAttempts++
        // Tam tur atildi -> biraz bekle (tum kaynaklar olu olabilir), sonra tazele.
        if (failAttempts % channel.candidates.size == 0) {
            delay(10_000)
            viewModel.autoRefreshThrottled()
        } else {
            delay(2_500)
        }
        candidateIndex = (candidateIndex + 1) % channel.candidates.size
    }

    // Basarili oynatma: adayi tercih olarak hatirla.
    LaunchedEffect(handle.playbackState) {
        if (handle.playbackState == Player.STATE_READY) {
            failAttempts = 0
            currentChannel?.let { viewModel.rememberCandidate(it.id, candidateIndex) }
        }
    }

    LaunchedEffect(selectedId) { failAttempts = 0 }

    // Hareketsizlikte listeyi kapat.
    LaunchedEffect(listVisible, activityTick) {
        if (listVisible) {
            delay(8_000)
            listVisible = false
            focusZone = PanelZone.NONE
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Ilk acilista calan kanal satirina toparlan.
    LaunchedEffect(Unit) {
        delay(150)
        focusRow()
    }

    // Liste yuklendiginde toparla.
    LaunchedEffect(channels.isNotEmpty(), listVisible) {
        if (channels.isNotEmpty() && listVisible) {
            delay(60)
            focusRow()
        }
    }

    // Yenileme hatasi -> eski liste korunur, toast bilgilendirir.
    LaunchedEffect(state.refreshFailedAt) {
        if (state.refreshFailedAt > 0L) {
            Toast.makeText(context, R.string.livetv_refresh_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // BACK: panel gizliyse ac; aciksa kok handler'lara (sidebar) birak.
    BackHandler(enabled = !listVisible) {
        showList()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                activityTick = activityTick + 1
                val code = native.keyCode
                val isDirection = code == AndroidKeyEvent.KEYCODE_DPAD_UP ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                val isOk = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    code == AndroidKeyEvent.KEYCODE_ENTER ||
                    code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    // Panel kapaliyken yon/OK -> paneli ac
                    !listVisible && (isDirection || isOk) -> {
                        showList()
                        true
                    }

                    // Panel acikken yon tuslari -> acik gezinme (satir<->cip<->baslik)
                    listVisible && isDirection -> {
                        when (focusZone) {
                            PanelZone.ROW -> {
                                val current = rowIndex.coerceAtLeast(0)
                                when (code) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP ->
                                        if (current <= 0) focusChipByIndex(chipIndex) else focusRowByIndex(current - 1)

                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> focusRowByIndex(current + 1)
                                    else -> Unit
                                }
                            }

                            PanelZone.CHIP -> when (code) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> focusChipByIndex(chipIndex - 1)
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> focusChipByIndex(chipIndex + 1)
                                AndroidKeyEvent.KEYCODE_DPAD_UP -> focusHeader()
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> focusRowByIndex(rowIndex.coerceAtLeast(0))
                                else -> Unit
                            }

                            PanelZone.HEADER -> when (code) {
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> focusChipByIndex(chipIndex)
                                else -> Unit
                            }

                            PanelZone.NONE -> scope.launch { focusRow() }
                        }
                        true
                    }

                    else -> false
                }
            }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                    AndroidKeyEvent.KEYCODE_PAGE_UP,
                    -> {
                        stepChannel(1)
                        true
                    }

                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                    AndroidKeyEvent.KEYCODE_PAGE_DOWN,
                    -> {
                        stepChannel(-1)
                        true
                    }

                    else -> false
                }
            }
            .pointerInput(Unit) {
                // Dokunmatik cihazda ekrana dokunus paneli acar/kapatar.
                detectTapGestures(onTap = {
                    if (listVisible) hideList() else showList()
                })
            },
    ) {
        val panelWidth = minOf(PanelWidthMax, maxWidth * 0.32f)

        // 1) Yayin sahnesi
        SportsStage(
            handle = handle,
            channel = currentChannel,
            candidate = candidate,
            dim = listVisible,
            modifier = Modifier.fillMaxSize(),
        )

        // 2) Sol panel: baslik + kaynak cipleri + kanal listesi
        if (listVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = PanelStartPadding, top = 20.dp, bottom = 20.dp)
                    .width(panelWidth),
            ) {
                SportsPanel(
                    state = state,
                    channels = channels,
                    listFocus = listFocus,
                    listState = listState,
                    refreshFocus = refreshFocus,
                    rowRequesters = rowRequesters,
                    chipRequesters = chipRequesters,
                    onFocusChannel = { id ->
                        // Gezinme coroutine'i rowIndex'i yönetir; kayma sırasında
                        // geç gelen odak olayları hedefi geriye taşımasın.
                        if (navJob?.isActive != true) {
                            rowIndex = channels.indexOfFirst { it.id == id }
                        }
                        focusZone = PanelZone.ROW
                    },
                    onPlayChannel = ::playChannel,
                    onSelectSource = { id ->
                        viewModel.selectSource(id)
                        chipIndex = if (id == null) 0 else state.sources.indexOfFirst { it.id == id } + 1
                        rowIndex = -1
                    },
                    onRefresh = { viewModel.refresh() },
                    onRetry = { viewModel.refresh() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

        // 3) Alt: calan kanal bilgisi (panel gorunurken)
        if (listVisible && currentChannel != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = PanelStartPadding + panelWidth + 40.dp, bottom = 42.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFD93025))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.livetv_live),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                    }
                    Text(
                        text = currentChannel.name,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Aday kaynak gostergesi: "AtomSpor · 2/4"
                candidate?.let { c ->
                    Text(
                        text = stringResource(
                            R.string.spor_source_format,
                            c.sourceName,
                            candidateIndex + 1,
                            currentChannel.candidates.size,
                        ),
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Cip anahtar listesi: null (Tumu) + kaynak id'leri. */
private fun rememberChips(state: SportsUiState): List<String?> =
    listOf<String?>(null) + state.sources.map { it.id }

// -----------------------------------------------------------------------------
// Sahne
// -----------------------------------------------------------------------------

/**
 * Yayin sahnesi: secilen aday Media3 ile doldurulur; hazirlik aninda kanal
 * logosu + "CANLI YAYIN"; karartmalar yalniz panel gorunurken; kaynaklar
 * tamamen acilamazsa altta kirmizi ipucu.
 */
@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun SportsStage(
    handle: LiveTvPlayerHandle,
    channel: SportsChannel?,
    candidate: SportsCandidate?,
    dim: Boolean,
    modifier: Modifier = Modifier,
) {
    val dimAlpha by animateFloatAsState(
        targetValue = if (dim) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "stageDim",
    )

    Box(modifier.background(Color.Black)) {
        // Canli yayin videosu: oynaticinin kendi yuzeyi tam ekranda.
        // textureView XML'de ayarlandi: SurfaceView siyah katmani Compose
        // overlay'lerini (hazirlik placeholder'i + hata metni) her zaman ezerdi.
        AndroidView(
            factory = { ctx ->
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.sport_player_view, null, false) as PlayerView
                view.player = handle.exoPlayer
                view
            },
            update = { view -> view.player = handle.exoPlayer },
            modifier = Modifier.fillMaxSize(),
        )

        if (candidate == null) {
            // Liste henuz yuklenmedi: atmosfer isigi
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(720.dp)
                    .alpha(0.25f)
                    .background(
                        Brush.radialGradient(
                            listOf(StageAccent.copy(alpha = 0.35f), Color.Transparent),
                        ),
                    ),
            )
        }

        // Hazirlik aninda ortada kanal logosu + "CANLI YAYIN"
        if (handle.isPreparing && channel != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(0.85f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SportsChannelLogo(
                    channel = channel,
                    modifier = Modifier.logoSize(230.dp, aspect = 16f / 9f),
                    fontSize = 34.sp,
                    cornerRadius = 14.dp,
                )
                Text(
                    text = stringResource(R.string.livetv_live_broadcast),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    letterSpacing = 5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Ust/alt karartmalar (panel gorunurken)
        if (dimAlpha > 0.01f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(140.dp)
                    .alpha(dimAlpha)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(180.dp)
                    .alpha(dimAlpha)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                        ),
                    ),
            )
        }

        // Kaynaklar acilamadi ipucu
        val errorMessage = handle.errorMessage
        if (errorMessage != null && !handle.isPreparing) {
            Text(
                text = stringResource(R.string.spor_no_stream),
                color = Color(0xFFFF6B6B),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 44.dp),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Kanal paneli
// -----------------------------------------------------------------------------

@Composable
private fun SportsPanel(
    state: SportsUiState,
    channels: List<SportsChannel>,
    listFocus: FocusRequester,
    listState: androidx.compose.foundation.lazy.LazyListState,
    refreshFocus: FocusRequester,
    rowRequesters: MutableMap<String, FocusRequester>,
    chipRequesters: MutableMap<String, FocusRequester>,
    onFocusChannel: (String) -> Unit,
    onPlayChannel: (String) -> Unit,
    onSelectSource: (String?) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val emptyAndError = channels.isEmpty() && state.error
    val filteredEmpty = channels.isEmpty() && !state.error && state.channels.isNotEmpty()

    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xF70A1327), Color(0xE60C1A36)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
    ) {
        // Baslik
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_spor),
                color = NuvioTheme.colors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = StageAccent,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            SportsIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.livetv_refresh),
                onClick = onRefresh,
                focusRequester = refreshFocus,
            )
        }

        // Kaynak cipleri: Tumu + kaynak adlari
        if (state.sources.isNotEmpty()) {
            val chips = rememberChips(state)
            val allLabel = stringResource(R.string.spor_all)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                items(chips, key = { it ?: "__all__" }) { key ->
                    val label = key?.let { id -> state.sources.firstOrNull { it.id == id }?.name } ?: allLabel
                    val chipFr = remember(key) { FocusRequester() }
                    SideEffect { chipRequesters[key ?: "__all__"] = chipFr }
                    SportsChip(
                        label = label,
                        selected = state.selectedSourceId == key,
                        onClick = { onSelectSource(key) },
                        focusRequester = chipFr,
                    )
                }
            }
        }

        // Liste / durumlar
        when {
            channels.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        val rowFr = remember(channel.id) { FocusRequester() }
                        SideEffect { rowRequesters[channel.id] = rowFr }
                        SportsChannelRow(
                            channel = channel,
                            focusRequester = rowFr,
                            onFocus = onFocusChannel,
                            onClick = { onPlayChannel(channel.id) },
                        )
                    }
                }
            }

            state.loading -> {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        color = StageAccent,
                        strokeWidth = 3.dp,
                    )
                }
            }

            emptyAndError -> {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.livetv_empty_title),
                        color = NuvioTheme.colors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.livetv_empty_body),
                        color = NuvioTheme.colors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SportsChip(
                        label = stringResource(R.string.livetv_refresh),
                        selected = true,
                        onClick = onRetry,
                        modifier = Modifier.focusRequester(listFocus),
                    )
                }
            }

            filteredEmpty -> {
                // Secili kaynakta kanal yok
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.spor_source_empty),
                        color = NuvioTheme.colors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }

            else -> {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        color = StageAccent,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }

        // Alt ipucu seridi
        Text(
            text = stringResource(R.string.spor_hints),
            color = NuvioTheme.colors.TextTertiary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SportsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val pill = RoundedCornerShape(50)

    Card(
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (isFocused != state.isFocused) isFocused = state.isFocused
            },
        colors = CardDefaults.colors(
            containerColor = when {
                selected -> StageAccent.copy(alpha = 0.20f)
                else -> Color.Transparent
            },
            focusedContainerColor = when {
                selected -> StageAccent.copy(alpha = 0.30f)
                else -> Color.White.copy(alpha = 0.10f)
            },
        ),
        border = CardDefaults.border(
            border = if (selected) {
                Border(
                    border = BorderStroke(1.dp, StageAccent.copy(alpha = 0.7f)),
                    shape = pill,
                )
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = pill,
            ),
        ),
        shape = CardDefaults.shape(pill),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Text(
            text = label,
            color = if (selected || isFocused) {
                NuvioTheme.colors.TextPrimary
            } else {
                NuvioTheme.colors.TextSecondary
            },
            style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SportsIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .size(34.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            focusedContainerColor = StageAccent.copy(alpha = 0.26f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(2.dp),
                shape = RoundedCornerShape(10.dp),
            ),
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun SportsChannelRow(
    channel: SportsChannel,
    focusRequester: FocusRequester?,
    onFocus: (String) -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .height(58.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) isFocused = nowFocused
                if (nowFocused) onFocus(channel.id)
            },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = StageAccent.copy(alpha = 0.26f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(2.5.dp),
                shape = shape,
            ),
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "%02d".format(channel.number),
                color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(24.dp),
            )

            SportsChannelLogo(
                channel = channel,
                modifier = Modifier.logoSize(48.dp),
                fontSize = 10.sp,
                cornerRadius = 6.dp,
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextPrimary.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel.sourceNames.joinToString(" · "),
                    color = if (isFocused) NuvioTheme.colors.TextPrimary.copy(alpha = 0.8f) else NuvioTheme.colors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when {
                isFocused -> androidx.compose.material3.Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Yardimcilar
// -----------------------------------------------------------------------------

/** Kanal logosu: M3U tvg-logo varsa gorsel, yoksa marka renkli kisa yazi plakasi. */
@Composable
private fun SportsChannelLogo(
    channel: SportsChannel,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
    cornerRadius: Dp = 6.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(channel.logo.background)),
        contentAlignment = Alignment.Center,
    ) {
        val logoUrl = channel.logoUrl
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = channel.name,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val label = channel.shortLabel
            val scaled = when {
                label.length <= 3 -> fontSize
                label.length <= 5 -> (fontSize.value * 0.86f).sp
                label.length <= 7 -> (fontSize.value * 0.74f).sp
                else -> (fontSize.value * 0.62f).sp
            }
            Text(
                text = label,
                color = Color(channel.logo.foreground),
                fontSize = scaled,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** En-boy oranli logo yardimcisi (liste/sahne kullanimi). */
private fun Modifier.logoSize(width: Dp, aspect: Float = 16f / 10f): Modifier =
    width(width).aspectRatio(aspect)

private enum class PanelZone { NONE, ROW, CHIP, HEADER }
