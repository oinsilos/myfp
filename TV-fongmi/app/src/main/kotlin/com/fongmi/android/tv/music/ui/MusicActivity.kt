package com.fongmi.android.tv.music.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.ui.text.input.ImeAction
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.bumptech.glide.Glide
import com.fongmi.android.tv.R
import com.fongmi.android.tv.music.model.MusicMedia
import com.fongmi.android.tv.music.model.RepeatMode
import com.fongmi.android.tv.music.plugin.MusicRepository
import com.fongmi.android.tv.music.service.MusicPlaybackService
import com.fongmi.android.tv.utils.Notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.regex.Pattern

/**
 * 音乐界面（Compose 版）：搜索 → 结果列表 → 点击播放。
 * 状态由 [UiState] 持有（Compose 可观察），播放仍交给 [MusicPlaybackService]（后台前台服务），
 * 本页订阅其回调刷新 UI。替换原 Java + View 实现，保持对外入口/接口不变。
 */
class MusicActivity : AppCompatActivity(), MusicPlaybackService.Listener {

    // ------------------------------------------------------------ UI 状态

    private class UiState {
        var results by mutableStateOf<List<MusicMedia>>(emptyList())
        var searching by mutableStateOf(false)
        var current by mutableStateOf<MusicMedia?>(null)
        var playing by mutableStateOf(false)
        var stateText by mutableStateOf("idle")
        var positionMs by mutableStateOf(0L)
        var durationMs by mutableStateOf(0L)
        var coverUrl by mutableStateOf("")
        var lyricHint by mutableStateOf("暂无歌词")
        var mode by mutableStateOf(RepeatMode.LIST)
        var lyricDialogVisible by mutableStateOf(false)
        var messageVisible by mutableStateOf(false)
        var messageText by mutableStateOf("")
        // 多音源
        var sources by mutableStateOf<List<MusicRepository.PluginInfo>>(emptyList())
        var currentSource by mutableStateOf("netease")
        var sourceDialogVisible by mutableStateOf(false)
        var importing by mutableStateOf(false)
    }

    private val ui = UiState()
    private var lyricLines: List<LyricLine>? = null
    private var lyricError: String? = null
    private var lastError: String? = null
    private var lastKeyword = ""
    private var dragging = false
    private var bound = false
    private var service: MusicPlaybackService? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MusicActivity"
        private const val SEARCH_TIMEOUT_MS = 25_000L
        private val LRC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
        private val PLACEHOLDER_COLOR = Color(0xFF323232)
        private val BG_COLOR = Color(0xFF141414)
        private val SURFACE_COLOR = Color(0xFF1E1E1E)

        /** 从主界面（HomeActivity）进入音乐模块。 */
        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, MusicActivity::class.java))
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as MusicPlaybackService.MusicBinder).service().bindListener(this@MusicActivity)
            bound = true
            val cur = service?.current()
            if (cur != null) onMusicChanged(cur)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // init 为后台异步加载（Rhino 引擎初始化不阻塞 UI，避免模拟器/低端机 ANR）
        MusicRepository.get().init(this)
        refreshSourceInfo()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF4FC3F7),
                background = BG_COLOR,
                surface = SURFACE_COLOR,
                onBackground = Color(0xFFE0E0E0),
                onSurface = Color(0xFFE0E0E0),
                secondary = Color(0xFF999999),
                onSecondary = Color(0xFFE0E0E0),
            )) {
                MusicScreen(
                    ui = ui,
                    onSearch = { search(it) },
                    onPlayAt = { index -> playAt(index) },
                    onToggleLyric = ::toggleLyric,
                    onCycleMode = ::cycleMode,
                    onSeek = ::seekTo,
                    onTogglePlay = { service?.toggle() },
                    onPrev = { service?.prev() },
                    onNext = { service?.next() },
                    onCloseLyricDialog = { ui.lyricDialogVisible = false },
                    onCloseMessage = { ui.messageVisible = false },
                    onOpenSources = { ui.sourceDialogVisible = true },
                    onSwitchSource = ::switchSource,
                    onImportPlugin = ::importPlugin,
                )
            }
        }
        startAndBindService()
        // 先渲染 UI，等插件引擎就绪（readyFuture）后再自动搜索，避免首帧卡顿
        MusicRepository.get().readyFuture().whenComplete { _, _ ->
            runOnUiThread {
                refreshSourceInfo()
                if (ui.stateText == "idle" || ui.stateText.contains("music ")) ui.stateText = "ready"
                search("周杰伦")
            }
        }
    }

    /** 刷新音源列表与当前源显示。 */
    private fun refreshSourceInfo() {
        ui.sources = MusicRepository.get().plugins()
        ui.currentSource = MusicRepository.get().platform().ifEmpty { "unknown" }
    }

    private fun switchSource(platform: String) {
        val ok = MusicRepository.get().switchTo(platform)
        ui.sourceDialogVisible = false
        if (!ok) return
        refreshSourceInfo()
        ui.stateText = "ready"
        // 切换源后自动用上次关键字重搜，避免手动再点一次
        if (lastKeyword.isNotEmpty()) search(lastKeyword)
        else Notify.show("已切换到 " + platform)
    }

    private fun importPlugin(url: String) {
        if (url.isBlank()) return
        ui.importing = true
        MusicRepository.get().importPlugin(url.trim()).whenComplete { ok, error ->
            runOnUiThread {
                ui.importing = false
                refreshSourceInfo()
                if (error == null && ok == true) {
                    ui.sourceDialogVisible = false
                    Notify.show("插件导入成功：" + ui.currentSource)
                    if (lastKeyword.isNotEmpty()) search(lastKeyword)
                } else {
                    ui.sourceDialogVisible = false
                    showMessage("插件导入失败\n\n" + url.trim())
                }
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.unbindListener()
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun startAndBindService() {
        startService(Intent(this, MusicPlaybackService::class.java))
        bindService(Intent(this, MusicPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    // ------------------------------------------------------------ 搜索

    private fun search(keyword: String) {
        if (keyword.isBlank()) return
        lastKeyword = keyword.trim()
        ui.searching = true
        handler.removeCallbacksAndMessages(null)
        // 兜底：无论底层如何，20s 内必须结束搜索态，绝不无限转圈
        handler.postDelayed({
            if (!ui.searching) return@postDelayed
            ui.searching = false
            Log.w(TAG, "search timeout; kw=$lastKeyword current=${MusicRepository.get().platform()} searching=$ui.searching")
            Notify.show("搜索超时，请检查网络")
        }, SEARCH_TIMEOUT_MS)
        MusicRepository.get().search(keyword.trim()).whenComplete { list, error ->
            runOnUiThread {
                handler.removeCallbacksAndMessages(null)
                ui.searching = false
                if (error != null) {
                    Notify.show("搜索失败：" + friendly(error))
                } else {
                    ui.results = list ?: emptyList()
                    if (ui.results.isEmpty()) Notify.show("未找到相关歌曲")
                }
            }
        }
    }

    private fun playAt(index: Int) {
        val s = service ?: return
        s.play(ArrayList(ui.results), index)
    }

    private fun friendly(t: Throwable): String {
        val cause = t.cause ?: t
        val msg = cause.message ?: cause.toString()
        val finalMsg = if (msg.isEmpty()) t.toString() else msg
        return if (finalMsg.length > 120) finalMsg.substring(0, 120) + "…" else finalMsg
    }

    // ------------------------------------------------------------ 播放回调

    override fun onMusicChanged(media: MusicMedia) {
        runOnUiThread {
            ui.current = media
            ui.coverUrl = media?.cover ?: ""
            ui.durationMs = media?.durationMs ?: 0L
            ui.positionMs = 0L
            loadLyric(media)
        }
    }

    override fun onPlayingChanged(playing: Boolean) {
        runOnUiThread { ui.playing = playing }
    }

    override fun onStateChanged(state: Int) {
        runOnUiThread {
            // 错误一闪而过问题：onPlayerError 后状态切 IDLE，会覆盖错误文本。
            // 有未读错误时，IDLE 状态保留错误文本，等下一首歌开始播放才清空。
            ui.stateText = when {
                state == Player.STATE_IDLE && lastError != null -> lastError!!
                state == Player.STATE_READY -> {
                    lastError = null
                    "ready"
                }
                else -> stateName(state)
            }
        }
    }

    override fun onError(media: MusicMedia, error: PlaybackException) {
        val url = media?.url?.takeIf { it.isNotEmpty() } ?: "-"
        var why = describe(error)
        if (why.isEmpty()) why = error.message ?: "未知"
        if (why.length > 120) why = why.substring(0, 120) + "…"
        lastError = "err " + error.errorCode + ": " + why + "\n" + url
        runOnUiThread { ui.stateText = lastError!! }
    }

    /** 取 cause 链顶层的具体异常原因（跳过笼统的 "Source error"）。 */
    private fun describe(t: Throwable): String {
        var cur: Throwable? = t
        var i = 0
        while (cur != null && i < 4) {
            val m = cur.message
            if (m != null && m.isNotEmpty() && m != "Source error") {
                return cur.javaClass.simpleName + ": " + m
            }
            cur = cur.cause
            i++
        }
        return ""
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown($state)"
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        runOnUiThread {
            ui.durationMs = durationMs
            if (!dragging) {
                ui.positionMs = positionMs
                updateLyricHint(positionMs)
            }
        }
    }

    override fun onSourceFailed(media: MusicMedia, message: String) {
        val why = message.ifEmpty { "未知错误" }
        runOnUiThread { Notify.show("播放失败：" + media.title + "（$why）") }
    }

    private fun cycleMode() {
        val s = service ?: return
        val cur = s.mode()
        val next = when (cur) {
            RepeatMode.LIST -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.SHUFFLE
            RepeatMode.SHUFFLE -> RepeatMode.LIST
        }
        s.setRepeatMode(next)
        ui.mode = next
    }

    private fun seekTo(fraction: Float) {
        val s = service ?: return
        s.seekTo((fraction * ui.durationMs).toLong())
    }

    // ------------------------------------------------------------ 歌词

    private fun loadLyric(media: MusicMedia?) {
        lyricLines = null
        lyricError = null
        ui.lyricHint = if (media == null) "暂无歌词" else "加载歌词…"
        if (media == null) return
        MusicRepository.get().getLyric(media).whenComplete { lrc, error ->
            runOnUiThread {
                if (error != null) {
                    lyricError = friendly(error)
                    val brief = if (lyricError!!.length > 24) lyricError!!.substring(0, 24) + "…" else lyricError!!
                    ui.lyricHint = "歌词失败:$brief"
                    return@runOnUiThread
                }
                val lines = parseLrc(lrc)
                if (lines.isEmpty()) {
                    ui.lyricHint = "该歌暂无歌词"
                    return@runOnUiThread
                }
                lyricLines = lines
                ui.lyricHint = "点击显示歌词"
            }
        }
    }

    private fun updateLyricHint(positionMs: Long) {
        val lines = lyricLines ?: return
        if (lines.isEmpty()) return
        val idx = lyricIndex(lines, positionMs)
        ui.lyricHint = if (idx < 0) "点击显示歌词" else lines[idx].text
    }

    /** 解析标准 LRC：支持多个时间戳共享一行（取最后一个），时间单位 mm:ss(.xx)。 */
    private fun parseLrc(lrc: String?): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        if (lrc.isNullOrEmpty()) return lines
        for (raw in lrc.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = LRC_TIME.matcher(line)
            var time = -1L
            var lastEnd = 0
            while (m.find()) {
                var t = m.group(1).toLong() * 60_000L + m.group(2).toLong() * 1000L
                val frac = m.group(3)
                if (frac != null) {
                    t += when (frac.length) {
                        1 -> frac.toLong() * 100L
                        2 -> frac.toLong() * 10L
                        else -> frac.toLong()
                    }
                }
                time = maxOf(time, t)
                lastEnd = m.end()
            }
            if (time < 0) continue
            val text = if (lastEnd >= line.length) "" else line.substring(lastEnd).trim()
            lines.add(LyricLine(time, text))
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    /** 二分定位 positionMs 落在哪一句（上一句仍未结束则为前一句）。 */
    private fun lyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 歌词行点击：有词进全屏歌词；无词/失败弹窗显示原因。 */
    private fun toggleLyric() {
        if (!lyricLines.isNullOrEmpty()) {
            ui.lyricDialogVisible = true
        } else if (lyricError != null) {
            showMessage("歌词获取失败\n\n$lyricError")
        } else {
            showMessage("该歌曲暂无歌词\n\n（未收录歌词或 VIP 歌曲）")
        }
    }

    private fun showMessage(text: String) {
        ui.messageText = text
        ui.messageVisible = true
    }

    private data class LyricLine(val timeMs: Long, val text: String)

    // ------------------------------------------------------------ Compose UI

    @Composable
    private fun MusicScreen(
        ui: UiState,
        onSearch: (String) -> Unit,
        onPlayAt: (Int) -> Unit,
        onToggleLyric: () -> Unit,
        onCycleMode: () -> Unit,
        onSeek: (Float) -> Unit,
        onTogglePlay: () -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onCloseLyricDialog: () -> Unit,
        onCloseMessage: () -> Unit,
        onOpenSources: () -> Unit,
        onSwitchSource: (String) -> Unit,
        onImportPlugin: (String) -> Unit,
    ) {
        var keyword by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                SearchBar(keyword, { keyword = it }) { onSearch(keyword) }
                if (ui.searching) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(28.dp)
                    )
                }
                ResultList(
                    items = ui.results,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onPlayAt = onPlayAt,
                )
                HorizontalDivider(color = Color(0xFF333333))
                PlayerBar(ui, onToggleLyric, onCycleMode, onSeek, onTogglePlay, onPrev, onNext, onOpenSources)
            }
        }
        if (ui.lyricDialogVisible) {
            val cur = ui.current
            LyricDialog(
                title = cur?.title?.plus(cur?.artist?.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: "") ?: "",
                lines = lyricLines ?: emptyList(),
                positionMs = ui.positionMs,
                onClose = onCloseLyricDialog,
            )
        }
        if (ui.sourceDialogVisible) {
            SourceDialog(
                current = ui.currentSource,
                sources = ui.sources,
                importing = ui.importing,
                onClose = { ui.sourceDialogVisible = false },
                onSwitch = onSwitchSource,
                onImport = onImportPlugin,
            )
        }
        if (ui.messageVisible) {
            MessageDialog(text = ui.messageText, onClose = onCloseMessage)
        }
    }

    @Composable
    private fun SearchBar(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入歌名 / 歌手", color = Color(0xFF666666), fontSize = 14.sp) },
                maxLines = 1,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSearch) { Text("搜索") }
        }
    }

    @Composable
    private fun ResultList(items: List<MusicMedia>, modifier: Modifier, onPlayAt: (Int) -> Unit) {
        LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(items) { index, media ->
                val vip = media.vip
                val sub = when {
                    media.artist.isNotEmpty() && media.album.isNotEmpty() -> "${media.artist} · ${media.album}"
                    media.artist.isNotEmpty() -> media.artist
                    media.album.isNotEmpty() -> media.album
                    else -> ""
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onPlayAt(index) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!media.cover.isNullOrEmpty()) {
                        GlideImage(
                            url = media.cover,
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = media.title + if (vip) "  [VIP]" else "",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (vip) 0.55f else 1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = sub,
                            fontSize = 12.sp,
                            color = Color(0xFF999999),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(fmt(media.durationMs), fontSize = 12.sp, color = Color(0xFF777777))
                }
            }
        }
    }

    @Composable
    private fun PlayerBar(
        ui: UiState,
        onToggleLyric: () -> Unit,
        onCycleMode: () -> Unit,
        onSeek: (Float) -> Unit,
        onTogglePlay: () -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onOpenSources: () -> Unit,
    ) {
        var dragFraction by remember { mutableStateOf<Float?>(null) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "音源 " + ui.currentSource + " ▾",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenSources() }.padding(vertical = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    ui.stateText,
                    fontSize = 10.sp,
                    color = Color(0xFF888888),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GlideImage(
                    url = ui.coverUrl.takeIf { it.isNotEmpty() },
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.lyricHint,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onToggleLyric() }
                            .padding(vertical = 2.dp),
                    )
                    Text(
                        ui.current?.let { c ->
                            c.title + if (c.artist.isNotEmpty()) " - ${c.artist}" else ""
                        } ?: "未在播放",
                        fontSize = 13.sp,
                        color = Color(0xFFAAAAAA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (ui.mode) {
                        RepeatMode.ONE -> "单曲循环"
                        RepeatMode.SHUFFLE -> "随机播放"
                        RepeatMode.LIST -> "列表循环"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB),
                    modifier = Modifier.clickable { onCycleMode() },
                )
                Spacer(Modifier.weight(1f))
                Text(timeText(ui.positionMs) + " / " + timeText(ui.durationMs), fontSize = 11.sp, color = Color(0xFF777777))
            }
            // 拖动中显示手指位置；松手后提交 seek，期间忽略进度回调覆盖
            val fraction = dragFraction
                ?: if (ui.durationMs <= 0) 0f else (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
            Slider(
                value = fraction,
                onValueChange = {
                    dragFraction = it
                    dragging = true
                },
                onValueChangeFinished = {
                    dragFraction?.let(onSeek)
                    dragFraction = null
                    dragging = false
                },
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(painterResource(R.drawable.ic_notify_prev), contentDescription = "上一首") }
                IconButton(onClick = onTogglePlay, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(
                        painterResource(if (ui.playing) R.drawable.ic_notify_pause else R.drawable.ic_notify_play),
                        contentDescription = "播放/暂停",
                    )
                }
                IconButton(onClick = onNext) { Icon(painterResource(R.drawable.ic_notify_next), contentDescription = "下一首") }
            }
        }
    }

    /** 全屏歌词：当前句高亮 + 自动滚动；行可点，点击跳转对应时间。 */
    @Composable
    private fun LyricDialog(title: String, lines: List<LyricLine>, positionMs: Long, onClose: () -> Unit) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val listState = rememberLazyListState()
            val currentIdx = lyricIndex(lines, positionMs)
            LaunchedEffect(currentIdx) {
                if (currentIdx >= 0) listState.animateScrollToItem((currentIdx - 4).coerceAtLeast(0))
            }
            Column(
                Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onClose() },
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    color = Color(0xFFCCCCCC),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    contentPadding = PaddingValues(vertical = 120.dp),
                ) {
                    itemsIndexed(lines) { index, line ->
                        val cur = index == currentIdx
                        Text(
                            text = line.text.ifEmpty { "…" },
                            fontSize = 18.sp,
                            fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                            color = if (cur) Color.White else Color(0xFF999999),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                .clickable { service?.seekTo(line.timeMs) },
                        )
                    }
                }
            }
        }
    }

    /** 音源切换 + 插件导入弹窗（列表选择当前源；底部 URL 导入）。 */
    @Composable
    private fun SourceDialog(
        current: String,
        sources: List<MusicRepository.PluginInfo>,
        importing: Boolean,
        onClose: () -> Unit,
        onSwitch: (String) -> Unit,
        onImport: (String) -> Unit,
    ) {
        var url by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text(
                    "选择音源",
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                )
                Text(
                    "当前：" + current,
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                ) {
                    sources.forEach { info ->
                        val isCur = info.platform == current
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onSwitch(info.platform) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (isCur) "●  " else "○  ", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${info.platform}  v${info.version}",
                                    fontSize = 14.sp,
                                    color = if (isCur) Color.White else Color(0xFFCCCCCC),
                                )
                                Text(
                                    (if (info.builtin) "内置 · " else "外部 · ") + info.label,
                                    fontSize = 11.sp,
                                    color = Color(0xFF777777),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isCur) Text("使用中", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("粘贴插件 JS 下载链接", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onImport(url) }, enabled = !importing) {
                        Text(if (importing) "导入中…" else "导入")
                    }
                }
                Text(
                    "点击下方关闭",
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }

    @Composable
    private fun MessageDialog(text: String, onClose: () -> Unit) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onClose() },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text, fontSize = 16.sp, color = Color(0xFFDDDDDD), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 48.dp))
                Spacer(Modifier.height(24.dp))
                Text("点击任意处关闭", fontSize = 12.sp, color = Color(0xFF666666))
            }
        }
    }

    /** Glide 封面桥：Compose 内复用现有 Glide 加载（避免引入 coil 增加体积）。 */
    @Composable
    private fun GlideImage(url: String?, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(url) {
            if (url.isNullOrEmpty()) {
                bitmap = null
                return@LaunchedEffect
            }
            val bmp = withContext(Dispatchers.IO) {
                try {
                    Glide.with(context).asBitmap().load(url).submit().get()
                } catch (e: Exception) {
                    null
                }
            }
            bitmap = bmp
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "封面",
                modifier = modifier,
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier.background(PLACEHOLDER_COLOR))
        }
    }

    private fun timeText(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "--:--"
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }
}