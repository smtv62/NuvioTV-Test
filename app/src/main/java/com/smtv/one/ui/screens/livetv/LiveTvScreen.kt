@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.smtv.one.ui.screens.livetv

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.smtv.one.R
import com.smtv.one.domain.model.LiveChannel
import com.smtv.one.ui.components.NuvioDialog
import com.smtv.one.ui.screens.detail.requestFocusAfterFrames
import com.smtv.one.ui.screens.settings.SettingsToggleRow
import com.smtv.one.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StageAccent = Color(0xFF3D8BFF)
private val PanelWidthMax = 440.dp
private val PanelStartPadding = 72.dp

/**
 * Canlı TV ekranı: tam ekran yayın sahnesi + solda açılıp kapanan kanal paneli.
 *
 * - ↑↓/OK panel kapalıyken paneli açar, açıkken satırlarda gezer.
 * - CH+/CH- (PAGE UP/DOWN) kanal adımlar; sayısal tuşlar 1.3 sn tamponla
 *   kanal numarasına atlar; MENU favori ekler/çıkarır.
 * - Panel 8 sn hareketsizlikte otomatik gizlenir (ayarlanabilir).
 * - BACK: panel gizliyse açar; açıksa Nuvio kök davranışına bırakır
 *   (sidebar açılır, tekrar BACK çıkış).
 */
@Composable
fun LiveTvScreen() {
    val viewModel: LiveTvViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Yayın boyunca ekran uyumasın.
    val activity = context as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val channels = remember(state) { state.filteredChannels() }

    // Kanal listesinde tek odak hedefi satırlar; panel odaklanabilir değil
    // (odaklanırsa Compose yön araması satırları aday saymıyor).
    val listFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Açık (explicit) panel gezinmesi: yön tuşları Compose'un LazyColumn içi
    // geometrik aramasına bırakılmaz; satır/chip/buton hedefleri ayrı
    // FocusRequester'larla satır↔chip↔başlık arasında kendi mantığımızla taşınır.
    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val chipRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val refreshFocus = remember { FocusRequester() }
    val gearFocus = remember { FocusRequester() }
    var focusZone by remember { mutableStateOf(PanelZone.NONE) }
    var rowIndex by remember { mutableStateOf(-1) }
    var chipIndex by remember { mutableStateOf(0) }
    var headerIndex by remember { mutableStateOf(0) }

    var listVisible by remember { mutableStateOf(true) }
    var activityTick by remember { mutableStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }

    var digitBuffer by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var tuneJob by remember { mutableStateOf<Job?>(null) }

    /** Listeyi açan/odakı toparlayan hedef: vurgulanan (çalan) kanal satırı. */
    suspend fun focusRow() {
        // viewModel üzerinden oku: pointerInput gibi uzun ömürlü lambda'lar
        // eski composition'un listesini yakalamasın.
        val current = viewModel.uiState.value
        val rows = current.filteredChannels()
        val targetId = rows.firstOrNull { it.id == current.highlightedChannelId }?.id
            ?: rows.firstOrNull { it.id == current.currentChannelId }?.id
            ?: rows.firstOrNull()?.id
        if (targetId != null) {
            val index = rows.indexOfFirst { it.id == targetId }
            if (index >= 0) runCatching { listState.scrollToItem(index) }
            delay(60) // satırın LazyColumn'a bağlanması için
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

    /** Verilen satıra odak (satır requesters haritası üzerinden). */
    fun focusRowByIndex(index: Int) {
        val rows = viewModel.uiState.value.filteredChannels()
        if (rows.isEmpty()) return
        val clamped = index.coerceIn(rows.indices)
        val id = rows[clamped].id
        scope.launch {
            runCatching { listState.scrollToItem(clamped) }
            delay(50)
            runCatching { rowRequesters[id]?.requestFocus() }
            rowIndex = clamped
            focusZone = PanelZone.ROW
        }
    }

    /** Verilen chip'e odak (0=Tümü, 1=Favoriler, 2..=gruplar). */
    fun focusChipByIndex(index: Int) {
        val current = viewModel.uiState.value
        val chips = buildList {
            add(LiveTvUiState.GROUP_ALL)
            add(LiveTvUiState.GROUP_FAVORITES)
            addAll(current.groups)
        }
        if (chips.isEmpty()) return
        val clamped = index.coerceIn(chips.indices)
        chipIndex = clamped
        runCatching { chipRequesters[chips[clamped]]?.requestFocus() }
        focusZone = PanelZone.CHIP
    }

    /** Başlık butonları: 0=Yenile, 1=Ayarlar. */
    fun focusHeader(which: Int) {
        headerIndex = which.coerceIn(0..1)
        runCatching {
            if (headerIndex == 0) refreshFocus.requestFocus() else gearFocus.requestFocus()
        }
        focusZone = PanelZone.HEADER
    }

    fun onDigit(digit: Int): Boolean {
        digitBuffer = (digitBuffer + digit.toString()).takeLast(3)
        tuneJob?.cancel()
        tuneJob = scope.launch {
            delay(1300)
            val target = digitBuffer.toIntOrNull()
            digitBuffer = ""
            if (target != null && viewModel.tuneToNumber(target)) hideList()
        }
        return true
    }

    // Hareketsizlikte listeyi kapat (ayar kapalıysa hiç kapanmaz).
    LaunchedEffect(listVisible, activityTick, state.autoHideList, settingsOpen) {
        if (listVisible && state.autoHideList && !settingsOpen) {
            delay(8_000)
            listVisible = false
            focusZone = PanelZone.NONE
            runCatching { rootFocus.requestFocus() }
        }
    }

    // İlk açılışta çalan kanal satırına toparlan.
    LaunchedEffect(Unit) {
        delay(150)
        focusRow()
    }

    // Liste yüklendiğinde (odak daha önce hiçbir satıra bağlanamadıysa) toparla.
    LaunchedEffect(channels.isNotEmpty(), listVisible) {
        if (channels.isNotEmpty() && listVisible) {
            delay(60)
            focusRow()
        }
    }

    // Manuel yenileme hatası → eski liste korunur, toast bilgilendirir.
    LaunchedEffect(state.refreshFailedAt) {
        if (state.refreshFailedAt > 0L) {
            Toast.makeText(context, R.string.livetv_refresh_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // BACK: panel gizliyse aç; açıksa kök handler'lara (sidebar) bırak.
    BackHandler(enabled = !listVisible && !settingsOpen) {
        showList()
    }

    val currentChannel = state.currentChannel

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                activityTick = activityTick + 1
                if (settingsOpen) return@onPreviewKeyEvent false
                val code = native.keyCode
                val isDirection = code == AndroidKeyEvent.KEYCODE_DPAD_UP ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
                    code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                val isOk = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    code == AndroidKeyEvent.KEYCODE_ENTER ||
                    code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    // Panel kapalıyken yön/OK → paneli aç
                    !listVisible && (isDirection || isOk) -> {
                        showList()
                        true
                    }

                    // Panel açıkken yön tuşları → açık gezinme (satır↔chip↔başlık)
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
                                AndroidKeyEvent.KEYCODE_DPAD_UP -> focusHeader(0)
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> focusRowByIndex(rowIndex.coerceAtLeast(0))
                                else -> Unit
                            }

                            PanelZone.HEADER -> when (code) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> focusHeader(0)
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> focusHeader(1)
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
                if (settingsOpen) return@onKeyEvent false
                val code = native.keyCode
                when (code) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                    AndroidKeyEvent.KEYCODE_PAGE_UP,
                    -> {
                        viewModel.stepChannel(1)
                        hideList()
                        true
                    }

                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                    AndroidKeyEvent.KEYCODE_PAGE_DOWN,
                    -> {
                        viewModel.stepChannel(-1)
                        hideList()
                        true
                    }

                    AndroidKeyEvent.KEYCODE_MENU,
                    AndroidKeyEvent.KEYCODE_STAR,
                    -> {
                        val target = state.highlightedChannelId
                        val nowFavorite = viewModel.toggleFavorite(target) ?: return@onKeyEvent false
                        Toast.makeText(
                            context,
                            if (nowFavorite) R.string.livetv_favorite_added else R.string.livetv_favorite_removed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        true
                    }

                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
                        onDigit(code - AndroidKeyEvent.KEYCODE_0)

                    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
                        onDigit(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)

                    else -> false
                }
            }
            .pointerInput(Unit) {
                // Dokunmatik cihazda ekrana dokunuş paneli açar/kapatar.
                detectTapGestures(onTap = {
                    if (listVisible) hideList() else showList()
                })
            },
    ) {
        val panelWidth = minOf(PanelWidthMax, maxWidth * 0.32f)

        // 1) Yayın sahnesi
        LiveStage(
            channel = currentChannel,
            dim = listVisible,
            modifier = Modifier.fillMaxSize(),
        )

        // 2) Sağ üst: kanal logosu + saat (panelle birlikte açılır/kapanır)
        if (listVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 18.dp, end = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                currentChannel?.let { channel ->
                    ChannelLogo(
                        channel = channel,
                        modifier = Modifier.logoSize(80.dp),
                        fontSize = 13.sp,
                        cornerRadius = 8.dp,
                    )
                }
                LiveTvClock()
            }
        }

        // 3) Sol panel: başlık + grup çipleri + kanal listesi
        if (listVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = PanelStartPadding, top = 20.dp, bottom = 20.dp)
                    .width(panelWidth),
            ) {
                ChannelPanel(
                    state = state,
                    channels = channels,
                    listFocus = listFocus,
                    listState = listState,
                    refreshFocus = refreshFocus,
                    gearFocus = gearFocus,
                    rowRequesters = rowRequesters,
                    chipRequesters = chipRequesters,
                    onFocusChannel = { id ->
                        viewModel.highlightChannel(id)
                        rowIndex = channels.indexOfFirst { it.id == id }
                        focusZone = PanelZone.ROW
                    },
                    onPlayChannel = { id ->
                        viewModel.playChannel(id)
                        hideList()
                    },
                    onSelectGroup = viewModel::selectGroup,
                    onRefresh = { viewModel.refresh() },
                    onOpenSettings = { settingsOpen = true },
                    onRetry = { viewModel.refresh() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

        // 4) Alt: çalan kanal bilgisi (panel görünürken)
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
                Text(
                    text = currentChannel.group,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
        }

        // 5) Sayısal tuş tamponu
        if (digitBuffer.isNotEmpty()) {
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 26.dp, bottom = 26.dp)
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.82f))
                    .border(2.dp, StageAccent, shape)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    text = digitBuffer,
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 5.sp,
                )
            }
        }

        // 6) Canlı TV ayarları
        if (settingsOpen) {
            val firstToggleFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { firstToggleFocus.requestFocusAfterFrames() }
            NuvioDialog(
                onDismiss = { settingsOpen = false },
                title = stringResource(R.string.livetv_settings_title),
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.livetv_auto_start),
                    subtitle = stringResource(R.string.livetv_auto_start_desc),
                    checked = state.autoStartOnLaunch,
                    onToggle = { viewModel.setAutoStartOnLaunch(!state.autoStartOnLaunch) },
                    modifier = Modifier.focusRequester(firstToggleFocus),
                )
                SettingsToggleRow(
                    title = stringResource(R.string.livetv_auto_hide),
                    subtitle = stringResource(R.string.livetv_auto_hide_desc),
                    checked = state.autoHideList,
                    onToggle = { viewModel.setAutoHideList(!state.autoHideList) },
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Sahne
// -----------------------------------------------------------------------------

/**
 * Yayın sahnesi: gerçek M3U adresi varsa Media3 bu alanı doldurur; üst/alt
 * karartmalar yalnız panel görünürken çizilir; hazırlık anında ortada kanal
 * logosu + "CANLI YAYIN" gösterilir.
 */
@Composable
private fun LiveStage(
    channel: LiveChannel?,
    dim: Boolean,
    modifier: Modifier = Modifier,
) {
    val handle = rememberLiveTvPlayer()
    val url = channel?.url
    val dimAlpha by animateFloatAsState(
        targetValue = if (dim) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "stageDim",
    )

    LaunchedEffect(url, channel?.headers, handle) {
        handle.play(url, channel?.headers.orEmpty())
    }

    Box(modifier.background(Color.Black)) {
        if (url.isNullOrBlank()) {
            // Liste henüz yüklenmedi: atmosfer ışığı
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(720.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x3D2E8BFF), Color(0x0A12305F), Color(0x00050B18)),
                        ),
                        CircleShape,
                    ),
            )
        } else {
            LiveTvPlayerSurface(
                handle = handle,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Üst koyulaşma (logo/saat okunurluğu)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(150.dp)
                .alpha(dimAlpha)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )

        // Alt koyulaşma (kanal bilgisi okunurluğu)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(230.dp)
                .alpha(dimAlpha)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f)),
                    ),
                ),
        )

        // Ortada kanal logosu + "CANLI YAYIN" — yalnız yayın hazırlanırken
        val preparing = handle.isPreparing ||
            (handle.playbackState == Player.STATE_IDLE && handle.errorMessage == null)
        if (channel != null && (url.isNullOrBlank() || preparing)) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChannelLogo(
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
    }
}

// -----------------------------------------------------------------------------
// Kanal paneli
// -----------------------------------------------------------------------------

@Composable
private fun ChannelPanel(
    state: LiveTvUiState,
    channels: List<LiveChannel>,
    listFocus: FocusRequester,
    listState: androidx.compose.foundation.lazy.LazyListState,
    refreshFocus: FocusRequester,
    gearFocus: FocusRequester,
    rowRequesters: MutableMap<String, FocusRequester>,
    chipRequesters: MutableMap<String, FocusRequester>,
    onFocusChannel: (String) -> Unit,
    onPlayChannel: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val emptyAndError = channels.isEmpty() && state.error
    val emptyNoError = channels.isEmpty() && !state.error

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
        // Başlık
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_live_tv),
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
            PanelIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.livetv_refresh),
                onClick = onRefresh,
                focusRequester = refreshFocus,
            )
            Spacer(modifier = Modifier.width(6.dp))
            PanelIconButton(
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.livetv_settings),
                onClick = onOpenSettings,
                focusRequester = gearFocus,
                modifier = Modifier.then(
                    if (emptyNoError) Modifier.focusRequester(listFocus) else Modifier
                ),
            )
        }

        // Grup çipleri: Tümü / Favoriler / M3U group-title değerleri
        if (channels.isNotEmpty() || state.groups.isNotEmpty()) {
            val chips = remember(state.groups) {
                buildList {
                    add(LiveTvUiState.GROUP_ALL)
                    add(LiveTvUiState.GROUP_FAVORITES)
                    addAll(state.groups)
                }
            }
            val fallbackLabel = stringResource(R.string.livetv_group_all)
            val favoritesLabel = stringResource(R.string.livetv_group_favorites)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                items(chips, key = { it }) { key ->
                    val label = when (key) {
                        LiveTvUiState.GROUP_ALL -> fallbackLabel
                        LiveTvUiState.GROUP_FAVORITES -> favoritesLabel
                        else -> key
                    }
                    val chipFr = remember(key) { FocusRequester() }
                    SideEffect { chipRequesters[key] = chipFr }
                    GroupChip(
                        label = label,
                        selected = state.selectedGroup == key,
                        onClick = { onSelectGroup(key) },
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
                        ChannelRow(
                            channel = channel,
                            isCurrent = channel.id == state.currentChannelId,
                            isFavorite = channel.id in state.favorites,
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
                    GroupChip(
                        label = stringResource(R.string.livetv_refresh),
                        selected = true,
                        onClick = onRetry,
                        modifier = Modifier.focusRequester(listFocus),
                    )
                }
            }

            else -> {
                // Favoriler boş (kanallar var ama favori yok)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.livetv_favorites_empty_title),
                            color = NuvioTheme.colors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.livetv_favorites_empty_body),
                            color = NuvioTheme.colors.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }

        // Alt ipucu şeridi
        Text(
            text = stringResource(R.string.livetv_hints),
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
private fun GroupChip(
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
private fun PanelIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Card(
        onClick = onClick,
        modifier = modifier
            .size(34.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (isFocused != state.isFocused) isFocused = state.isFocused
            },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.10f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                shape = shape,
            ),
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    isCurrent: Boolean,
    isFavorite: Boolean,
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
            containerColor = if (isCurrent) Color.White.copy(alpha = 0.07f) else Color.Transparent,
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

            ChannelLogo(
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
                    text = channel.group,
                    color = if (isFocused) NuvioTheme.colors.TextPrimary.copy(alpha = 0.8f) else NuvioTheme.colors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(15.dp),
                )
            }
            when {
                isFocused -> Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.size(18.dp),
                )

                isCurrent -> Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(StageAccent),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Yardımcılar
// -----------------------------------------------------------------------------

/** Kanal logosu: M3U tvg-logo varsa görsel, yoksa marka renkli kısa yazı plakası. */
@Composable
fun ChannelLogo(
    channel: LiveChannel,
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

/** En-boy oranlı logo yardımcısı (liste/sahne kullanımı). */
fun Modifier.logoSize(width: Dp, aspect: Float = 16f / 10f): Modifier =
    width(width).aspectRatio(aspect)

@Composable
private fun LiveTvClock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(20_000)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) { SimpleDateFormat("d MMM EEE", Locale.getDefault()).format(Date(now)) }

    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = time,
            color = NuvioTheme.colors.TextPrimary,
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = date,
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

/**
 * Panelde odaklının bulunduğu bölge: yön tuşlarının davranışı buradan belirlenir
 * (Compose'un LazyColumn içi geometrik yön araması güvenilir olmadığı için).
 */
private enum class PanelZone { NONE, ROW, CHIP, HEADER }
