package com.fongmi.android.tv.music.ui

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.bumptech.glide.request.RequestOptions
import com.fongmi.android.tv.R
import com.fongmi.android.tv.music.core.LocalMusicScanner
import com.fongmi.android.tv.music.core.LrcParser
import com.fongmi.android.tv.music.core.MusicDownloader
import com.fongmi.android.tv.music.core.MusicLibrary
import com.fongmi.android.tv.music.model.MusicMedia
import com.fongmi.android.tv.music.model.MusicSheet
import com.fongmi.android.tv.music.model.RepeatMode
import com.fongmi.android.tv.music.plugin.MusicRepository
import com.fongmi.android.tv.music.plugin.MusicSource
import com.fongmi.android.tv.music.service.MusicPlaybackService
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.ui.common.UnifiedBackup
import com.fongmi.android.tv.ui.common.UnifiedSettingsDialog
import com.fongmi.android.tv.utils.Notify
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern
import org.json.JSONObject

/**
 * 音乐界面（Compose 版）：搜索 → 结果列表 → 点击播放。
 * 状态由 [UiState] 持有（Compose 可观察），播放仍交给 [MusicPlaybackService]（后台前台服务），
 * 本页订阅其回调刷新 UI。替换原 Java + View 实现，保持对外入口/接口不变。
 */
class MusicActivity : AppCompatActivity(), MusicPlaybackService.Listener {

    // ------------------------------------------------------------ UI 状态

    private class UiState {
        var results by mutableStateOf<List<MusicMedia>>(emptyList())
        // 聚合搜索分组：一个音源一组（单源时为空列表，退化为平铺）
        var searchGroups by mutableStateOf<List<MusicRepository.Aggregated>>(emptyList())
        var searching by mutableStateOf(false)
        var current by mutableStateOf<MusicMedia?>(null)
        var playing by mutableStateOf(false)
        var stateText by mutableStateOf("idle")
        var positionMs by mutableStateOf(0L)
        var durationMs by mutableStateOf(0L)
        var coverUrl by mutableStateOf("")
        var lyricHint by mutableStateOf("暂无歌词")
        var mode by mutableStateOf(RepeatMode.LIST)
        var speed by mutableStateOf(1f)
        // 睡眠定时：ui.sleepUntil = 触发时刻 epoch 毫秒；<0 未设置
        var sleepUntil by mutableStateOf(-1L)
        var sleepDialogVisible by mutableStateOf(false)
        var lyricDialogVisible by mutableStateOf(false)
        var messageVisible by mutableStateOf(false)
        var messageText by mutableStateOf("")
        // 多音源
        var sources by mutableStateOf<List<MusicRepository.PluginInfo>>(emptyList())
        var currentSource by mutableStateOf("netease")
        var sourceDialogVisible by mutableStateOf(false)
        var importing by mutableStateOf(false)
        // 下载状态（MusicDownloader 回调递增触发重绘）
        var downloadTick by mutableStateOf(0)
        // 收藏/最近播放
        var favorites by mutableStateOf<List<MusicMedia>>(emptyList())
        var history by mutableStateOf<List<MusicMedia>>(emptyList())
        var libraryTick by mutableStateOf(0)
        // 顶部 Tab（0 搜索 / 1 歌单 / 2 我的音乐）
        var tab by mutableStateOf(0)
        // 歌单页：榜单分组 + 推荐分类 + 分类歌单
        var topGroups by mutableStateOf<List<MusicSource.SheetGroup>>(emptyList())
        var sheetTags by mutableStateOf<List<String>>(emptyList())
        var tagSheets by mutableStateOf<List<MusicSheet>>(emptyList())
        var activeTag by mutableStateOf("")
        var sheetsLoading by mutableStateOf(false)
        var tagSheetsLoading by mutableStateOf(false)
        // 歌单导入弹窗
        var importDialogVisible by mutableStateOf(false)
        // 歌单/榜单/歌手/导入结果详情视图：非 null 时主区显示详情
        var sheetView by mutableStateOf<SheetView?>(null)
        // 本地音乐（扫描 MediaStore）
        var localMusic by mutableStateOf<List<MusicMedia>>(emptyList())
        var localLoading by mutableStateOf(false)
        var localLoaded by mutableStateOf(false)
        // 下载管理（进行中列表 + 已完成文件）
        var downloads by mutableStateOf<List<MusicMedia>>(emptyList())
        var doneFiles by mutableStateOf<List<File>>(emptyList())
        // 自建歌单
        var playlists by mutableStateOf<List<MusicLibrary.Playlist>>(emptyList())
        // 加歌单弹窗
        var playlistPickerVisible by mutableStateOf(false)
        var pickerMedia by mutableStateOf<MusicMedia?>(null)
        // 歌单新建/重命名输入弹窗
        var playlistDialogVisible by mutableStateOf(false)
        var playlistDialogTitle by mutableStateOf("")
        var playlistDialogText by mutableStateOf("")
        var playlistDialogTarget by mutableStateOf<String?>(null)
        // “我的音乐”子 Tab：0 收藏 / 1 最近 / 2 本地 / 3 下载 / 4 歌单
        var libTab by mutableStateOf(0)
        // 统一设置弹窗（与小说模块共用：主题/皮肤 + 备份/恢复，单一 JSON）
        var unifiedSettingsVisible by mutableStateOf(false)
    }

    /** 详情视图（歌单详情 / 榜单详情 / 歌手热歌 / 导入结果 共用）。 */
    private class SheetView {
        var title by mutableStateOf("")
        var cover by mutableStateOf("")
        var subtitle by mutableStateOf("")
        var items by mutableStateOf<List<MusicMedia>>(emptyList())
        var loading by mutableStateOf(false)
        var error by mutableStateOf("")
        /** 非空=自定义歌单详情（行内显示「移除」入口）。 */
        var playlistName by mutableStateOf("")
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

    /** 本地音乐权限（Android 13+ READ_MEDIA_AUDIO / 旧版 READ_EXTERNAL_STORAGE）。 */
    private val audioPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadLocalMusic()
        else Notify.show("需要音频读取权限才能扫描本地音乐")
    }

    /** 本地 JS 插件文件选择器（SAF）。 */
    private val localPluginPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocalPlugin(uri)
    }

    /** 歌单文本文件选择器（.txt/.lst 等，每行一个歌名/歌手）。 */
    private val importTextPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTextPlaylist(uri)
    }

    /** 统一备份导出（与小说模块共用单一 JSON）。 */
    private val backupExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportBackup(uri)
    }

    /** 统一备份恢复。 */
    private val backupImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(uri)
    }

    /** 导出统一备份：音乐收藏/最近/歌单 + 小说书架/进度/书源/订阅源 单文件。 */
    private fun exportBackup(uri: Uri) {
        Thread {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter(StandardCharsets.UTF_8)?.use {
                    it.write(UnifiedBackup.export())
                }
                runOnUiThread { Notify.show("备份完成（音乐收藏/歌单/阅读/订阅）") }
            } catch (e: Exception) {
                runOnUiThread { Notify.show("导出失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    /** 恢复统一备份（覆盖音乐收藏/最近/歌单与阅读各库）。 */
    private fun importBackup(uri: Uri) {
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    runOnUiThread { Notify.show("备份文件为空") }
                    return@Thread
                }
                val ok = UnifiedBackup.import(text)
                runOnUiThread {
                    refreshLibrary()
                    refreshPlaylists()
                    Notify.show(if (ok) "恢复完成（音乐/阅读/订阅）" else "恢复失败：不是有效的备份文件")
                }
            } catch (e: Exception) {
                runOnUiThread { Notify.show("恢复失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    /** 文本歌单导入：读文本 → 逐行作为关键词批量聚合搜索 → 结果替换当前搜索列表。 */
    private fun importTextPlaylist(uri: Uri) {
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                val keywords = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
                    .distinct()
                    .take(50)
                if (keywords.isEmpty()) {
                    runOnUiThread { Notify.show("文本为空：每行放一个歌名（可带 - 歌手）") }
                    return@Thread
                }
                runOnUiThread {
                    ui.importDialogVisible = false
                    ui.searching = true
                }
                MusicRepository.get().searchMany(keywords).whenComplete { groups, err ->
                    runOnUiThread {
                        ui.searching = false
                        if (err != null) {
                            Notify.show("导入搜索失败：" + friendly(err))
                        } else {
                            val g = groups ?: emptyList()
                            ui.searchGroups = g
                            ui.results = g.flatMap { it.items }
                            ui.tab = 0
                            Notify.show(
                                if (ui.results.isEmpty()) "未找到：请检查关键词"
                                else "歌单搜索完成：${keywords.size} 个关键词，共 ${ui.results.size} 首"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Notify.show("文本读取失败：" + (e.message ?: "未知错误")) }
            }
        }.start()
    }

    /** 遥控器/实体媒体键：播放暂停、上下首（覆盖系统不接管的按键；耳机键经 MediaSession 走服务侧）。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_HEADSETHOOK -> {
                service?.toggle()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                service?.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                service?.next()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                service?.prev()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

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
            // 换绑同步：倍速与睡眠定时以服务当前态为准
            onSpeedChanged(service?.speed() ?: 1f)
            onSleepTimerChanged(service?.sleepUntilMillis() ?: -1L)
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
        MusicLibrary.get().init(this)
        refreshLibrary()
        MusicDownloader.get().init(this)
        MusicDownloader.get().setListener(object : MusicDownloader.Listener {
            override fun onStateChanged() {
                ui.downloadTick = ui.downloadTick + 1
                refreshDownloads()
            }

            override fun onProgress(key: String, percent: Int) {
                ui.downloadTick = ui.downloadTick + 1
                refreshDownloads()
            }
        })
        // 插件未就绪前的占位：显示「加载中…」而不是误导性的 unknown；readyFuture 完成后刷新为真实音源
        ui.currentSource = "加载中…"
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
                    onDownloadAt = { index -> downloadAt(index) },
                    onFavoriteAt = { index -> toggleFavoriteAt(index) },
                    onSelectTab = { ui.tab = it },
                    onLoadSheets = ::loadSheetsTab,
                    onOpenTag = ::loadTagSheets,
                    onOpenSheet = ::openSheetDetail,
                    onOpenTop = ::openTopDetail,
                    onOpenArtistOf = ::openArtistOf,
                    onOpenImport = { ui.importDialogVisible = true },
                    onDoImport = ::doImport,
                    onCloseSheetView = { ui.sheetView = null },
                    onPlayList = ::playList,
                    onDownloadMedia = ::downloadMedia,
                    onToggleFavMedia = { media -> toggleFavorite(media) },
                    onToggleLyric = ::toggleLyric,
                    onCycleMode = ::cycleMode,
                    onSeek = ::seekTo,
                    onTogglePlay = { service?.toggle() },
                    onPrev = { service?.prev() },
                    onNext = { service?.next() },
                    onCloseLyricDialog = { ui.lyricDialogVisible = false },
                    onCloseMessage = { ui.messageVisible = false },
                    onOpenSources = { ui.sourceDialogVisible = true },
                    onCycleSpeed = ::cycleSpeed,
                    onOpenSleep = { ui.sleepDialogVisible = true },
                    onSwitchSource = ::switchSource,
                    onImportPlugin = ::importPlugin,
                    onPickLocalPlugin = { localPluginPicker.launch(arrayOf("*/*")) },
                    onPickTextImport = { importTextPicker.launch(arrayOf("text/*")) },
                    onAddPlaylist = ::openPlaylistPicker,
                    onScanLocal = ::requestLocalMusic,
                    onPlayLocalFile = ::playLocalFile,
                    onCancelDownload = { MusicDownloader.get().cancel(it) },
                    onDeleteDownloaded = { MusicDownloader.get().deleteDownloaded(it) },
                    onOpenPlaylist = { p ->
                        val v = SheetView()
                        v.title = p.name
                        v.subtitle = "自定义歌单 · ${p.items.size} 首"
                        v.items = p.items
                        v.playlistName = p.name
                        v.loading = false
                        ui.sheetView = v
                    },
                    onNewPlaylist = ::openNewPlaylistDialog,
                    onRenamePlaylist = ::openRenamePlaylistDialog,
                    onDeletePlaylist = { name ->
                        MusicLibrary.get().deletePlaylist(name)
                        refreshPlaylists()
                        Notify.show("歌单已删除")
                    },
                    onRemoveFromPlaylist = { name, idx ->
                        MusicLibrary.get().removeFromPlaylist(name, idx)
                        ui.sheetView?.let { v ->
                            if (v.playlistName == name) {
                                v.items = v.items.filterIndexed { i, _ -> i != idx }
                                v.subtitle = "自定义歌单 · ${v.items.size} 首"
                                if (v.items.isEmpty()) {
                                    ui.sheetView = null
                                    Notify.show("歌单已清空")
                                }
                            }
                        }
                        refreshPlaylists()
                    },
                )
            }
        }
        startAndBindService()
        // 先渲染 UI，等插件引擎就绪（readyFuture）后再自动搜索，避免首帧卡顿
        MusicRepository.get().readyFuture().whenComplete { _, _ ->
            runOnUiThread {
                refreshSourceInfo()
                val p = MusicRepository.get().platform()
                if (p.isEmpty()) {
                    // 源加载失败：不发起无谓搜索，状态栏直接展示原因（音源弹窗可见完整列表）
                    val errs = MusicRepository.get().loadErrors()
                    ui.stateText = if (errs.isEmpty()) "源加载失败" else "源加载失败：" + errs.first().take(80)
                    return@runOnUiThread
                }
                if (ui.stateText == "idle" || ui.stateText.contains("music ")) ui.stateText = "ready"
                // 不默认自动搜索（早期调试遗留行为）：首屏保持空态，由用户输入触发；
                // 避免插件就绪后立刻抢占沙箱线程解析大响应（歌单/榜单并发加载时的卡顿源）。
            }
        }
    }

    /** 刷新音源列表与当前源显示（插件为加载完成前显示「加载中…」，失败显示「加载失败」并附原因）。 */
    private fun refreshSourceInfo() {
        ui.sources = MusicRepository.get().plugins()
        val p = MusicRepository.get().platform()
        ui.currentSource = when {
            p.isNotEmpty() -> p
            MusicRepository.get().loadErrors().isNotEmpty() -> "加载失败"
            else -> "加载中…"
        }
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
        MusicRepository.get().searchAll(keyword.trim()).whenComplete { groups, error ->
            runOnUiThread {
                handler.removeCallbacksAndMessages(null)
                ui.searching = false
                if (error != null) {
                    logSearch("search_error", "kw=$lastKeyword err=${friendly(error)}")
                    Notify.show("搜索失败：" + friendly(error))
                } else {
                    val g = groups ?: emptyList()
                    ui.searchGroups = g
                    ui.results = g.flatMap { it.items }
                    if (ui.results.isEmpty()) {
                        logSearch("search_empty", "kw=$lastKeyword src=${MusicRepository.get().platform()}")
                        Notify.show("未找到相关歌曲")
                    }
                }
            }
        }
    }

    /** 搜索链路现场落盘（外部 logs/search.log），便于排查“转圈/未找到”的具体失败原因。 */
    private fun logSearch(tag: String, detail: String) {
        try {
            val dir = getExternalFilesDir(null)?.let { File(it, "logs") } ?: return
            if (!dir.exists()) dir.mkdirs()
            File(dir, "search.log").appendText(
                System.currentTimeMillis().toString() + " [" + tag + "] " + detail + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    private fun playAt(index: Int) {
        val s = service ?: return
        s.play(ArrayList(ui.results), index)
    }

    /** 下载第 index 首：交给 MusicDownloader（内部去重/取 URL/进度）。 */
    private fun downloadAt(index: Int) {
        val media = ui.results.getOrNull(index) ?: return
        MusicDownloader.get().download(media)
    }

    // ------------------------------------------------------------ 收藏/最近播放

    /** 刷新收藏与最近播放（持久化数据 → UI state）。 */
    private fun refreshLibrary() {
        ui.favorites = MusicLibrary.get().favorites()
        ui.history = MusicLibrary.get().history()
        ui.libraryTick = ui.libraryTick + 1
    }

    // ------------------------------------------------------------ 本地音乐 / 下载 / 歌单

    /** 后台扫描本地 mp3（MediaStore）。 */
    private fun loadLocalMusic() {
        if (ui.localLoading) return
        ui.localLoading = true
        Thread {
            val list = LocalMusicScanner.scan(applicationContext)
            runOnUiThread {
                ui.localMusic = list
                ui.localLoading = false
                ui.localLoaded = true
                if (list.isEmpty()) Notify.show("未扫描到本地 mp3 音乐")
            }
        }.start()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** 「扫描本地音乐」入口：有权限直接扫，无权限先申请（Android 13+ READ_MEDIA_AUDIO）。 */
    private fun requestLocalMusic() {
        if (hasAudioPermission()) loadLocalMusic()
        else audioPermLauncher.launch(audioPermission())
    }

    /** 刷新下载管理数据（进行中 + 已完成文件）。 */
    private fun refreshDownloads() {
        ui.downloads = MusicDownloader.get().runningList()
        ui.doneFiles = MusicDownloader.get().completedFiles()
    }

    /** 刷新歌单列表。 */
    private fun refreshPlaylists() {
        ui.playlists = MusicLibrary.get().playlists()
    }

    /** 打开「加入歌单」弹窗。 */
    private fun openPlaylistPicker(media: MusicMedia) {
        ui.pickerMedia = media
        refreshPlaylists()
        ui.playlistPickerVisible = true
    }

    /** 加入指定歌单。 */
    private fun pickPlaylist(name: String) {
        val m = ui.pickerMedia ?: return
        if (MusicLibrary.get().addToPlaylist(name, m)) {
            refreshPlaylists()
            Notify.show("已加入歌单「$name」")
        } else {
            Notify.show("加入失败")
        }
        ui.playlistPickerVisible = false
        ui.pickerMedia = null
    }

    /** 新建/重命名歌单输入弹窗提交。 */
    private fun submitPlaylistDialog(value: String) {
        val target = ui.playlistDialogTarget
        ui.playlistDialogVisible = false
        if (value.isBlank()) {
            Notify.show("名称不能为空")
            return
        }
        if (target == null) {
            val named = value.trim()
            if (MusicLibrary.get().createPlaylist(named)) {
                refreshPlaylists()
                val m = ui.pickerMedia
                if (m != null) {
                    MusicLibrary.get().addToPlaylist(named, m)
                    refreshPlaylists()
                    ui.pickerMedia = null
                    Notify.show("已创建并加入歌单「$named」")
                } else {
                    Notify.show("歌单已创建")
                }
            } else {
                Notify.show("创建失败（重名或空名）")
            }
        } else if (MusicLibrary.get().renamePlaylist(target, value)) {
            refreshPlaylists()
            Notify.show("已重命名")
        } else {
            Notify.show("重命名失败（重名或空名）")
        }
    }

    private fun openNewPlaylistDialog() {
        ui.playlistDialogTitle = "新建歌单"
        ui.playlistDialogText = ""
        ui.playlistDialogTarget = null
        ui.playlistDialogVisible = true
    }

    private fun openRenamePlaylistDialog(name: String) {
        ui.playlistDialogTitle = "重命名歌单"
        ui.playlistDialogText = name
        ui.playlistDialogTarget = name
        ui.playlistDialogVisible = true
    }

    /** 播放一个已下载的本地文件。 */
    private fun playLocalFile(file: File) {
        val m = MusicMedia(file.name, file.nameWithoutExtension, "", "", 0, null, Uri.fromFile(file).toString(), null, null)
        m.source = "local"
        playList(listOf(m), 0)
    }

    /** 读 SAF uri 文本内容（插件 JS）并在后台导入。 */
    private fun importLocalPlugin(uri: Uri) {
        Thread {
            try {
                val name = queryName(contentResolver, uri) ?: "plugin.js"
                val code = contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                if (code.isEmpty()) {
                    runOnUiThread { Notify.show("插件文件读取失败") }
                    return@Thread
                }
                MusicRepository.get().importLocalFile(name, code).whenComplete { ok, _ ->
                    runOnUiThread {
                        if (ok == true) {
                            refreshSourceInfo()
                            ui.sourceDialogVisible = false
                            Notify.show("插件导入成功：" + name)
                        } else {
                            Notify.show("插件导入失败（无法解析）")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Notify.show("插件读取失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    private fun queryName(cr: ContentResolver, uri: Uri): String? {
        return try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 切换当前行收藏状态，并同步到 UI（无序遍历安全）。 */
    private fun toggleFavoriteAt(index: Int) {
        val media = ui.results.getOrNull(index) ?: return
        MusicLibrary.get().toggleFavorite(media)
        refreshLibrary()
    }

    /** 切换收藏（收藏/历史弹窗内播放项点击）。 */
    private fun toggleFavorite(media: MusicMedia) {
        MusicLibrary.get().toggleFavorite(media)
        refreshLibrary()
    }

    /** 播放任意列表（service 队列整表装载，从 index 起播；搜索/歌单详情/收藏/历史/导入共用）。 */
    private fun playList(list: List<MusicMedia>, index: Int) {
        val s = service ?: return
        if (list.isEmpty() || index !in list.indices) return
        s.play(ArrayList(list), index)
    }

    /** 下载指定歌曲（歌单详情/导入结果行共用）。 */
    private fun downloadMedia(media: MusicMedia) {
        MusicDownloader.get().download(media)
    }

    // ------------------------------------------------------------ 歌单 / 榜单 / 歌手 / 导入

    /** 歌单 Tab 一次性装载：榜单分组 + 推荐分类（并行提交，互不嵌套等待）。
     *  分类歌单改为用户点标签时才加载：此前自动加载第一个分类会再多 1 次网络请求 + 30 张封面，
     *  与 63→20 张榜单封面叠加，正是低端机上「点进歌单就卡、弹无响应」的诱因。 */
    private fun loadSheetsTab() {
        if (ui.sheetsLoading) return
        ui.sheetsLoading = true
        ui.topGroups = emptyList()
        ui.sheetTags = emptyList()
        ui.tagSheets = emptyList()
        ui.activeTag = ""
        val g = MusicRepository.get().topLists()
        val t = MusicRepository.get().recommendTags()
        // 两个请求分别完成即分别刷新对应状态，不再串行嵌套等待
        g.whenComplete { groups, _ ->
            runOnUiThread { ui.topGroups = groups ?: emptyList() }
        }
        t.whenComplete { tags, _ ->
            runOnUiThread {
                ui.sheetTags = tags ?: emptyList()
                ui.sheetsLoading = false
            }
        }
    }

    /** 加载某个推荐分类的歌单（结果列表缓存于 ui.tagSheets）。 */
    private fun loadTagSheets(tag: String) {
        if (ui.activeTag == tag && ui.tagSheets.isNotEmpty()) return
        ui.activeTag = tag
        ui.tagSheetsLoading = true
        ui.tagSheets = emptyList()
        MusicRepository.get().sheetsByTag(tag, 1).whenComplete { list, _ ->
            runOnUiThread {
                ui.tagSheetsLoading = false
                ui.tagSheets = list ?: emptyList()
                if (ui.tagSheets.isEmpty()) Notify.show("「$tag」分类暂无歌单")
            }
        }
    }

    /** 打开详情视图（歌单/榜单/歌手/导入共用），loader 负责拉取歌曲列表。 */
    private fun openSheetView(title: String, cover: String, subtitle: String, loader: () -> CompletableFuture<List<MusicMedia>>) {
        val v = SheetView()
        v.title = title
        v.cover = cover
        v.subtitle = subtitle
        v.loading = true
        ui.sheetView = v
        loader().whenComplete { list, e ->
            runOnUiThread {
                v.loading = false
                if (e != null) {
                    v.error = "加载失败：" + friendly(e)
                } else {
                    v.items = list ?: emptyList()
                    if (v.items.isEmpty()) v.error = "该歌单暂无内容"
                }
            }
        }
    }

    private fun openSheetDetail(sheet: MusicSheet) {
        openSheetView(sheet.title, sheet.cover, "播放量 " + fmtCount(sheet.playCount), { MusicRepository.get().sheetDetail(sheet, 1) })
    }

    private fun openTopDetail(sheet: MusicSheet) {
        openSheetView(sheet.title, sheet.cover, sheet.description.ifEmpty { "官方榜" }, { MusicRepository.get().topListDetail(sheet, 1) })
    }

    private fun openArtistDetail(artist: MusicSheet) {
        openSheetView(artist.title, artist.cover, "热门歌曲", { MusicRepository.get().artistSongs(artist, 1) })
    }

    /** 搜索结果行的歌手入口：解析 extra 中的 artistId 后进歌手热歌页。 */
    private fun openArtistOf(media: MusicMedia) {
        val id = artistIdOf(media) ?: return
        val artist = MusicSheet(id, media.artist.ifEmpty { media.title }, media.cover, media.artist, "", -1, -1)
        artist.source = media.source
        openArtistDetail(artist)
    }

    private fun artistIdOf(media: MusicMedia): String? {
        if (media.extra.isNullOrEmpty()) return null
        return try {
            JSONObject(media.extra).optString("artistId").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** 歌单导入：URL → 歌曲列表进详情视图。 */
    private fun doImport(url: String) {
        if (url.isBlank()) {
            Notify.show("请输入歌单链接")
            return
        }
        ui.importDialogVisible = false
        val v = SheetView()
        v.title = "导入歌单…"
        v.loading = true
        v.subtitle = url.trim()
        ui.sheetView = v
        MusicRepository.get().importSheet(url.trim()).whenComplete { list, e ->
            runOnUiThread {
                v.loading = false
                if (e != null) {
                    v.error = "导入失败：" + friendly(e)
                } else {
                    val items = list ?: emptyList()
                    if (items.isEmpty()) {
                        v.error = "未解析到歌曲（支持网易云歌单/榜单链接或纯 id）"
                    } else {
                        v.title = "导入结果（${items.size} 首）"
                        v.items = items
                    }
                }
            }
        }
    }

    /** 播放量/数量格式化：万/亿。 */
    private fun fmtCount(n: Long): String {
        if (n <= 0) return ""
        return when {
            n >= 100_000_000 -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
            n >= 10_000 -> String.format(Locale.US, "%.1f万", n / 10_000.0)
            else -> n.toString()
        }
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

    override fun onSleepTimerChanged(untilMs: Long) {
        runOnUiThread { ui.sleepUntil = untilMs }
    }

    override fun onSleepTriggered() {
        runOnUiThread {
            ui.sleepUntil = -1L
            Notify.show("睡眠定时已触发，播放已暂停")
        }
    }

    override fun onSpeedChanged(speed: Float) {
        runOnUiThread { ui.speed = speed }
    }

    /** 倍速循环：0.75x → 1.0x → 1.25x → 1.5x → 2.0x。 */
    private fun cycleSpeed() {
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val cur = ui.speed
        var next = speeds[0]
        for (s in speeds) {
            if (s > cur + 0.001f) {
                next = s
                break
            }
        }
        service?.setSpeed(next)
        ui.speed = next
        // 内核（服务侧）未建播放器时可能短暂不同步，onSpeedChanged 会纠正
        Notify.show("倍速 ${fmtSpeed(next)}x")
    }

    /** 选择睡眠定时（毫秒；0 取消）。 */
    private fun setSleepTimer(ms: Long) {
        service?.setSleepTimer(ms)
        if (ms <= 0) Notify.show("睡眠定时已关闭")
        else Notify.show("将在 ${sleepTimeText(System.currentTimeMillis() + ms)} 暂停播放")
    }

    private fun fmtSpeed(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    /** epoch 毫秒 → "HH:mm" 墙钟。 */
    private fun sleepTimeText(untilMs: Long): String {
        @Suppress("DEPRECATION")
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = untilMs
        return String.format(Locale.getDefault(), "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
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
        onDownloadAt: (Int) -> Unit,
        onFavoriteAt: (Int) -> Unit,
        onSelectTab: (Int) -> Unit,
        onLoadSheets: () -> Unit,
        onOpenTag: (String) -> Unit,
        onOpenSheet: (MusicSheet) -> Unit,
        onOpenTop: (MusicSheet) -> Unit,
        onOpenArtistOf: (MusicMedia) -> Unit,
        onOpenImport: () -> Unit,
        onDoImport: (String) -> Unit,
        onCloseSheetView: () -> Unit,
        onPlayList: (List<MusicMedia>, Int) -> Unit,
        onDownloadMedia: (MusicMedia) -> Unit,
        onToggleFavMedia: (MusicMedia) -> Unit,
        onToggleLyric: () -> Unit,
        onCycleMode: () -> Unit,
        onSeek: (Float) -> Unit,
        onTogglePlay: () -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onCloseLyricDialog: () -> Unit,
        onCloseMessage: () -> Unit,
        onOpenSources: () -> Unit,
        onCycleSpeed: () -> Unit,
        onOpenSleep: () -> Unit,
        onSwitchSource: (String) -> Unit,
        onImportPlugin: (String) -> Unit,
        onPickLocalPlugin: () -> Unit,
        onPickTextImport: () -> Unit,
        onAddPlaylist: (MusicMedia) -> Unit,
        onScanLocal: () -> Unit,
        onPlayLocalFile: (File) -> Unit,
        onCancelDownload: (MusicMedia) -> Unit,
        onDeleteDownloaded: (File) -> Unit,
        onOpenPlaylist: (MusicLibrary.Playlist) -> Unit,
        onNewPlaylist: () -> Unit,
        onRenamePlaylist: (String) -> Unit,
        onDeletePlaylist: (String) -> Unit,
        onRemoveFromPlaylist: (String, Int) -> Unit,
    ) {
        var keyword by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                TopTabs(tab = ui.tab, onSelect = onSelectTab, onOpenSources = onOpenSources)
                val view = ui.sheetView
                if (view != null) {
                    // 详情视图（歌单/榜单/歌手/导入结果）：覆盖当前 Tab 内容，播放条保持
                    SheetDetailView(
                        view = view,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onBack = onCloseSheetView,
                        onPlay = { idx -> onPlayList(view.items, idx) },
                        onDownload = onDownloadMedia,
                        onFavorite = onToggleFavMedia,
                        onArtist = onOpenArtistOf,
                        onRemoveFromPlaylist = onRemoveFromPlaylist,
                    )
                } else {
                    when (ui.tab) {
                        1 -> MyContent(
                            ui = ui,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onPlayList = onPlayList,
                            onDownloadMedia = onDownloadMedia,
                            onToggleFavMedia = onToggleFavMedia,
                            onAddPlaylist = onAddPlaylist,
                            onScanLocal = onScanLocal,
                            onPlayLocalFile = onPlayLocalFile,
                            onCancelDownload = onCancelDownload,
                            onDeleteDownloaded = onDeleteDownloaded,
                            onOpenPlaylist = { p ->
                                val v = SheetView()
                                v.title = p.name
                                v.subtitle = "自定义歌单 · ${p.items.size} 首"
                                v.items = p.items
                                v.playlistName = p.name
                                v.loading = false
                                v.error = ""
                                ui.sheetView = v
                            },
                            onNewPlaylist = onNewPlaylist,
                            onRenamePlaylist = onRenamePlaylist,
                            onDeletePlaylist = onDeletePlaylist,
                            onOpenImport = onOpenImport,
                        )
                        else -> {
                            // 搜索页 = 搜索框 + 结果列表；未搜索时展示歌单内容（榜单/分类），避免页面单调
                            SearchBar(keyword, { keyword = it }) { onSearch(keyword) }
                            if (ui.searching) {
                                CircularProgressIndicator(
                                    Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(28.dp)
                                )
                            } else if (keyword.isNotBlank() || ui.results.isNotEmpty()) {
                                ResultList(
                                    groups = ui.searchGroups,
                                    items = ui.results,
                                    libraryTick = ui.libraryTick,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    onPlayAt = onPlayAt,
                                    onDownloadAt = onDownloadAt,
                                    onFavoriteAt = onFavoriteAt,
                                    onArtist = onOpenArtistOf,
                                    onAddPlaylist = { idx -> ui.results.getOrNull(idx)?.let(onAddPlaylist) },
                                )
                            } else {
                                LaunchedEffect(Unit) { onLoadSheets() }
                                SheetContent(
                                    ui = ui,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    onOpenTag = onOpenTag,
                                    onOpenSheet = onOpenSheet,
                                    onOpenTop = onOpenTop,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF333333))
                PlayerBar(ui, onToggleLyric, onCycleMode, onSeek, onTogglePlay, onPrev, onNext, onCycleSpeed, onOpenSleep)
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
                onPickLocal = onPickLocalPlugin,
            )
        }
        if (ui.importDialogVisible) {
            ImportDialog(
                onClose = { ui.importDialogVisible = false },
                onOk = onDoImport,
                onPickTextFile = onPickTextImport,
            )
        }
        if (ui.messageVisible) {
            MessageDialog(text = ui.messageText, onClose = onCloseMessage)
        }
        if (ui.sleepDialogVisible) {
            SleepDialog(
                current = ui.sleepUntil,
                onClose = { ui.sleepDialogVisible = false },
                onSelect = ::setSleepTimer,
            )
        }
        if (ui.playlistPickerVisible) {
            PlaylistPickerDialog(
                playlists = ui.playlists,
                onClose = {
                    ui.playlistPickerVisible = false
                    ui.pickerMedia = null
                },
                onPick = ::pickPlaylist,
                onNew = {
                    ui.playlistPickerVisible = false
                    openNewPlaylistDialog()
                },
            )
        }
        if (ui.playlistDialogVisible) {
            PlaylistInputDialog(
                title = ui.playlistDialogTitle,
                initial = ui.playlistDialogText,
                onClose = { ui.playlistDialogVisible = false },
                onOk = ::submitPlaylistDialog,
            )
        }
        if (ui.unifiedSettingsVisible) {
            // 统一设置与小说模块共用：主题/皮肤（作用阅读与界面）+ 备份/恢复（单一 JSON 含音乐本地库）
            UnifiedSettingsDialog(
                theme = ReaderStore.get().theme,
                fontSize = ReaderStore.get().fontSize,
                lineHeight = ReaderStore.get().lineHeight,
                onTheme = {
                    ReaderStore.get().theme = it
                    ReaderStore.get().saveSettings()
                },
                onFontSize = {
                    ReaderStore.get().fontSize = it
                    ReaderStore.get().saveSettings()
                },
                onLineHeight = {
                    ReaderStore.get().lineHeight = it
                    ReaderStore.get().saveSettings()
                },
                onExport = {
                    ui.unifiedSettingsVisible = false
                    backupExportPicker.launch("tv_fongmi_backup.json")
                },
                onImport = {
                    ui.unifiedSettingsVisible = false
                    backupImportPicker.launch(arrayOf("*/*"))
                },
                onClose = { ui.unifiedSettingsVisible = false },
            )
        }
    }

    /** 顶部 Tab：搜索 / 歌单 / 我的音乐。 */
    @Composable
    private fun TopTabs(tab: Int, onSelect: (Int) -> Unit, onOpenSources: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 搜索页已合并歌单页内容，顶部仅保留「搜索 / 我的音乐」
            listOf("搜索", "我的音乐").forEachIndexed { index, name ->
                TabItem(name, tab == index) { onSelect(index) }
                if (index < 1) Spacer(Modifier.width(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "设置",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { ui.unifiedSettingsVisible = true }.padding(vertical = 4.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "音源 " + ui.currentSource + " ▾",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenSources() }.padding(vertical = 4.dp),
            )
        }
        HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(top = 4.dp))
    }

    @Composable
    private fun TabItem(name: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            name,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF888888),
            modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        )
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
    private fun ResultList(groups: List<MusicRepository.Aggregated>, items: List<MusicMedia>, libraryTick: Int, modifier: Modifier, onPlayAt: (Int) -> Unit, onDownloadAt: (Int) -> Unit, onFavoriteAt: (Int) -> Unit, onArtist: (MusicMedia) -> Unit, onAddPlaylist: (Int) -> Unit = {}) {
        @Suppress("UNUSED_EXPRESSION")
        libraryTick // 观察收藏变化：切换收藏后重绘心形图标
        LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
            if (groups.size > 1) {
                // 聚合搜索：按音源分组，每组一个标题头 + 歌曲行
                var offset = 0
                groups.filter { it.items.isNotEmpty() }.forEach { g ->
                    item(key = "grp_" + g.platform) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                g.label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${g.items.size} 首", fontSize = 11.sp, color = Color(0xFF777777))
                        }
                    }
                    g.items.forEachIndexed { i, media ->
                        val idx = offset + i
                        item(key = "grp_" + g.platform + "_" + i) {
                            MusicRow(
                                index = idx,
                                media = media,
                                onPlay = onPlayAt,
                                onDownload = onDownloadAt,
                                onFavorite = onFavoriteAt,
                                onArtist = onArtist,
                                onAddPlaylist = onAddPlaylist,
                            )
                        }
                    }
                    offset += g.items.size
                }
            } else {
                itemsIndexed(items) { index, media ->
                    MusicRow(
                        index = index,
                        media = media,
                        onPlay = onPlayAt,
                        onDownload = onDownloadAt,
                        onFavorite = onFavoriteAt,
                        onArtist = onArtist,
                        onAddPlaylist = onAddPlaylist,
                    )
                }
            }
        }
    }

    /** 歌曲列表行（搜索 / 歌单详情 / 导入结果 / 我的音乐 共用）：封面 + 标题 + 歌手/专辑 + 时长 + 收藏 + 下载 + 加歌单。 */
    @Composable
    private fun MusicRow(
        index: Int,
        media: MusicMedia,
        onPlay: (Int) -> Unit,
        onDownload: (Int) -> Unit,
        onFavorite: (Int) -> Unit,
        onArtist: (MusicMedia) -> Unit,
        onAddPlaylist: (Int) -> Unit = {},
        /** 非 null 时行尾显示「移除」（自定义歌单整理用），回调返回行下标。 */
        onRemoveFromPlaylist: ((Int) -> Unit)? = null,
    ) {
        val vip = media.vip
        val sub = when {
            media.artist.isNotEmpty() && media.album.isNotEmpty() -> "${media.artist} · ${media.album}"
            media.artist.isNotEmpty() -> media.artist
            media.album.isNotEmpty() -> media.album
            else -> ""
        }
        val artistId = artistIdOf(media)
        val downloading = MusicDownloader.get().isRunning(media)
        val downloaded = MusicDownloader.get().isDone(media)
        Row(
            Modifier.fillMaxWidth()
                .clickable { onPlay(index) }
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
                if (artistId != null && media.artist.isNotEmpty() && media.album.isNotEmpty()) {
                    // 歌手可点（有 artistId）：进歌手热歌页；专辑部分不可点
                    Row {
                        Text(
                            text = media.artist,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onArtist(media) },
                        )
                        Text(
                            text = " · " + media.album,
                            fontSize = 12.sp,
                            color = Color(0xFF999999),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = sub,
                        fontSize = 12.sp,
                        color = Color(0xFF999999),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(fmt(media.durationMs), fontSize = 12.sp, color = Color(0xFF777777))
            // 收藏按钮：已收藏红色高亮，点击切换
            IconButton(
                onClick = { onFavorite(index) },
                modifier = Modifier.size(36.dp),
            ) {
                val isFav = MusicLibrary.get().isFavorite(media)
                Icon(
                    painterResource(R.drawable.ic_favorite),
                    contentDescription = "收藏",
                    tint = if (isFav) Color(0xFFFF4081) else Color(0xFF666666),
                )
            }
            // 下载按钮：下载中转圈，完成后变色；VIP 歌标记不可下载
            if (!vip) {
                IconButton(
                    onClick = { onDownload(index) },
                    modifier = Modifier.size(36.dp),
                ) {
                    if (downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            contentDescription = "下载",
                            tint = if (downloaded) MaterialTheme.colorScheme.primary else Color(0xFF999999),
                        )
                    }
                }
            }
            // 加歌单（本地/下载行也允许，方便整理）
            Text(
                "＋歌单",
                fontSize = 10.sp,
                color = Color(0xFF888888),
                modifier = Modifier.clickable { onAddPlaylist(index) }.padding(horizontal = 2.dp, vertical = 6.dp),
            )
            // 歌单整理入口：从自定义歌单移除该曲
            if (onRemoveFromPlaylist != null) {
                Text(
                    "移除",
                    fontSize = 10.sp,
                    color = Color(0xFFFFB74D),
                    modifier = Modifier.clickable { onRemoveFromPlaylist(index) }.padding(horizontal = 2.dp, vertical = 6.dp),
                )
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
        onCycleSpeed: () -> Unit,
        onOpenSleep: () -> Unit,
    ) {
        var dragFraction by remember { mutableStateOf<Float?>(null) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.stateText,
                    fontSize = 10.sp,
                    color = Color(0xFF888888),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
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
                Spacer(Modifier.width(14.dp))
                Text(
                    "倍速 ${ui.speed}x",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCycleSpeed() },
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    if (ui.sleepUntil > 0) "定时 ${sleepTimeText(ui.sleepUntil)}" else "定时",
                    fontSize = 12.sp,
                    color = if (ui.sleepUntil > 0) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB),
                    modifier = Modifier.clickable { onOpenSleep() },
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
        onPickLocal: () -> Unit,
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
                // 内置/导入插件加载失败时展示具体原因（定位 Rhino/转译/网络问题）
                val loadErrs = MusicRepository.get().loadErrors()
                if (loadErrs.isNotEmpty()) {
                    Text(
                        "源加载失败：\n" + loadErrs.joinToString("\n"),
                        fontSize = 11.sp,
                        color = Color(0xFFFF8A80),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
                    )
                    HorizontalDivider(color = Color(0x22FFFFFF))
                }
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "或选择本地 JS 文件导入",
                        fontSize = 12.sp,
                        color = Color(0xFF999999),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPickLocal) { Text("本地文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
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

    /** 我的音乐页面（Tab 2）：收藏 / 最近播放 / 本地音乐 / 下载管理 / 自建歌单 五子页。 */
    @Composable
    private fun MyContent(
        ui: UiState,
        modifier: Modifier = Modifier,
        onPlayList: (List<MusicMedia>, Int) -> Unit,
        onDownloadMedia: (MusicMedia) -> Unit,
        onToggleFavMedia: (MusicMedia) -> Unit,
        onAddPlaylist: (MusicMedia) -> Unit,
        onScanLocal: () -> Unit,
        onPlayLocalFile: (File) -> Unit,
        onCancelDownload: (MusicMedia) -> Unit,
        onDeleteDownloaded: (File) -> Unit,
        onOpenPlaylist: (MusicLibrary.Playlist) -> Unit,
        onNewPlaylist: () -> Unit,
        onRenamePlaylist: (String) -> Unit,
        onDeletePlaylist: (String) -> Unit,
        onOpenImport: () -> Unit,
    ) {
        Column(modifier) {
            // 子页签：收藏 / 最近 / 本地 / 下载 / 歌单
            val names = listOf("收藏", "最近", "本地", "下载", "歌单")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { i, name ->
                    val count = when (i) {
                        0 -> ui.favorites.size
                        1 -> ui.history.size
                        2 -> if (ui.localLoaded) ui.localMusic.size else 0
                        3 -> ui.downloads.size + ui.doneFiles.size
                        else -> ui.playlists.size
                    }
                    LibraryTab(if (count > 0) "$name $count" else name, ui.libTab == i) { ui.libTab = i }
                    Spacer(Modifier.width(4.dp))
                }
            }
            HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
            // 内容区固定权重容器：子页内部用 fillMaxSize 填满（避免子页各自依赖 ColumnScope）
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (ui.libTab) {
                    0 -> FavHistoryTab(
                        ui.favorites, "暂无收藏",
                        onPlayList, onDownloadMedia, onToggleFavMedia, onAddPlaylist,
                    )
                    1 -> FavHistoryTab(
                        ui.history, "暂无播放记录",
                        onPlayList, onDownloadMedia, onToggleFavMedia, onAddPlaylist,
                    )
                    2 -> LocalTab(ui, onPlayList, onDownloadMedia, onToggleFavMedia, onAddPlaylist, onScanLocal)
                    3 -> DownloadTab(ui, onPlayLocalFile, onCancelDownload, onDeleteDownloaded)
                    else -> PlaylistsTab(ui, onOpenPlaylist, onNewPlaylist, onRenamePlaylist, onDeletePlaylist, onOpenImport)
                }
            }
        }
    }

    /** 「我的音乐」收藏/最近播放共用列表。 */
    @Composable
    private fun FavHistoryTab(
        items: List<MusicMedia>,
        emptyText: String,
        onPlayList: (List<MusicMedia>, Int) -> Unit,
        onDownloadMedia: (MusicMedia) -> Unit,
        onToggleFavMedia: (MusicMedia) -> Unit,
        onAddPlaylist: (MusicMedia) -> Unit,
    ) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, fontSize = 13.sp, color = Color(0xFF666666))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(items) { index, media ->
                    MusicRow(
                        index = index,
                        media = media,
                        onPlay = { i -> onPlayList(items, i) },
                        onDownload = { onDownloadMedia(media) },
                        onFavorite = { onToggleFavMedia(media) },
                        onArtist = {},
                        onAddPlaylist = { onAddPlaylist(media) },
                    )
                    HorizontalDivider(color = Color(0x1AFFFFFF))
                }
            }
        }
    }

    /** 「我的音乐」本地音乐：未扫描时给扫描入口，已扫描展示列表，可重新扫描。 */
    @Composable
    private fun LocalTab(
        ui: UiState,
        onPlayList: (List<MusicMedia>, Int) -> Unit,
        onDownloadMedia: (MusicMedia) -> Unit,
        onToggleFavMedia: (MusicMedia) -> Unit,
        onAddPlaylist: (MusicMedia) -> Unit,
        onScanLocal: () -> Unit,
    ) {
        when {
            ui.localLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp))
            }
            !ui.localLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("扫描设备中的本地音乐（mp3）", fontSize = 13.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onScanLocal) { Text("扫描本地音乐") }
                }
            }
            ui.localMusic.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("未扫描到本地 mp3", fontSize = 13.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onScanLocal) { Text("重新扫描") }
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${ui.localMusic.size} 首", fontSize = 12.sp, color = Color(0xFF888888))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "重新扫描",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onScanLocal() }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(ui.localMusic) { index, media ->
                        MusicRow(
                            index = index,
                            media = media,
                            onPlay = { i -> onPlayList(ui.localMusic, i) },
                            onDownload = { onDownloadMedia(media) },
                            onFavorite = { onToggleFavMedia(media) },
                            onArtist = {},
                            onAddPlaylist = { onAddPlaylist(media) },
                        )
                        HorizontalDivider(color = Color(0x14FFFFFF))
                    }
                }
            }
        }
    }

    /** 「我的音乐」下载管理：进行中（进度+取消） + 已完成文件（播放+删除）。 */
    @Composable
    private fun DownloadTab(
        ui: UiState,
        onPlayLocalFile: (File) -> Unit,
        onCancelDownload: (MusicMedia) -> Unit,
        onDeleteDownloaded: (File) -> Unit,
    ) {
        if (ui.downloads.isEmpty() && ui.doneFiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无下载", fontSize = 13.sp, color = Color(0xFF666666))
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
            if (ui.downloads.isNotEmpty()) {
                item(key = "dl_head") {
                    Text(
                        "下载中 (${ui.downloads.size})",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCCCCCC),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                itemsIndexed(ui.downloads) { index, media ->
                    val pct = MusicDownloader.get().progressOf(media)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${media.title} - ${media.artist}",
                                fontSize = 13.sp, color = Color(0xFFDDDDDD),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("$pct%", fontSize = 12.sp, color = Color(0xFF999999))
                        Text(
                            "取消",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onCancelDownload(media) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    HorizontalDivider(color = Color(0x14FFFFFF))
                }
            }
            if (ui.doneFiles.isNotEmpty()) {
                item(key = "done_head") {
                    Text(
                        "已完成 (${ui.doneFiles.size})",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCCCCCC),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                itemsIndexed(ui.doneFiles) { index, file ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                fontSize = 13.sp, color = Color(0xFFDDDDDD),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(fileSize(file.length()), fontSize = 11.sp, color = Color(0xFF888888))
                        }
                        Text(
                            "播放",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onPlayLocalFile(file) }.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        Text(
                            "删除",
                            fontSize = 12.sp,
                            color = Color(0xFFFF8A80),
                            modifier = Modifier.clickable { onDeleteDownloaded(file) }.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    HorizontalDivider(color = Color(0x14FFFFFF))
                }
            }
        }
    }

    /** 「我的音乐」自建歌单：新建 / 打开整理 / 重命名 / 删除。 */
    @Composable
    private fun PlaylistsTab(
        ui: UiState,
        onOpenPlaylist: (MusicLibrary.Playlist) -> Unit,
        onNewPlaylist: () -> Unit,
        onRenamePlaylist: (String) -> Unit,
        onDeletePlaylist: (String) -> Unit,
        onOpenImport: () -> Unit,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("自建歌单", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCCCCCC))
                Spacer(Modifier.weight(1f))
                // 导入歌单（粘贴网易云歌单/榜单链接）统一收口到歌单管理页
                Button(onClick = onOpenImport, modifier = Modifier.height(32.dp)) { Text("导入歌单", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onNewPlaylist, modifier = Modifier.height(32.dp)) { Text("＋ 新建歌单", fontSize = 12.sp) }
            }
            if (ui.playlists.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("暂无歌单，点右上角新建", fontSize = 13.sp, color = Color(0xFF666666))
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(ui.playlists) { index, p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenPlaylist(p) }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 14.sp, color = Color(0xFFE0E0E0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.items.size} 首", fontSize = 11.sp, color = Color(0xFF888888))
                            }
                            Text(
                                "整理",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onOpenPlaylist(p) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Text(
                                "重命名",
                                fontSize = 12.sp,
                                color = Color(0xFFBBBBBB),
                                modifier = Modifier.clickable { onRenamePlaylist(p.name) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Text(
                                "删除",
                                fontSize = 12.sp,
                                color = Color(0xFFFF8A80),
                                modifier = Modifier.clickable { onDeletePlaylist(p.name) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        HorizontalDivider(color = Color(0x14FFFFFF))
                    }
                }
            }
        }
    }

    @Composable
    private fun LibraryTab(text: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF888888),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }

    /** 歌单页面（Tab 1）：导入入口 + 官方榜 + 推荐分类 + 分类歌单。 */
    @Composable
    private fun SheetContent(
        ui: UiState,
        modifier: Modifier = Modifier,
        onOpenTag: (String) -> Unit,
        onOpenSheet: (MusicSheet) -> Unit,
        onOpenTop: (MusicSheet) -> Unit,
    ) {
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("歌单", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE0E0E0))
            }
            if (ui.sheetsLoading) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(30.dp))
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    // 官方榜
                    ui.topGroups.forEach { group ->
                        item(key = "g-" + group.name) {
                            SheetGroupHeader(group.name)
                        }
                        item(key = "gl-" + group.name) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                items(group.items.size, key = { i -> "top-" + group.items[i].id }) { i ->
                                    val sheet = group.items[i]
                                    SheetCard(
                                        sheet = sheet,
                                        subtitle = sheet.description.ifEmpty { "官方榜" },
                                        onClick = { onOpenTop(sheet) },
                                    )
                                }
                            }
                        }
                    }
                    // 推荐分类
                    if (ui.sheetTags.isNotEmpty()) {
                        item(key = "tags") {
                            Text(
                                "推荐分类",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFCCCCCC),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        item(key = "tagrow") {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp),
                            ) {
                                ui.sheetTags.forEach { tag ->
                                    val selected = tag == ui.activeTag
                                    Text(
                                        tag,
                                        fontSize = 13.sp,
                                        color = if (selected) Color(0xFF141414) else Color(0xFFCCCCCC),
                                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x26FFFFFF))
                                            .clickable { onOpenTag(tag) }
                                            .padding(horizontal = 12.dp, vertical = 5.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                    // 分类歌单
                    if (ui.activeTag.isNotEmpty()) {
                        item(key = "cat") {
                            Text(
                                (if (ui.tagSheetsLoading) "加载「${ui.activeTag}」歌单…" else "「${ui.activeTag}」歌单"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFCCCCCC),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        item(key = "catrow") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                items(ui.tagSheets.size, key = { i -> "s-" + ui.tagSheets[i].id + "-" + i }) { i ->
                                    val sheet = ui.tagSheets[i]
                                    SheetCard(
                                        sheet = sheet,
                                        subtitle = "播放量 " + fmtCount(sheet.playCount),
                                        onClick = { onOpenSheet(sheet) },
                                    )
                                }
                            }
                        }
                    }
                    if (!ui.sheetsLoading && ui.topGroups.isEmpty() && ui.sheetTags.isEmpty()) {
                        item(key = "empty") {
                            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                Text("歌单加载失败，请检查网络或切换音源", fontSize = 13.sp, color = Color(0xFF666666))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SheetGroupHeader(name: String) {
        Text(
            name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFCCCCCC),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    /** 歌单/榜单卡片（横向滑动列表内）。 */
    @Composable
    private fun SheetCard(sheet: MusicSheet, subtitle: String, onClick: () -> Unit) {
        Column(
            Modifier.width(136.dp).padding(end = 10.dp)
                .clickable(onClick = onClick),
        ) {
            GlideImage(
                url = sheet.cover.takeIf { it.isNotEmpty() },
                modifier = Modifier.size(136.dp).clip(RoundedCornerShape(6.dp)),
                pixels = 400, // 136dp @3x ≈ 408px，贴近显示大小解码，避免大图全尺寸解码
            )
            Text(
                sheet.title,
                fontSize = 12.sp,
                color = Color(0xFFDDDDDD),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                subtitle,
                fontSize = 10.sp,
                color = Color(0xFF888888),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /** 详情视图：歌单详情 / 榜单详情 / 歌手热歌 / 导入结果。返回 + 头部 + 歌曲列表。 */
    @Composable
    private fun SheetDetailView(
        view: SheetView,
        modifier: Modifier = Modifier,
        onBack: () -> Unit,
        onPlay: (Int) -> Unit,
        onDownload: (MusicMedia) -> Unit,
        onFavorite: (MusicMedia) -> Unit,
        onArtist: (MusicMedia) -> Unit,
        onRemoveFromPlaylist: (String, Int) -> Unit,
    ) {
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹ 返回",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(view.title, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(56.dp)) // 平衡左侧返回键宽度
            }
            HorizontalDivider(color = Color(0x22FFFFFF))
            if (view.loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(34.dp))
                }
            } else if (view.error.isNotEmpty() && view.items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(view.error, fontSize = 13.sp, color = Color(0xFFFF8A80), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item(key = "head") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlideImage(
                                url = view.cover.takeIf { it.isNotEmpty() },
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(view.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8E8E8), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (view.subtitle.isNotEmpty()) {
                                    Text(view.subtitle, fontSize = 11.sp, color = Color(0xFF999999), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                }
                                Spacer(Modifier.height(6.dp))
                                Button(onClick = { onPlay(0) }, modifier = Modifier.height(32.dp)) {
                                    Text("播放全部（${view.items.size}）", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    itemsIndexed(view.items) { index, media ->
                        MusicRow(
                            index = index,
                            media = media,
                            onPlay = onPlay,
                            onDownload = { onDownload(media) },
                            onFavorite = { onFavorite(media) },
                            onArtist = onArtist,
                            onRemoveFromPlaylist = if (view.playlistName.isNotEmpty()) {
                                { i -> onRemoveFromPlaylist(view.playlistName, i) }
                            } else null,
                        )
                        HorizontalDivider(color = Color(0x14FFFFFF))
                    }
                }
            }
        }
    }

    /** 加入歌单弹窗：选择已有歌单或新建。 */
    @Composable
    private fun PlaylistPickerDialog(
        playlists: List<MusicLibrary.Playlist>,
        onClose: () -> Unit,
        onPick: (String) -> Unit,
        onNew: () -> Unit,
    ) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text(
                    "加入歌单",
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp),
                )
                // 新建歌单入口
                Button(
                    onClick = onNew,
                    modifier = Modifier.align(Alignment.CenterHorizontally).height(36.dp),
                ) { Text("＋ 新建歌单", fontSize = 13.sp) }
                Spacer(Modifier.height(8.dp))
                if (playlists.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("暂无歌单，先新建一个吧", fontSize = 13.sp, color = Color(0xFF666666))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(playlists) { index, p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(p.name) }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(p.name, fontSize = 14.sp, color = Color(0xFFE0E0E0))
                                Spacer(Modifier.width(10.dp))
                                Text("${p.items.size} 首", fontSize = 11.sp, color = Color(0xFF777777))
                            }
                            HorizontalDivider(color = Color(0x14FFFFFF))
                        }
                    }
                }
                Text(
                    "点击下方关闭",
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }

    /** 歌单新建/重命名输入弹窗。 */
    @Composable
    private fun PlaylistInputDialog(
        title: String,
        initial: String,
        onClose: () -> Unit,
        onOk: (String) -> Unit,
    ) {
        var value by remember { mutableStateOf(initial) }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text(
                    title,
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp),
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                    placeholder = { Text("歌单名称", color = Color(0xFF666666), fontSize = 14.sp) },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { onOk(value) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onClose) { Text("取消", color = Color(0xFF888888)) }
                    Spacer(Modifier.width(24.dp))
                    Button(onClick = { onOk(value) }) { Text("确定") }
                }
                Text(
                    "点击下方关闭",
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }

    @Composable
    private fun ImportDialog(
        onClose: () -> Unit,
        onOk: (String) -> Unit,
        onPickTextFile: () -> Unit,
    ) {
        var url by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text(
                    "导入歌单",
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp),
                )
                Text(
                    "粘贴网易云歌单/榜单链接（如 music.163.com/playlist?id=xxx）或纯数字 id；",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
                Text(
                    "也可以选择「歌名集」文本文件（每行一个歌名，支持「歌名 - 歌手」），批量搜索后统一整理。",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 2.dp),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    placeholder = { Text("歌单链接 / id", color = Color(0xFF666666), fontSize = 14.sp) },
                    maxLines = 2,
                    keyboardActions = KeyboardActions(onDone = { onOk(url) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onClose) { Text("取消", color = Color(0xFF888888)) }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onPickTextFile) { Text("选文本文件", fontSize = 13.sp) }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { onOk(url) }) { Text("导入") }
                }
                Text(
                    "点击下方关闭",
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() }
                        .padding(vertical = 12.dp),
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

    /** 睡眠定时弹窗：关闭 / 10/15/30/60/90 分钟后暂停播放。 */
    @Composable
    private fun SleepDialog(current: Long, onClose: () -> Unit, onSelect: (Long) -> Unit) {
        val options = arrayOf(
            "关闭定时" to 0L,
            "10 分钟后" to 10 * 60_000L,
            "15 分钟后" to 15 * 60_000L,
            "30 分钟后" to 30 * 60_000L,
            "60 分钟后" to 60 * 60_000L,
            "90 分钟后" to 90 * 60_000L,
        )
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .background(SURFACE_COLOR, RoundedCornerShape(12.dp))
                        .padding(vertical = 16.dp)
                        .clickable(enabled = false) {},
                ) {
                    Text(
                        "睡眠定时",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                    )
                    if (current > 0) {
                        Text(
                            "当前：${sleepTimeText(current)}（${timeText(current - System.currentTimeMillis())}后）暂停",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                        )
                    }
                    options.forEach { (label, ms) ->
                        Text(
                            label,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(ms)
                                    onClose()
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    /** Glide 封面桥：Compose 内复用现有 Glide 加载（避免引入 coil 增加体积）。
     *  override 限制解码尺寸（网易封面原图常达数百 px，列表里按显示大小解码即可，
     *  歌单页几十张封面并发时不至于内存/CPU 双爆）；timeout 保证弱网下不无限挂起 IO 线程。 */
    @Composable
    private fun GlideImage(url: String?, modifier: Modifier = Modifier, pixels: Int = 256) {
        val context = LocalContext.current
        var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(url) {
            if (url.isNullOrEmpty()) {
                bitmap = null
                return@LaunchedEffect
            }
            val bmp = withContext(Dispatchers.IO) {
                try {
                    Glide.with(context).asBitmap()
                        .apply(RequestOptions().override(pixels, pixels).timeout(8000))
                        .load(url).submit().get()
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

    /** 文件大小格式化（下载管理页展示）。 */
    private fun fileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1) String.format(Locale.CHINA, "%.1f MB", mb)
        else String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
    }
}