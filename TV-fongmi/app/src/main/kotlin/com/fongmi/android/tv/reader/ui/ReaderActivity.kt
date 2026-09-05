package com.fongmi.android.tv.reader.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.Fragment

import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.fongmi.android.tv.reader.Book
import com.fongmi.android.tv.reader.BookSource
import com.fongmi.android.tv.reader.EpubImporter
import com.fongmi.android.tv.reader.ReaderRepository
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.reader.RssRepository
import com.fongmi.android.tv.ui.activity.HomeActivity
import com.fongmi.android.tv.ui.common.ThemeStore
import com.fongmi.android.tv.utils.Notify
import com.github.catvod.net.OkHttp
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 全文搜索结果：命中书 + 章节 + 章内段落号 + 摘要。 */
data class FulltextHit(
    val bookUrl: String,
    val bookName: String,
    val chapter: Int,
    val chapterName: String,
    val para: Int,
    val snippet: String,
)

/**
 * 阅读（书源）板块 Fragment：搜索 → 书籍详情/目录 → 正文阅读（Compose 段落排版，断点按段落号恢复）。
 * 书源管理：内置 + 粘贴 JSON/URL 导入；搜索并发跑全部启用书源。
 * 内嵌于 HomeActivity 底部导航（视频/音乐/小说/设置），隐藏时视图不销毁，切回即原样。
 */
class ReaderFragment : Fragment() {

    private class UiState {
        var results by mutableStateOf<List<Book>>(emptyList())
        var searching by mutableStateOf(false)
        var keyword by mutableStateOf("")
        // 书源管理
        var sources by mutableStateOf<List<BookSource>>(emptyList())
        /** 板块内书源入口只做切换（启用/停用点选），导入/测试/删除在「设置 → 书源管理」。 */
        var sourceSwitchVisible by mutableStateOf(false)
        // 书籍详情 + 目录
        var book by mutableStateOf<Book?>(null)
        var detailLoading by mutableStateOf(false)
        var detailError by mutableStateOf("")
        // 阅读
        var reading by mutableStateOf(false)
        var chapterIndex by mutableStateOf(0)
        var content by mutableStateOf("")
        var contentLoading by mutableStateOf(false)
        var contentError by mutableStateOf("")
        // 书架（批1：收藏 / 断点续读 / 书签）
        var shelfMode by mutableStateOf(false)
        var shelves by mutableStateOf<List<Book>>(emptyList())
        var bookmarks by mutableStateOf<List<ReaderStore.Bookmark>>(emptyList())
        // 书架分组/排序：分组过滤 + 移入分组 + 三种排序
        var shelfGroups by mutableStateOf<List<String>>(emptyList())
        var shelfGroupFilter by mutableStateOf("全部")
        var shelfSort by mutableStateOf(0) // 0 加入顺序 / 1 最近阅读 / 2 书名
        var moveTarget by mutableStateOf<Book?>(null)
        var newGroupDialogVisible by mutableStateOf(false)
        /** 当前详情书是否在书架（Compose 可观察，收藏/移出即时刷新按钮）。 */
        var inShelf by mutableStateOf(false)
        // 批2：章节缓存 + 本地 TXT 导入 + 阅读设置
        var cacheText by mutableStateOf("")
        var settingsDialogVisible by mutableStateOf(false)
        // 阅读设置（Compose 可观察：改动即重绘正文样式；持久化在 ReaderStore）
        var fontSize by mutableStateOf(17)
        var lineHeight by mutableStateOf(1.9f)
        var theme by mutableStateOf("dark")
        // 本地/远程书籍导入（txt/epub）
        var importDialogVisible by mutableStateOf(false)
        var remoteImportVisible by mutableStateOf(false)
        var remoteImporting by mutableStateOf(false)
        // 全文搜索（在已缓存/本地书籍的章节内定位关键词）
        var fulltextVisible by mutableStateOf(false)
        var fulltextKeyword by mutableStateOf("")
        var fulltextSearching by mutableStateOf(false)
        var fulltextHits by mutableStateOf<List<FulltextHit>>(emptyList())
        // 阅读统计弹窗
        var statsVisible by mutableStateOf(false)
        // 缓存管理页：列出已缓存的书，支持单本/全部清除
        var cacheMode by mutableStateOf(false)
        var cacheBooks by mutableStateOf<List<ReaderStore.CachedBook>>(emptyList())
        // 章内阅读百分比（滚动节流更新，供阅读页顶部进度条展示）
        var readPercent by mutableStateOf(0f)
        // RSS 阅读器：独立页签（源管理 + 文章列表 + 复用阅读页）
        var rssMode by mutableStateOf(false)
        var rssSources by mutableStateOf<List<RssRepository.RssSource>>(emptyList())
        var rssActive by mutableStateOf("")
        var rssArticles by mutableStateOf<List<RssRepository.RssArticle>>(emptyList())
        var rssLoading by mutableStateOf(false)
        var rssError by mutableStateOf("")
        var rssSourceDialogVisible by mutableStateOf(false)
        /** 当前 RSS 源是否已在书架（RssBar 收藏按钮即时刷新）。 */
        var rssInShelf by mutableStateOf(false)
        // TTS 听书：本机 TextToSpeech + 在线 HTTP 朗读 + 语速 + 自动切章
        var ttsVisible by mutableStateOf(false)
        var ttsPlaying by mutableStateOf(false)
        var ttsSpeed by mutableStateOf(1.0f)
        var ttsEngine by mutableStateOf("online")
        var ttsOnlineUrl by mutableStateOf("")
        var ttsStatus by mutableStateOf("")
        // RSS 收藏 / 已读 / 排序
        var rssFavMode by mutableStateOf(false)
        var rssFavs by mutableStateOf<List<RssRepository.RssFav>>(emptyList())
        var rssUnreadOnly by mutableStateOf(false)
        var rssRefreshKey by mutableStateOf(0)
        /** 当前阅读的 RSS 文章是否已收藏（ReaderBar 星标）。 */
        var rssArticleFav by mutableStateOf(false)
        // 订阅源视频播放：RSS 正文里 mp4/m3u8 直链
        var videoPlayUrl by mutableStateOf<String?>(null)
    }

    private val ui = UiState()
    /** TTS 朗读引擎（本机 TextToSpeech / 在线 HTTP），fragment attach 后惰性创建。 */
    private val ttsSpeaker by lazy { TtsSpeaker(requireContext()) }
    /** 切章后等正文就绪继续朗读（自动切章 / 手动上下章）。 */
    private var ttsPendingCont = false
    /** 朗读排队：正文加载完成后自动开始（首次点朗读时正文未就绪）。 */
    private var ttsPendingStart = false
    /** RSS 文章收藏页的伪源 key（rssActive 取此值表示在看收藏）。 */
    private val RSS_FAV = "__favorites__"
    /** 当前章首可见段落号（LazyColumn 滚动实时更新，退出/切章时落盘）。 */
    private var currentParaIndex = 0
    /** 当前章段落总数（正文到达后按 htmlToParagraphs 计算）。 */
    private var currentParaCount = 0
    /** 滚动进度节流落盘时间戳（2s 最多写一次，进程被杀也能续读）。 */
    private var lastAutoSaveAt = 0L
    /** 待恢复的段落号（openChapter 从进度库读到后置值；阅读页首次布局消费后复位 -1）。 */
    private var restorePara = -1
    /** 全局主题订阅：设置在其它板块修改后即时换肤（已归属 ThemeStore，不再依赖板块内设置）。 */
    private val themeListener: (String) -> Unit = { t ->
        mainHandler.post {
            ui.theme = t
            if (ui.reading) openChapter(ui.chapterIndex)
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    /** RSS 定时自动刷新（30 分钟一次，仅 RSS 页可见且未在阅读时执行）。 */
    private val rssRefreshTick = object : Runnable {
        override fun run() {
            if (ui.rssMode && !ui.reading && !ui.rssLoading && ui.rssActive.isNotEmpty()) {
                loadRssArticles(ui.rssActive, background = true)
            }
            mainHandler.postDelayed(this, 30 * 60_000L)
        }
    }
    /** 阅读时长累计（每分钟一次，仅阅读中生效，计入阅读统计）。 */
    private val statsTick = object : Runnable {
        override fun run() {
            val b = ui.book
            if (ui.reading && b != null) {
                ReaderStore.get().tickRead(60_000L, b.url, b.name)
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }
    /** 搜索兜底：底层（书源网络/规则求值）无论怎样，25s 内必须结束搜索态，绝不无限转圈。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val SEARCH_TIMEOUT_MS = 25_000L

    /** 本地书籍文件选择器（SAF，TXT/EPUB 按扩展名识别）。 */
    private val localBookPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocalBook(uri)
    }

    /** OPML 导入（批量添加订阅源）。 */
    private val opmlImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importOpmlFile(uri)
    }

    /** OPML 导出（保存全部订阅源）。 */
    private val opmlExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        if (uri != null) exportOpmlFile(uri)
    }

    companion object {
        private const val TAG = "ReaderFragment"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderRepository.get().init(requireContext())
        ReaderStore.get().init(requireContext())
        RssRepository.get().init(requireContext())
        val rdr = ReaderStore.get()
        ThemeStore.get().init(requireContext())
        ThemeStore.get().addListener(themeListener)
        ui.fontSize = rdr.fontSize
        ui.lineHeight = rdr.lineHeight
        ui.theme = ThemeStore.get().theme
        ui.ttsSpeed = rdr.ttsSpeed
        ui.ttsEngine = rdr.ttsEngine
        ui.ttsOnlineUrl = rdr.ttsOnlineUrl
        // TTS 回调（均回到主线程）：状态按钮 / 段落进度 / 整章读完自动切章
        ttsSpeaker.onState = { playing -> mainHandler.post { ui.ttsPlaying = playing } }
        ttsSpeaker.onProgress = { pi, pc -> mainHandler.post { ui.ttsStatus = "第 ${pi + 1}/${pc} 段" } }
        ttsSpeaker.onDone = { mainHandler.post { ttsChapterDone() } }
        refreshSources()
        refreshShelves()
        refreshRssSources()
        // 板块级定时器：RSS 定时刷新 + 阅读时长统计（fragment 只创建一次，隐藏切回不受影响）
        mainHandler.postDelayed(rssRefreshTick, 30 * 60_000L)
        mainHandler.postDelayed(statsTick, 60_000L)
    }

    /** 切回本板块时刷新书源列表（书源管理在设置 tab 完成，回来要看到最新书源）。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshSources()
    }

    /** 板块视图：Compose 根（fragment 的 onCreateView 一次性渲染，切板块不重建视图）。 */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF4FC3F7),
                background = Color(0xFF141414),
                surface = Color(0xFF1E1E1E),
                onBackground = Color(0xFFE0E0E0),
                onSurface = Color(0xFFE0E0E0),
                secondary = Color(0xFF999999),
            )) {
                ReaderScreen(
                    ui = ui,
                    onSearch = { search(it) },
                    onOpenBook = { openBook(it, false) },
                    onOpenShelfBook = { openBook(it, true) },
                    onBackFromBook = { ui.book = null; ui.reading = false },
                    onOpenChapter = { openChapter(it) },
                    onPrev = { step(-1) },
                    onNext = { step(1) },
                    onBackFromReader = { leaveReader() },
                    onToggleShelf = ::toggleShelf,
                    onOpenShelf = { ui.shelfMode = true },
                    onBackFromShelf = { ui.shelfMode = false },
                    onAddBookmark = ::addBookmark,
                    onOpenBookmark = { ch ->
                        val bm = ui.bookmarks.firstOrNull { it.chapter == ch }
                        openChapter(ch, bm?.para ?: -1)
                    },
                    onRemoveBookmark = ::removeBookmark,
                    onRemoveShelf = { url ->
                        ReaderStore.get().removeFromShelf(url)
                        if (ui.book?.url == url) ui.inShelf = false
                        refreshShelves()
                        Notify.show("已移出书架")
                    },
                    onCycleSort = ::cycleShelfSort,
                    onSelectGroup = { ui.shelfGroupFilter = it },
                    onOpenNewGroup = { ui.newGroupDialogVisible = true },
                    onCreateGroup = ::createShelfGroup,
                    onMoveShelfBook = { url, group ->
                        moveShelfBook(url, group)
                        ui.moveTarget = null
                        Notify.show("已移入分组「$group」")
                    },
                    onOpenSettings = { ui.settingsDialogVisible = true },
                    onCacheBook = ::cacheCurrentBook,
                    onOpenImport = { ui.importDialogVisible = true },
                    onOpenFulltext = { ui.fulltextVisible = true },
                    onOpenCacheHit = ::openFromCacheHit,
                    onOpenStats = { ui.statsVisible = true },
                    onOpenCache = { ui.cacheMode = true; refreshCacheBooks() },
                    onCloseCache = { ui.cacheMode = false },
                    onClearBookCache = { url ->
                        ReaderStore.get().clearCache(url)
                        refreshCacheBooks()
                        Notify.show("已清除该书缓存")
                    },
                    onClearAllCache = {
                        val n = ReaderStore.get().clearAllCache()
                        refreshCacheBooks()
                        Notify.show("已清除全部缓存（$n 章）")
                    },
                    onOpenRss = {
                        ui.rssMode = true
                        refreshRssSources()
                        if (ui.rssActive.isEmpty()) {
                            ui.rssActive = RssRepository.get().sources().firstOrNull()?.url ?: ""
                        }
                        refreshRssShelfState()
                        if (ui.rssActive.isNotEmpty() && ui.rssArticles.isEmpty()) loadRssArticles(ui.rssActive)
                    },
                    onBackFromRss = { ui.rssMode = false },
                    onSelectRssSource = ::loadRssArticles,
                    onOpenRssArticle = ::openRssArticle,
                    onRefreshRss = {
                        if (ui.rssActive.isNotEmpty()) loadRssArticles(ui.rssActive)
                        else Notify.show("请先选择订阅源")
                    },
                    onToggleRssShelf = ::toggleRssShelf,
                    onImportOpml = { opmlImportPicker.launch(arrayOf("*/*")) },
                    onExportOpml = { opmlExportPicker.launch("rss_subscriptions.opml") },
                    onRssImport = { name, url ->
                        if (url.isBlank()) {
                            Notify.show("请输入源名称和地址")
                        } else {
                            RssRepository.get().addSource(name, url)
                            refreshRssSources()
                            Notify.show("已添加订阅源")
                        }
                    },
                    onToggleRssSource = {
                        RssRepository.get().toggleSource(it)
                        refreshRssSources()
                    },
                    onTestRssSource = ::testRssSource,
                    onRemoveRssSource = {
                        RssRepository.get().removeSource(it)
                        refreshRssSources()
                        if (ui.rssActive == it) {
                            ui.rssActive = ""
                            ui.rssArticles = emptyList()
                        }
                        Notify.show("已删除订阅源")
                    },
                    onOpenSources = { ui.sourceSwitchVisible = true }, // 板块内只做切换；管理在设置 tab
                    onToggleSource = { toggleSource(it) },
                )
            }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(rssRefreshTick)
        mainHandler.removeCallbacks(statsTick)
    }

    override fun onDestroy() {
        ThemeStore.get().removeListener(themeListener)
        ttsSpeaker.release()
        super.onDestroy()
    }

    /** 退到后台时立即落盘进度，保证系统杀进程后也能断点续读（切板块不销毁视图，进度由滚动节流保存）。 */
    override fun onStop() {
        super.onStop()
        saveProgress()
    }

    private fun refreshSources() {
        ui.sources = ReaderRepository.get().sources()
    }

    private fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        ui.keyword = kw
        ui.searching = true
        ui.results = emptyList()
        uiHandler.removeCallbacksAndMessages(null)
        // 兜底：无论底层如何，25s 内必须结束搜索态（书源转圈的最终护栏）
        uiHandler.postDelayed({
            if (!ui.searching) return@postDelayed
            ui.searching = false
            Notify.show("搜索超时，请检查书源或网络")
        }, SEARCH_TIMEOUT_MS)
        ReaderRepository.get().search(kw).whenComplete { list, e ->
            mainHandler.post {
                uiHandler.removeCallbacksAndMessages(null)
                ui.searching = false
                if (e != null) {
                    Notify.show("搜索失败：" + friendly(e))
                } else {
                    ui.results = list ?: emptyList()
                    if (ui.results.isEmpty()) Notify.show("未找到相关书籍（请检查书源或网络）")
                }
            }
        }
    }

    private fun openBook(book: Book, autoContinue: Boolean) {
        ui.book = book
        ui.reading = false
        ui.shelfMode = false
        ui.detailLoading = true
        ui.detailError = ""
        ui.inShelf = ReaderStore.get().inShelf(book.url)
        ui.cacheText = ""
        // 本地 TXT 书：正文已按章写入缓存，无需网络详情/目录
        if (book.url.startsWith("local_txt://")) {
            ui.detailLoading = false
            refreshBookmarks()
            // 书架重开时 chapter 列表为空，从缓存重建（章节名来自导入时保存的索引）
            if (book.chapters.isEmpty()) {
                val n = ReaderStore.get().cachedCount(book.url)
                for (i in 0 until n) {
                    val name = ReaderStore.get().chapterName(book.url, i).ifEmpty { "第${i + 1}节" }
                    book.chapters.add(Book.Chapter(name, "local_txt#$i"))
                }
            }
            ui.cacheText = "本地文件 · ${book.chapters.size} 节"
            if (autoContinue) {
                val p = ReaderStore.get().progress(book.url)
                if (p != null && p.chapter in 0 until book.chapters.size) openChapter(p.chapter)
                else if (book.chapters.isNotEmpty()) openChapter(0)
            }
            return
        }
        // RSS 源收藏书：从条目缓存重建目录，直接续读（离线可用）
        if (book.url.startsWith("rss_src://")) {
            ui.detailLoading = false
            refreshBookmarks()
            val sourceUrl = book.url.removePrefix("rss_src://")
            val arts = RssRepository.get().cachedArticles(sourceUrl)
            if (arts.isNotEmpty() && book.chapters.isEmpty()) {
                for (a in arts) book.chapters.add(Book.Chapter(a.title.ifEmpty { "文章" }, a.link.ifEmpty { "rss" }))
            }
            if (autoContinue) {
                val p = ReaderStore.get().progress(book.url)
                val idx = if (p != null && p.chapter in 0 until book.chapters.size) p.chapter else 0
                if (book.chapters.isNotEmpty()) openChapter(idx) else Notify.show("该源暂无缓存文章，先去 RSS 页刷新")
            }
            return
        }
        ReaderRepository.get().detail(book).whenComplete { b, e1 ->
            val bd = b ?: book
            ReaderRepository.get().toc(bd).whenComplete { tb, e2 ->
                mainHandler.post {
                    ui.detailLoading = false
                    ui.book = tb ?: bd
                    refreshBookmarks()
                    val err: Throwable? = (e2 as? Throwable) ?: (e1 as? Throwable)
                    if (err != null) ui.detailError = "详情/目录失败：" + friendly(err)
                    // 书架/断点续读：目录就绪后直接跳上次读到的章节
                    if (autoContinue && ui.reading.not()) {
                        val book0 = ui.book
                        if (book0 != null) {
                            val p = ReaderStore.get().progress(book0.url)
                            if (p != null && p.chapter in 0 until book0.chapters.size) {
                                openChapter(p.chapter)
                            } else if (book0.chapters.isNotEmpty()) {
                                openChapter(0)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openChapter(index: Int, restore: Int = -1) {
        val book = ui.book ?: return
        if (index < 0 || index >= book.chapters.size) return
        if (ui.reading) saveProgress()
        ui.chapterIndex = index
        ui.reading = true
        currentParaIndex = 0
        currentParaCount = 0
        ui.contentLoading = true
        ui.contentError = ""
        ui.content = ""
        refreshRssArticleFav()
        // 断点续读：优先传入的段落号（书签跳转）；否则读进度库中该章的段落号
        if (restore >= 0) {
            restorePara = restore
        } else {
            val p = ReaderStore.get().progress(book.url)
            restorePara = if (p != null && p.chapter == index) p.para else -1
        }
        // 章节缓存优先：命中本地直接显示，不请求网络
        val cached = ReaderStore.get().cachedChapter(book.url, index)
        if (cached != null && cached.isNotEmpty()) {
            ui.contentLoading = false
            ui.content = cached
            maybeContinueTts()
            return
        }
        // RSS 文章：正文来自条目 description（缓存层已覆盖离线续读），不走书源规则
        if (book.source == "rss") {
            val art = rssArticleForChapter(index)
            if (art == null) {
                ui.contentLoading = false
                ui.contentError = "文章不存在"
                return
            }
            RssRepository.get().body(art).whenComplete { html, _ ->
                mainHandler.post {
                    ui.contentLoading = false
                    if (!html.isNullOrEmpty()) {
                        ui.content = html
                        Thread { ReaderStore.get().cacheChapter(book.url, index, html) }.start()
                        maybeContinueTts()
                    } else {
                        ui.contentError = "正文为空（未能获取内容）"
                    }
                }
            }
            return
        }
        ReaderRepository.get().chapter(book.chapters[index].url, book.source).whenComplete { html, e ->
            mainHandler.post {
                ui.contentLoading = false
                if (e != null || html == null) {
                    // 网络失败时也尝试缓存兜底（部分缓存场景）
                    val fallback = ReaderStore.get().cachedChapter(book.url, index)
                    if (fallback != null && fallback.isNotEmpty()) {
                        ui.content = fallback
                        maybeContinueTts()
                    } else {
                        ui.contentError = "正文加载失败：" + friendly(e ?: Exception("空正文"))
                    }
                } else {
                    ui.content = html
                    Thread { ReaderStore.get().cacheChapter(book.url, index, html) }.start()
                    maybeContinueTts()
                }
            }
        }
    }

    /** 当前章对应的 RSS 条目（来源文章列表或收藏页），供正文获取与星标。 */
    private fun rssArticleForChapter(index: Int): RssRepository.RssArticle? {
        val book = ui.book ?: return null
        val ch = book.chapters.getOrNull(index) ?: return null
        val link = ch.url.trim()
        // 收藏模式：book.url 形如 rss_fav://<link>
        if (book.url.startsWith("rss_fav://")) {
            val fav = ui.rssFavs.firstOrNull { it.link.trim() == link } ?: RssRepository.get().favorites().firstOrNull { it.link.trim() == link }
            if (fav != null) return RssRepository.RssArticle(fav.title, fav.link, fav.pubDate, fav.desc)
        }
        if (book.url.startsWith("rss_src://")) {
            ui.rssArticles.firstOrNull { it.link.trim() == link }?.let { return it }
            // 收藏页或离线场景：尝试缓存/收藏里找
            RssRepository.get().favorites().firstOrNull { it.link.trim() == link }?.let { return RssRepository.RssArticle(it.title, it.link, it.pubDate, it.desc) }
        }
        return null
    }

    /** 当前 RSS 文章收藏态刷新（阅读页星标显示）。 */
    private fun refreshRssArticleFav() {
        if (ui.book?.source != "rss") {
            ui.rssArticleFav = false
            return
        }
        val ch = ui.book?.chapters?.getOrNull(ui.chapterIndex)
        ui.rssArticleFav = ch != null && RssRepository.get().isFavorite(ch.url.trim())
    }

    /** 星标/取消星标当前 RSS 文章。 */
    private fun toggleRssArticleFav() {
        val ch = ui.book?.chapters?.getOrNull(ui.chapterIndex) ?: return
        val link = ch.url.trim()
        val base = rssArticleForChapter(ui.chapterIndex)
        val art = base ?: RssRepository.RssArticle(ch.name, link, "", "")
        val srcUrl = if (ui.book?.url?.startsWith("rss_fav://") == true) {
            RssRepository.get().favorites().firstOrNull { it.link == link }?.sourceUrl ?: ""
        } else ui.rssActive
        val srcName = ui.rssSources.firstOrNull { it.url == srcUrl }?.name
            ?: RssRepository.get().favorites().firstOrNull { it.link == link }?.source
            ?: "RSS"
        val fav = RssRepository.get().toggleFavorite(art, srcUrl, srcName)
        ui.rssArticleFav = fav
        refreshRssFavs()
        ui.rssRefreshKey++
        Notify.show(if (fav) "已收藏到收藏页" else "已取消收藏")
    }

    /** 缓存整本书全部章节（后台逐章拉取写入本地），展示进度。 */
    private fun cacheCurrentBook() {
        val book = ui.book ?: return
        if (book.chapters.isEmpty()) {
            Notify.show("暂无章节可缓存")
            return
        }
        Thread {
            val total = book.chapters.size
            var cached = ReaderStore.get().cachedCount(book.url)
            for (i in 0 until total) {
                if (Thread.currentThread().isInterrupted) return@Thread
                if (ReaderStore.get().cachedChapter(book.url, i) != null) continue
                try {
                    val html = ReaderRepository.get().chapter(book.chapters[i].url, book.source).get()
                    if (html != null && html.isNotEmpty() && ReaderStore.get().cacheChapter(book.url, i, html)) {
                        cached++
                        val done = cached
                        val all = total
                        mainHandler.post { ui.cacheText = "缓存 $done/$all" }
                    }
                } catch (ignored: Exception) {
                }
            }
            mainHandler.post {
                ui.cacheText = "已缓存 $cached/$total 章"
                Notify.show("缓存完成：$cached/$total 章")
            }
        }.start()
    }

    /** 本地书籍导入（TXT/EPUB）：按扩展名走不同解析，切章 → 逐章写缓存 → 入书架并打开。 */
    private fun importLocalBook(uri: Uri) {
        Thread {
            try {
                val name = queryName(requireContext().contentResolver, uri) ?: "本地书籍"
                val chapters: List<Pair<String, String>>
                if (name.lowercase().endsWith(".epub")) {
                    val stream = requireContext().contentResolver.openInputStream(uri)
                    if (stream == null) {
                        mainHandler.post { Notify.show("无法打开文件") }
                        return@Thread
                    }
                    val parsed = EpubImporter.parse(stream)
                    stream.close()
                    if (parsed.isEmpty()) {
                        mainHandler.post { Notify.show("EPUB 无有效正文") }
                        return@Thread
                    }
                    chapters = parsed.map { it.title to it.text }
                } else {
                    val text = requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                    if (text.isBlank()) {
                        mainHandler.post { Notify.show("文本内容为空") }
                        return@Thread
                    }
                    chapters = splitChapters(text)
                }
                runImportChapters(name.removeSuffix(".txt").removeSuffix(".epub").ifEmpty { "本地书籍" }, chapters)
            } catch (e: Exception) {
                mainHandler.post { Notify.show("导入失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    /** 远程书籍导入：拉取直链（.epub/.txt），复用本地导入流程。 */
    private fun importRemoteBook(url: String) {
        if (url.isBlank()) return
        ui.remoteImporting = true
        var display = "txt"
        Thread {
            try {
                if (!url.startsWith("http")) {
                    mainHandler.post { Notify.show("无效链接，需以 http(s) 开头") }
                    return@Thread
                }
                val chapters: List<Pair<String, String>>
                val name: String
                val urlName = url.substringAfterLast('/').substringBefore('?').ifEmpty { "远程书籍" }
                if (urlName.lowercase().endsWith(".epub")) {
                    display = "epub"
                    val resp = OkHttp.newCall(url).execute()
                    resp.use { r ->
                        val body = r.body
                        if (body == null) throw IllegalStateException("响应为空")
                        val parsed = EpubImporter.parse(body.byteStream())
                        if (parsed.isEmpty()) throw IllegalStateException("EPUB 无有效正文")
                        chapters = parsed.map { it.title to it.text }
                    }
                    name = urlName.removeSuffix(".epub")
                } else {
                    val text = OkHttp.string(url)
                    if (text.isBlank()) throw IllegalStateException("链接内容为空")
                    chapters = splitChapters(text)
                    name = urlName.removeSuffix(".txt")
                }
                mainHandler.post { ui.remoteImporting = false }
                runImportChapters(name.ifEmpty { "远程书籍" }, chapters)
            } catch (e: Exception) {
                mainHandler.post {
                    ui.remoteImporting = false
                    Notify.show("导入失败（$display）：" + (e.message ?: ""))
                }
            }
        }.start()
    }

    /** 把切好的章节写入缓存并入书架（IO 线程调用；UI 收尾在主线程）。 */
    private fun runImportChapters(name: String, chapters: List<Pair<String, String>>) {
        try {
            val seed = (chapters.firstOrNull()?.second ?: "").take(40)
            val bookUrl = "local_txt://" + abs(name.hashCode() + 31 * seed.hashCode())
            val book = Book(bookUrl, name, "本地文件", "")
            book.source = "local"
            var idx = 0
            val names = ArrayList<String>()
            for ((title, body) in chapters) {
                val html = "<p>" + body.replace("\n", "<br/>") + "</p>"
                ReaderStore.get().cacheChapter(bookUrl, idx, html)
                val t = title.ifEmpty { "第${idx + 1}节" }
                names.add(t)
                book.chapters.add(Book.Chapter(t, "local_txt#$idx"))
                idx++
            }
            ReaderStore.get().saveChapterNames(bookUrl, names)
            ReaderStore.get().addToShelf(book)
            mainHandler.post {
                refreshShelves()
                Notify.show("已导入「${book.name}」${book.chapters.size} 节")
                // 覆盖简要信息后直接进书架详情（不再请求网络）
                openBook(book, false)
                ui.shelfMode = false
            }
        } catch (e: Exception) {
            mainHandler.post { Notify.show("导入失败：" + (e.message ?: "")) }
        }
    }

    /** 文本切章（txtTocRule）：按阅读设置里的正则匹配章标题分割；无标题时整篇作一节。 */
    private fun splitChapters(text: String): List<Pair<String, String>> {
        val pat: Regex = try {
            Regex("^\\s*(" + ReaderStore.get().txtTocRegex + ")")
        } catch (e: Exception) {
            Regex("^\\s*(第[0-9零一二三四五六七八九十百千万]+[章节卷回部篇集])")
        }
        val out = mutableListOf<Pair<String, String>>()
        var curTitle = ""
        val curBody = StringBuilder()
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t.isNotEmpty() && pat.containsMatchIn(t) && curBody.isNotEmpty()) {
                out.add(curTitle to curBody.toString())
                curTitle = t
                curBody.setLength(0)
            } else {
                if (curTitle.isEmpty()) curTitle = "正文"
                if (curBody.isNotEmpty()) curBody.append("\n")
                curBody.append(t)
            }
        }
        if (curTitle.isNotEmpty() || out.isEmpty()) out.add(curTitle to curBody.toString())
        return out
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

    /** 退出阅读页（返回目录/列表）：落盘当前章内进度。 */
    private fun leaveReader() {
        saveProgress()
        if (ui.ttsPlaying || ttsSpeaker.isActive) stopTts()
        ui.reading = false
    }

    /**
     * 首页返回键回调：返回 true 表示本板块内部已消费（阅读→详情→列表→搜索 逐级退回）；
     * 退回板块根状态返回 false，交由 HomeActivity 切回视频板块。
     */
    fun processBack(): Boolean {
        when {
            ui.reading -> { leaveReader(); return true } // 返回详情页或 RSS 文章列表，进度已保存
            ui.book != null && ui.book?.source != "rss" -> { // 详情 → 搜索
                ui.book = null
                ui.shelfMode = false
                ui.cacheMode = false
                return true
            }
            ui.shelfMode || ui.cacheMode -> { // 书架/缓存 → 搜索
                ui.shelfMode = false
                ui.cacheMode = false
                return true
            }
            ui.rssMode -> { ui.rssMode = false; return true } // RSS → 搜索
        }
        return false
    }

    /** 外部分享/打开 txt|epub 文件：HomeActivity 切到本板块后延迟投递进来走本地导入。 */
    fun importExternal(uri: Uri) {
        mainHandler.post { importLocalBook(uri) }
    }

    private fun saveProgress() {
        val book = ui.book ?: return
        if (ui.content.isEmpty()) return
        ReaderStore.get().saveProgressPara(book.url, ui.chapterIndex, currentParaIndex, currentParaCount)
        // 阅读记录：此书记录去重置顶（阅读统计页展示最近在读）
        val cn = book.chapters.getOrNull(ui.chapterIndex)?.name ?: ""
        ReaderStore.get().recordRead(book.url, book.name, cn, ui.readPercent)
    }

    /** 滚动中节流保存进度（2s 一次），防止进程被杀时丢失断点；同时刷新阅读页进度条。 */
    private fun maybeAutoSaveProgress() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSaveAt < 2000L) return
        lastAutoSaveAt = now
        ui.readPercent = if (currentParaCount <= 0) 0f else (currentParaIndex.toFloat() / currentParaCount).coerceIn(0f, 1f)
        mainHandler.post { saveProgress() }
    }

    // ------------------------------------------------------------ TTS 听书（本机 + 在线 HTTP）

    /** 开始朗读当前章：从当前首可见段落读到章尾（自动切章由 onDone 驱动）。 */
    private fun beginTts(fromPara: Int = currentParaIndex) {
        if (ui.book == null) return
        // 正文还在加载（网速慢/详情页）：排队等待，正文就绪后自动开始（maybeContinueTts 消费）
        if (ui.contentLoading || ui.content.isBlank()) {
            ttsPendingStart = true
            ui.ttsVisible = true
            ui.ttsStatus = "正文加载中…就绪后自动开始"
            Notify.show("正文加载中，完成后自动开始朗读")
            return
        }
        val paras = htmlToParagraphs(ui.content)
        if (paras.isEmpty()) {
            Notify.show("本章无可朗读内容")
            return
        }
        ttsPendingStart = false
        ttsPendingCont = false
        ui.ttsVisible = true
        ui.ttsStatus = "准备朗读…"
        ttsSpeaker.start(paras, fromPara.coerceIn(0, paras.size - 1), ui.ttsSpeed, ui.ttsEngine, ui.ttsOnlineUrl.ifBlank { ReaderStore.get().ttsOnlineUrl })
        ui.ttsPlaying = ttsSpeaker.isActive
    }

    /** 阅读页顶栏「朗读」按钮：未开始时启动；朗读中再点则暂停/继续。 */
    fun beginTtsLong() {
        if (ui.ttsPlaying) {
            ttsSpeaker.pause()
            ui.ttsPlaying = false
        } else if (ttsSpeaker.isActive) {
            ttsSpeaker.resume()
            ui.ttsPlaying = true
        } else {
            beginTts()
        }
    }

    private fun stopTts() {
        ttsSpeaker.stop()
        ttsPendingCont = false
        ttsPendingStart = false
        ui.ttsPlaying = false
        ui.ttsStatus = ""
    }

    /** 整章读完：有下章则继续，否则结束。 */
    private fun ttsChapterDone() {
        val book = ui.book ?: run { stopTts(); return }
        if (ui.chapterIndex + 1 >= book.chapters.size) {
            stopTts()
            Notify.show("全书朗读完毕")
            return
        }
        ttsPendingCont = true
        step(1)
    }

    /** 朗读面板上下章：切章后在正文就绪处继续读。 */
    private fun ttsSkipChapter(delta: Int) {
        ttsPendingCont = true
        ttsSpeaker.stop()
        step(delta)
    }

    /** 切章/首次点朗读时正文未就绪：正文一到齐就自动开始（consumes pending flags）。 */
    private fun maybeContinueTts() {
        if (ttsPendingStart) {
            ttsPendingStart = false
            if (ui.ttsVisible || ui.ttsPlaying || ttsSpeaker.isActive) beginTts()
            return
        }
        if (!ttsPendingCont) return
        ttsPendingCont = false
        if (ui.ttsPlaying || ttsSpeaker.isActive) beginTts()
    }

    private fun refreshCacheBooks() {
        ui.cacheBooks = ReaderStore.get().cachedBooks()
    }

    // ------------------------------------------------------------ RSS

    private fun refreshRssSources() {
        ui.rssSources = RssRepository.get().sources()
    }

    /** 拉取订阅源文章列表（失败时回落缓存）；background=true 时不清空旧列表（定时刷新）。 */
    private fun loadRssArticles(url: String, background: Boolean = false) {
        if (url.isEmpty()) return
        if (url == RSS_FAV) { // 收藏页：不拉网络
            refreshRssFavs()
            return
        }
        ui.rssActive = url
        ui.rssLoading = true
        ui.rssError = ""
        if (!background) ui.rssArticles = emptyList()
        refreshRssShelfState()
        RssRepository.get().refresh(url).whenComplete { list, _ ->
            mainHandler.post {
                ui.rssLoading = false
                val items = list ?: emptyList()
                if (items.isNotEmpty()) ui.rssArticles = items
                if (ui.rssArticles.isEmpty()) {
                    ui.rssError = if (url.isBlank()) "请先添加订阅源"
                    else "该源暂无内容（可能网络失败且无缓存）"
                }
            }
        }
    }

    /** 打开某篇文章：构造虚拟「书」（章节=文章列表），进阅读页。 */
    private fun openRssArticle(index: Int) {
        val arts = ui.rssArticles
        if (index !in arts.indices) return
        val source = ui.rssSources.firstOrNull { it.url == ui.rssActive }
        val name = source?.name?.ifEmpty { "RSS" } ?: "RSS"
        val book = Book("rss_src://" + ui.rssActive, name, "RSS 源", "")
        book.source = "rss"
        for (a in arts) {
            book.chapters.add(Book.Chapter(a.title.ifEmpty { "文章" }, a.link.ifEmpty { "rss" }))
        }
        ui.book = book
        ui.reading = false
        ui.shelfMode = false
        ui.detailLoading = false
        ui.inShelf = false
        RssRepository.get().markRead(arts[index].link)
        ui.rssRefreshKey++
        openChapter(index)
    }

    /** 收藏列表（ui.rssActive 切为 RSS_FAV，展示跨源收藏，不拉网络）。 */
    private fun openFavList() {
        ui.rssActive = RSS_FAV
        ui.rssMode = true
        ui.rssLoading = false
        ui.rssError = ""
        refreshRssFavs()
    }

    private fun refreshRssFavs() {
        ui.rssFavs = RssRepository.get().favorites()
    }

    /** 打开收藏文章：单章虚拟书，正文来自收藏里存的 desc。 */
    private fun openRssFav(index: Int) {
        val favs = ui.rssFavs
        if (index !in favs.indices) return
        val f = favs[index]
        val book = Book("rss_fav://" + f.link.trim(), f.title.ifEmpty { "收藏文章" },
            if (f.source.isNotEmpty()) "收藏 · " + f.source else "RSS 收藏", "")
        book.source = "rss"
        book.chapters.add(Book.Chapter(f.title.ifEmpty { "文章" }, f.link.ifEmpty { "rss" }))
        ui.book = book
        ui.reading = false
        ui.shelfMode = false
        ui.detailLoading = false
        ui.inShelf = false
        RssRepository.get().markRead(f.link)
        ui.rssRefreshKey++
        openChapter(0)
    }

    /** 收藏页移除单篇。 */
    private fun removeRssFav(link: String) {
        RssRepository.get().removeFavorite(link.trim())
        refreshRssFavs()
        ui.rssRefreshKey++
        Notify.show("已取消收藏")
    }

    /** 当前 RSS 源是否已在书架（RssBar 收藏按钮）。 */
    private fun refreshRssShelfState() {
        ui.rssInShelf = ui.rssActive.isNotEmpty() && ReaderStore.get().inShelf("rss_src://" + ui.rssActive)
    }

    /** 收藏/移出当前 RSS 源（整个源作为一本书进书架，点开直接续读文章）。 */
    private fun toggleRssShelf() {
        val url = ui.rssActive
        if (url.isEmpty()) return
        val source = ui.rssSources.firstOrNull { it.url == url }
        val name = source?.name?.ifEmpty { "RSS" } ?: "RSS"
        val bookUrl = "rss_src://" + url
        if (ui.rssInShelf) {
            ReaderStore.get().removeFromShelf(bookUrl)
            Notify.show("已从书架移出")
        } else {
            val b = Book(bookUrl, name, "RSS 源", "")
            b.source = "rss"
            for (a in ui.rssArticles) b.chapters.add(Book.Chapter(a.title.ifEmpty { "文章" }, a.link.ifEmpty { "rss" }))
            ReaderStore.get().addToShelf(b)
            Notify.show("已收藏到书架（${b.chapters.size} 篇文章）")
        }
        ui.rssInShelf = !ui.rssInShelf
        refreshShelves()
    }

    /** 导入 OPML：读取文本 → 批量添加订阅源。 */
    private fun importOpmlFile(uri: Uri) {
        Thread {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                val n = RssRepository.get().importOpml(text)
                mainHandler.post {
                    refreshRssSources()
                    Notify.show(if (n > 0) "OPML 导入完成：新增 $n 个订阅源" else "OPML 内未找到订阅源")
                }
            } catch (e: Exception) {
                mainHandler.post { Notify.show("OPML 导入失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    /** 导出 OPML：全部订阅源写入用户选择的文件。 */
    private fun exportOpmlFile(uri: Uri) {
        Thread {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter(StandardCharsets.UTF_8)?.use {
                    it.write(RssRepository.get().exportOpml())
                }
                mainHandler.post { Notify.show("已导出订阅源 OPML") }
            } catch (e: Exception) {
                mainHandler.post { Notify.show("导出失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    /** 阅读设置变更：主题写全局 ThemeStore，字号/行距写 ReaderStore（均持久化），并以新样式重载当前章。 */
    private fun applySettings() {
        val s = ReaderStore.get()
        s.fontSize = ui.fontSize
        s.lineHeight = ui.lineHeight
        s.saveSettings()
        ThemeStore.get().theme = ui.theme
        if (ui.reading) openChapter(ui.chapterIndex)
    }

    private fun refreshShelves() {
        var list = ReaderStore.get().shelf()
        when (ui.shelfSort) {
            1 -> list = list.sortedByDescending { ReaderStore.get().progress(it.url)?.lastRead ?: 0L }
            2 -> list = list.sortedBy { it.name }
        }
        ui.shelves = list
        ui.shelfGroups = ReaderStore.get().groupNames()
        if (ui.shelfGroupFilter != "全部") {
            // 分组被删除后回退到全部
            if (ui.shelfGroupFilter != "默认" && ui.shelfGroups.none { it == ui.shelfGroupFilter }) {
                ui.shelfGroupFilter = "全部"
            }
        }
    }

    /** 书架排序循环：加入顺序 → 最近阅读 → 书名。 */
    private fun cycleShelfSort() {
        ui.shelfSort = (ui.shelfSort + 1) % 3
        refreshShelves()
    }

    /** 新建分组（空名忽略）。 */
    private fun createShelfGroup(name: String) {
        if (name.trim().isEmpty()) return
        ui.shelfGroups = if (ui.shelfGroups.contains(name.trim())) ui.shelfGroups else ui.shelfGroups + name.trim()
    }

    /** 把书移入分组。 */
    private fun moveShelfBook(url: String, group: String) {
        ReaderStore.get().moveToGroup(url, group)
        refreshShelves()
    }

    private fun refreshBookmarks() {
        ui.bookmarks = ui.book?.let { ReaderStore.get().bookmarks(it.url) } ?: emptyList()
    }

    /** 收藏/移出书架（未收藏则进架，已收藏则移出）。 */
    private fun toggleShelf() {
        val book0 = ui.book ?: return
        if (ui.inShelf) {
            ReaderStore.get().removeFromShelf(book0.url)
            Notify.show("已移出书架")
        } else {
            ReaderStore.get().addToShelf(book0)
            Notify.show("已加入书架")
        }
        ui.inShelf = !ui.inShelf
        refreshShelves()
    }

    /** 阅读页快捷加书签：记录当前章节 + 章内段落号。 */
    private fun addBookmark() {
        val book = ui.book ?: return
        val chapterName = book.chapters.getOrNull(ui.chapterIndex)?.name ?: ""
        ReaderStore.get().addBookmarkPara(
            book.url, ui.chapterIndex, chapterName, currentParaIndex, currentParaCount
        )
        refreshBookmarks()
        Notify.show("已添加书签：${chapterName.ifEmpty { "第${ui.chapterIndex + 1}章" }}")
    }

    /** 删除某书签（按章节号）。 */
    private fun removeBookmark(chapter: Int) {
        val book = ui.book ?: return
        ReaderStore.get().removeBookmark(book.url, chapter)
        refreshBookmarks()
    }

    private fun step(delta: Int) {
        val book = ui.book ?: return
        val next = ui.chapterIndex + delta
        if (next < 0 || next >= book.chapters.size) {
            Notify.show(if (delta > 0) "已经是最后一章" else "已经是第一章")
            return
        }
        openChapter(next)
    }

    private fun toggleSource(url: String) {
        ReaderRepository.get().toggleSource(url)
        refreshSources()
    }

    /** RSS 订阅源连通性测试：拉一次文章列表，能解析出条目即视为可用。 */
    private fun testRssSource(url: String) {
        RssRepository.get().refresh(url).whenComplete { list, _ ->
            mainHandler.post {
                val n = list?.size ?: 0
                Notify.show(if (n > 0) "测试通过：拉取到 $n 篇文章" else "测试失败：无文章或源不可达")
            }
        }
    }

    /** 全文搜索：遍历书架所有“已缓存/本地”书的章节正文，逐段匹配关键词（后台线程，结果增量上屏）。 */
    private fun startFulltextSearch(kw: String) {
        val keyword = kw.trim()
        if (keyword.isEmpty()) return
        ui.fulltextKeyword = keyword
        ui.fulltextSearching = true
        ui.fulltextHits = emptyList()
        val cap = 200
        Thread {
            val found = ArrayList<FulltextHit>()
            val books = ReaderStore.get().shelf()
            for (b in books) {
                val count = ReaderStore.get().cachedCount(b.url)
                if (count == 0) continue
                for (i in 0 until count) {
                    val html = ReaderStore.get().cachedChapter(b.url, i) ?: continue
                    val paras = htmlToParagraphs(html)
                    for ((pi, p) in paras.withIndex()) {
                        if (p.contains(keyword)) {
                            if (found.size >= cap) break
                            val cname = ReaderStore.get().chapterName(b.url, i).ifEmpty { "第${i + 1}节" }
                            found.add(FulltextHit(b.url, b.name, i, cname, pi, snippet(p, keyword)))
                        }
                    }
                }
                // 每本书扫完推送一次进度（增量上屏）
                mainHandler.post { ui.fulltextHits = found.toList() }
                if (found.size >= cap) break
            }
            mainHandler.post {
                ui.fulltextSearching = false
                if (found.isEmpty()) Notify.show("未在已缓存/本地书籍中找到「$keyword」（远程书需先下载章节）")
            }
        }.start()
    }

    /** 命中段落摘要：关键词前后各取一段，两端补省略号。 */
    private fun snippet(p: String, kw: String): String {
        val i = p.indexOf(kw)
        if (i < 0) return p.take(60)
        val from = (i - 20).coerceAtLeast(0)
        val to = (i + kw.length + 25).coerceAtMost(p.length)
        return (if (from > 0) "…" else "") + p.substring(from, to) + (if (to < p.length) "…" else "")
    }

    /** 从全文搜索命中直接进入阅读（走缓存，不请求网络）。 */
    private fun openFromCacheHit(hit: FulltextHit) {
        val book = Book(hit.bookUrl, hit.bookName, "本地文件", "")
        book.source = "local"
        val n = ReaderStore.get().cachedCount(hit.bookUrl)
        for (i in 0 until n) {
            val name = ReaderStore.get().chapterName(hit.bookUrl, i).ifEmpty { "第${i + 1}节" }
            book.chapters.add(Book.Chapter(name, "local_txt#$i"))
        }
        if (hit.chapter !in 0 until n) {
            Notify.show("该章节缓存已失效，请先重新打开书籍")
            return
        }
        ui.book = book
        ui.shelfMode = false
        ui.fulltextVisible = false
        ui.importDialogVisible = false
        openChapter(hit.chapter, hit.para)
    }

    private fun friendly(t: Throwable): String {
        val cause = t.cause ?: t
        val msg = cause.message ?: cause.toString()
        return if (msg.length > 100) msg.substring(0, 100) + "…" else msg
    }

    // ------------------------------------------------------------ Compose

    @Composable
    private fun ReaderScreen(
        ui: UiState,
        onSearch: (String) -> Unit,
        onOpenBook: (Book) -> Unit,
        onOpenShelfBook: (Book) -> Unit,
        onBackFromBook: () -> Unit,
        onOpenChapter: (Int) -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onBackFromReader: () -> Unit,
        onToggleShelf: () -> Unit,
        onOpenShelf: () -> Unit,
        onBackFromShelf: () -> Unit,
        onAddBookmark: () -> Unit,
        onOpenBookmark: (Int) -> Unit,
        onRemoveBookmark: (Int) -> Unit,
        onRemoveShelf: (String) -> Unit,
        onCycleSort: () -> Unit,
        onSelectGroup: (String) -> Unit,
        onOpenNewGroup: () -> Unit,
        onCreateGroup: (String) -> Unit,
        onMoveShelfBook: (String, String) -> Unit,
        onOpenSettings: () -> Unit,
        onCacheBook: () -> Unit,
        onOpenImport: () -> Unit,
        onOpenFulltext: () -> Unit,
        onOpenCacheHit: (FulltextHit) -> Unit,
        onOpenStats: () -> Unit,
        onOpenCache: () -> Unit,
        onCloseCache: () -> Unit,
        onClearBookCache: (String) -> Unit,
        onClearAllCache: () -> Unit,
        onOpenRss: () -> Unit,
        onBackFromRss: () -> Unit,
        onSelectRssSource: (String) -> Unit,
        onOpenRssArticle: (Int) -> Unit,
        onRssImport: (String, String) -> Unit,
        onToggleRssSource: (String) -> Unit,
        onRemoveRssSource: (String) -> Unit,
        onTestRssSource: (String) -> Unit,
        onRefreshRss: () -> Unit,
        onToggleRssShelf: () -> Unit,
        onImportOpml: () -> Unit,
        onExportOpml: () -> Unit,
        onOpenSources: () -> Unit,
        onToggleSource: (String) -> Unit,
    ) {
        var keyword by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                // 顶栏：阅读 / 详情 / 书架 / 搜索
                val currentBook = ui.book
                if (ui.reading) {
                    ReaderBar(
                        chapterName = ui.book?.chapters?.getOrNull(ui.chapterIndex)?.name ?: "阅读",
                        onBack = onBackFromReader,
                        onPrev = onPrev,
                        onNext = onNext,
                        onAddBookmark = onAddBookmark,
                        onOpenSettings = onOpenSettings,
                        ttsPlaying = ui.ttsPlaying,
                        onToggleTts = { beginTtsLong() },
                        favVisible = ui.book?.source == "rss",
                        favOn = ui.rssArticleFav,
                        onToggleFav = ::toggleRssArticleFav,
                    )
                } else if (ui.rssMode) {
                    if (ui.rssActive == RSS_FAV) {
                        FavoritesBar(
                            count = ui.rssFavs.size,
                            onBack = onBackFromRss,
                            onClear = {
                                val n = RssRepository.get().clearFavorites()
                                refreshRssFavs()
                                ui.rssRefreshKey++
                                Notify.show(if (n > 0) "已清空收藏（$n 篇）" else "收藏里还没有内容")
                            },
                        )
                    } else {
                        RssBar(
                            title = ui.rssSources.firstOrNull { it.url == ui.rssActive }?.name ?: "RSS 订阅",
                            inShelf = ui.rssInShelf,
                            onBack = onBackFromRss,
                            onManage = { ui.rssSourceDialogVisible = true },
                            onToggleShelf = onToggleRssShelf,
                            onRefresh = onRefreshRss,
                        )
                    }
                } else if (ui.cacheMode) {
                    CacheBar(onBack = onCloseCache, onClearAll = onClearAllCache)
                } else if (currentBook != null) {
                    BookBar(book = currentBook, onBack = onBackFromBook)
                } else if (ui.shelfMode) {
                    ShelfBar(
                        count = ui.shelves.size,
                        sortLabel = shelfSortLabel(ui.shelfSort),
                        groupFilter = ui.shelfGroupFilter,
                        onBack = onBackFromShelf,
                        onOpenCache = onOpenCache,
                        onOpenSources = onOpenSources,
                        onCycleSort = onCycleSort,
                        onOpenNewGroup = onOpenNewGroup,
                        onOpenFulltext = onOpenFulltext,
                        onOpenStats = onOpenStats,
                    )
                } else {
                    SearchTop(
                        keyword = keyword,
                        onKeyword = { keyword = it },
                        onSearch = { onSearch(keyword) },
                        sources = ui.sources,
                        onOpenSources = onOpenSources,
                        shelfCount = ui.shelves.size,
                        onOpenShelf = onOpenShelf,
                        onOpenRss = onOpenRss,
                    )
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                if (ui.reading) {
                    ReaderBody(
                        loading = ui.contentLoading,
                        content = ui.content,
                        error = ui.contentError,
                    )
                } else if (ui.rssMode) {
                    RssBody(
                        sources = ui.rssSources,
                        active = ui.rssActive,
                        articles = ui.rssArticles,
                        favs = ui.rssFavs,
                        loading = ui.rssLoading,
                        error = ui.rssError,
                        unreadOnly = ui.rssUnreadOnly,
                        refreshKey = ui.rssRefreshKey,
                        onSelectSource = onSelectRssSource,
                        onOpenArticle = onOpenRssArticle,
                        onOpenFav = ::openRssFav,
                        onRemoveFav = ::removeRssFav,
                        onToggleUnread = { ui.rssUnreadOnly = !ui.rssUnreadOnly },
                    )
                } else if (ui.cacheMode) {
                    CacheBody(
                        books = ui.cacheBooks,
                        onOpen = onOpenShelfBook,
                        onClear = onClearBookCache,
                    )
                } else if (currentBook != null) {
                    BookBody(
                        book = currentBook,
                        loading = ui.detailLoading,
                        error = ui.detailError,
                        inShelf = ui.inShelf,
                        bookmarks = ui.bookmarks,
                        cacheText = ui.cacheText,
                        onOpenChapter = onOpenChapter,
                        onToggleShelf = onToggleShelf,
                        onOpenBookmark = onOpenBookmark,
                        onRemoveBookmark = onRemoveBookmark,
                        onCacheBook = onCacheBook,
                    )
                } else if (ui.shelfMode) {
                    ShelfBody(
                        shelves = ui.shelves,
                        groupFilter = ui.shelfGroupFilter,
                        groups = ui.shelfGroups,
                        onSelectGroup = onSelectGroup,
                        onOpenNewGroup = onOpenNewGroup,
                        onMove = { book -> ui.moveTarget = book },
                        onOpenBook = onOpenShelfBook,
                        onRemove = onRemoveShelf,
                        onOpenImport = onOpenImport,
                    )
                } else {
                    SearchBody(
                        searching = ui.searching,
                        results = ui.results,
                        onOpenBook = onOpenBook,
                    )
                }
            }
        }
        if (ui.sourceSwitchVisible) {
            SourceSwitchDialog(
                sources = ui.sources,
                onClose = { ui.sourceSwitchVisible = false },
                onToggle = onToggleSource,
                onGoImport = {
                    ui.sourceSwitchVisible = false
                    (requireActivity() as? HomeActivity)?.openReadSourceManage()
                },
            )
        }
        if (ui.importDialogVisible) {
            ImportDialog(
                onPickLocal = { localBookPicker.launch(arrayOf("*/*")) },
                onRemote = {
                    ui.importDialogVisible = false
                    ui.remoteImportVisible = true
                },
                onClose = { ui.importDialogVisible = false },
            )
        }
        if (ui.remoteImportVisible) {
            RemoteImportDialog(
                importing = ui.remoteImporting,
                onImport = { importRemoteBook(it) },
                onClose = { ui.remoteImportVisible = false },
            )
        }
        if (ui.fulltextVisible) {
            FulltextDialog(
                searching = ui.fulltextSearching,
                keyword = ui.fulltextKeyword,
                hits = ui.fulltextHits,
                onKeyword = { ui.fulltextKeyword = it },
                onSearch = { startFulltextSearch(ui.fulltextKeyword) },
                onOpen = onOpenCacheHit,
                onClose = { ui.fulltextVisible = false },
            )
        }
        if (ui.statsVisible) {
            StatsDialog(
                onClose = { ui.statsVisible = false },
                onOpenRecord = { url, name ->
                    ui.statsVisible = false
                    openBook(Book(url, name, "本地文件", ""), true)
                },
            )
        }
        if (ui.settingsDialogVisible) {
            SettingsDialog(
                fontSize = ui.fontSize,
                lineHeight = ui.lineHeight,
                theme = ui.theme,
                onFontSize = { ui.fontSize = it; applySettings() },
                onLineHeight = { ui.lineHeight = it; applySettings() },
                onTheme = { ui.theme = it; applySettings() },
                onClose = { ui.settingsDialogVisible = false },
            )
        }
        if (ui.rssSourceDialogVisible) {
            RssSourceDialog(
                sources = ui.rssSources,
                onClose = { ui.rssSourceDialogVisible = false },
                onAdd = onRssImport,
                onToggle = onToggleRssSource,
                onRemove = onRemoveRssSource,
                onTest = onTestRssSource,
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
            )
        }
        if (ui.ttsVisible) {
            TtsPanel(
                playing = ui.ttsPlaying,
                speed = ui.ttsSpeed,
                engine = ui.ttsEngine,
                onlineUrl = ui.ttsOnlineUrl,
                status = ui.ttsStatus,
                chapterName = ui.book?.chapters?.getOrNull(ui.chapterIndex)?.name ?: "",
                onTogglePlay = { beginTtsLong() },
                onPrevChapter = { ttsSkipChapter(-1) },
                onNextChapter = { ttsSkipChapter(1) },
                onSpeed = { v ->
                    ui.ttsSpeed = v
                    ReaderStore.get().ttsSpeed = v
                    ReaderStore.get().saveSettings()
                    ttsSpeaker.changeSpeed(v)
                },
                onEngine = { eng ->
                    ui.ttsEngine = eng
                    ReaderStore.get().ttsEngine = eng
                    ReaderStore.get().saveSettings()
                    Notify.show(if (eng == "local") "已切换本机朗读（需设备装有 TTS 引擎）" else "已切换在线朗读")
                },
                onOnlineUrl = { v ->
                    ui.ttsOnlineUrl = v
                    ReaderStore.get().ttsOnlineUrl = v
                    ReaderStore.get().saveSettings()
                },
                onClose = {
                    ui.ttsVisible = false
                    if (ui.ttsPlaying || ttsSpeaker.isActive) stopTts()
                },
            )
        }
        val vurl = ui.videoPlayUrl
        if (vurl != null) {
            VideoDialog(url = vurl, onClose = { ui.videoPlayUrl = null })
        }
        val mt = ui.moveTarget
        if (mt != null) {
            MoveGroupDialog(
                book = mt,
                groups = ui.shelfGroups,
                onPick = { g -> onMoveShelfBook(mt.url, g) },
                onClose = { ui.moveTarget = null },
            )
        }
        if (ui.newGroupDialogVisible) {
            NewGroupDialog(
                onClose = { ui.newGroupDialogVisible = false },
                onCreate = { name ->
                    onCreateGroup(name)
                    ui.newGroupDialogVisible = false
                    if (name.isNotBlank()) ui.shelfGroupFilter = name.trim()
                },
            )
        }
    }

    @Composable
    private fun SearchTop(
        keyword: String,
        onKeyword: (String) -> Unit,
        onSearch: () -> Unit,
        sources: List<BookSource>,
        onOpenSources: () -> Unit,
        shelfCount: Int,
        onOpenShelf: () -> Unit,
        onOpenRss: () -> Unit,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "书源 ${enabledCount(sources)} 个 ▾",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenSources).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "书架($shelfCount)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenShelf).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "RSS",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenRss).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeyword,
                modifier = Modifier.weight(1f),
                placeholder = { Text("书名 / 作者", color = Color(0xFF666666), fontSize = 14.sp) },
                maxLines = 1,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSearch) { Text("搜索", fontSize = 14.sp) }
        }
    }

    private fun enabledCount(sources: List<BookSource>): Int {
        var n = 0
        for (s in sources) if (s.enabled) n++
        return n
    }

    @Composable
    private fun SearchBody(searching: Boolean, results: List<Book>, onOpenBook: (Book) -> Unit) {
        if (searching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入书名搜索", fontSize = 13.sp, color = Color(0xFF666666))
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(results) { _, book ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenBook(book) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReaderCover(book.cover.takeIf { it.isNotEmpty() }, book.name, Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(book.name, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
                            Text(
                                listOfNotNull(book.author.takeIf { it.isNotEmpty() }, sourceName(book.source))
                                    .joinToString(" · "),
                                fontSize = 12.sp,
                                color = Color(0xFF888888),
                                maxLines = 1,
                            )
                        }
                        Text("›", fontSize = 18.sp, color = Color(0xFF555555))
                    }
                    HorizontalDivider(color = Color(0x14FFFFFF))
                }
            }
        }
    }

    private fun sourceName(url: String): String {
        for (s in ui.sources) if (s.url == url) return s.name
        return url
    }

    /** 书架顶栏：返回搜索 + 标题；右侧排序循环、新建分组、缓存管理与书源入口。 */
    @Composable
    private fun ShelfBar(
        count: Int,
        sortLabel: String,
        groupFilter: String,
        onBack: () -> Unit,
        onOpenCache: () -> Unit,
        onOpenSources: () -> Unit,
        onCycleSort: () -> Unit,
        onOpenNewGroup: () -> Unit,
        onOpenFulltext: () -> Unit,
        onOpenStats: () -> Unit,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 搜索", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("书架 ($count)", fontSize = 15.sp, color = Color(0xFFE0E0E0))
            if (groupFilter != "全部") {
                Text(" · $groupFilter", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
            Text("排序:$sortLabel", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onCycleSort).padding(horizontal = 4.dp, vertical = 6.dp))
            Text("分组", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenNewGroup).padding(horizontal = 4.dp, vertical = 6.dp))
            Text("全文", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenFulltext).padding(horizontal = 4.dp, vertical = 6.dp))
            Text("统计", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenStats).padding(horizontal = 4.dp, vertical = 6.dp))
            Text("缓存", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenCache).padding(horizontal = 4.dp, vertical = 6.dp))
            Text("书源", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenSources).padding(horizontal = 4.dp, vertical = 6.dp))
        }
    }

    private fun shelfSortLabel(mode: Int): String = when (mode) {
        1 -> "最近阅读"
        2 -> "书名"
        else -> "加入顺序"
    }

    /** 书架：分组 chips + 封面/书名/进度行（行尾可移入分组、移出）；顶部固定导入 TXT。 */
    @Composable
    private fun ShelfBody(
        shelves: List<Book>,
        groupFilter: String,
        groups: List<String>,
        onSelectGroup: (String) -> Unit,
        onOpenNewGroup: () -> Unit,
        onMove: (Book) -> Unit,
        onOpenBook: (Book) -> Unit,
        onRemove: (String) -> Unit,
        onOpenImport: () -> Unit,
    ) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "import") {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenImport() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF245060)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("导入", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导入本地 / 远程书籍", fontSize = 14.sp, color = Color(0xFFE0E0E0))
                        Text("支持 TXT（自动切章）、EPUB，或粘贴书籍文件直链", fontSize = 11.sp,
                            color = Color(0xFF888888), maxLines = 2)
                    }
                    Text("导入", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = Color(0x16FFFFFF))
            }
            item(key = "groups") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupChip("全部", groupFilter == "全部") { onSelectGroup("全部") }
                    for (g in groups) {
                        Spacer(Modifier.width(6.dp))
                        GroupChip(g, groupFilter == g) { onSelectGroup(g) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("＋", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onOpenNewGroup)
                            .clip(RoundedCornerShape(14.dp)).background(Color(0x26FFFFFF))
                            .padding(horizontal = 12.dp, vertical = 3.dp))
                }
            }
            // 继续阅读（未读完的书横滑卡片，按最近阅读排前）
            val continueBooks = shelves
                .map { b -> b to ReaderStore.get().progress(b.url) }
                .filter { (_, p) -> p != null && p.percent < 0.99f }
                .sortedByDescending { (_, p) -> p!!.lastRead }
            if (continueBooks.isNotEmpty()) {
                item(key = "continue") {
                    Text("继续阅读", fontSize = 13.sp, color = Color(0xFF999999),
                        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp))
                    LazyRow(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                        itemsIndexed(continueBooks) { _, (book, p) ->
                            Column(
                                Modifier.width(112.dp).padding(6.dp).clickable { onOpenBook(book) },
                            ) {
                                ReaderCover(
                                    url = book.cover,
                                    name = book.name,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(book.name, fontSize = 12.sp, color = Color(0xFFDDDDDD),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val pct = (p.percent * 100).toInt().coerceIn(1, 99)
                                Text("读到第${p.chapter + 1}章 · $pct%", fontSize = 10.sp, color = Color(0xFF888888),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(top = 6.dp))
                }
            }
            val shown = shelves.filter {
                if (groupFilter == "全部") true
                else (it.group.ifEmpty { "默认" } == groupFilter)
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
                        Text("该分组暂无书籍", fontSize = 13.sp, color = Color(0xFF666666))
                    }
                }
                return@LazyColumn
            }
            itemsIndexed(shown) { _, book ->
                val p = ReaderStore.get().progress(book.url)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenBook(book) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderCover(
                        url = book.cover,
                        name = book.name,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.name, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
                        val sub = listOfNotNull(
                            book.author.takeIf { it.isNotEmpty() },
                            if (p != null) "读到第${p.chapter + 1}章 ${(p.percent * 100).toInt()}%" else "未开始阅读",
                        ).joinToString(" · ")
                        Text(sub, fontSize = 12.sp, color = Color(0xFF888888), maxLines = 1)
                    }
                    Text("⋯", fontSize = 16.sp, color = Color(0xFFAAAAAA),
                        modifier = Modifier.clickable { onMove(book) }.padding(horizontal = 8.dp, vertical = 6.dp))
                    Text("移出", fontSize = 12.sp, color = Color(0xFFFF8A80),
                        modifier = Modifier.clickable { onRemove(book.url) }.padding(horizontal = 6.dp, vertical = 6.dp))
                }
                HorizontalDivider(color = Color(0x14FFFFFF))
            }
        }
    }

    @Composable
    private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) Color(0xFF141414) else Color(0xFFCCCCCC),
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (selected) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }

    /** 移入分组弹窗：列出全部可选分组（含默认），点击即移入。 */
    @Composable
    private fun MoveGroupDialog(book: Book, groups: List<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
        var newName by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("「${book.name}」移入分组", fontSize = 16.sp, color = Color.White,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    val all = listOf("默认") + groups.filter { it != "默认" }
                    itemsIndexed(all) { _, g ->
                        Text(g, fontSize = 14.sp, color = Color(0xFFDDDDDD),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(g) }
                                .padding(horizontal = 28.dp, vertical = 12.dp))
                        HorizontalDivider(color = Color(0x14FFFFFF))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("或输入新分组名", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (newName.isNotBlank()) onPick(newName.trim())
                    }) { Text("移入", fontSize = 13.sp) }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 新建分组弹窗。 */
    @Composable
    private fun NewGroupDialog(onClose: () -> Unit, onCreate: (String) -> Unit) {
        var name by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("新建分组", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 10.dp))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                    placeholder = { Text("分组名称", color = Color(0xFF666666), fontSize = 14.sp) },
                    maxLines = 1,
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { onCreate(name) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(10.dp))
                Text("新建后可在书架行尾「⋯」把书移入该分组", fontSize = 11.sp, color = Color(0xFF888888),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onClose) { Text("取消", fontSize = 13.sp, color = Color(0xFF888888)) }
                    Spacer(Modifier.width(24.dp))
                    Button(onClick = { onCreate(name) }) { Text("确定", fontSize = 13.sp) }
                }
            }
        }
    }

    /** 详情页顶栏：返回 + 书名（书架操作统一在详情页按钮区，避免重复入口）。 */
    @Composable
    private fun BookBar(book: Book, onBack: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(book.name, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
        }
    }

    @Composable
    private fun BookBody(
        book: Book,
        loading: Boolean,
        error: String,
        inShelf: Boolean,
        bookmarks: List<ReaderStore.Bookmark>,
        cacheText: String,
        onOpenChapter: (Int) -> Unit,
        onToggleShelf: () -> Unit,
        onOpenBookmark: (Int) -> Unit,
        onRemoveBookmark: (Int) -> Unit,
        onCacheBook: () -> Unit,
    ) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
            return
        }
        if (error.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, fontSize = 13.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal = 24.dp))
            }
            return
        }
        val progress = ReaderStore.get().progress(book.url)
        val startChapter = if (progress != null && progress.chapter in 0 until book.chapters.size) progress.chapter else 0
        // 详情页：头部区块占满两列，章节目录用 2 列宫格（对齐国内小说 App 排版）
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    // 封面 + 标题/作者/简介
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        ReaderCover(
                            url = book.cover,
                            name = book.name,
                            modifier = Modifier.size(92.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(book.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5F5F5), maxLines = 2)
                            Text(
                                listOfNotNull(book.author.takeIf { it.isNotEmpty() }, sourceName(book.source))
                                    .joinToString(" · "),
                                fontSize = 12.sp,
                                color = Color(0xFF999999),
                                modifier = Modifier.padding(top = 3.dp),
                                maxLines = 1,
                            )
                            if (book.intro.isNotEmpty()) {
                                Text(book.intro, fontSize = 12.sp, color = Color(0xFFAAAAAA), lineHeight = 17.sp,
                                    modifier = Modifier.padding(top = 6.dp), maxLines = 3,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    // 操作按钮组：开始阅读 / 加入书架（唯一书架入口）/ 缓存
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onOpenChapter(startChapter) }, modifier = Modifier.height(38.dp)) {
                            Text(if (progress != null) "继续阅读" else "开始阅读", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = onToggleShelf,
                            modifier = Modifier.height(38.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (inShelf) Color(0xFF3A2F1A) else Color(0xFF1E3A46),
                            ),
                        ) {
                            Text(if (inShelf) "已在书架" else "加入书架", fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("缓存", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onCacheBook).padding(horizontal = 6.dp, vertical = 8.dp))
                    }
                    if (cacheText.isNotEmpty()) {
                        Text(cacheText, fontSize = 11.sp, color = Color(0xFF888888),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    // 继续阅读进度卡片
                    if (progress != null && progress.chapter in 0 until book.chapters.size) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E1E1E)).clickable { onOpenChapter(progress.chapter) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                "读到 ${book.chapters[progress.chapter].name} · ${(progress.percent * 100).toInt()}%，点此继续",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress.percent },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        }
                    }
                    // 书签
                    if (bookmarks.isNotEmpty()) {
                        Text("书签 (${bookmarks.size})", fontSize = 13.sp, color = Color(0xFFCCCCCC),
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                        bookmarks.forEach { bm ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${book.chapters.getOrNull(bm.chapter)?.name?.takeIf { it.isNotEmpty() } ?: "第${bm.chapter + 1}章"}（${(bm.percent * 100).toInt()}%）",
                                    fontSize = 12.sp,
                                    color = Color(0xFF9E9E9E),
                                    modifier = Modifier.weight(1f).clickable { onOpenBookmark(bm.chapter) }
                                        .padding(vertical = 4.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("×", fontSize = 14.sp, color = Color(0xFFFF8A80),
                                    modifier = Modifier.clickable { onRemoveBookmark(bm.chapter) }.padding(horizontal = 8.dp))
                            }
                        }
                    }
                    Text("目录（${book.chapters.size} 章）", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFFCCCCCC), modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                }
            }
            items(book.chapters.size, key = { it }) { index ->
                val chapter = book.chapters[index]
                val cur = progress != null && progress.chapter == index
                Text(
                    chapter.name,
                    fontSize = 13.sp,
                    color = if (cur) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (cur) Color(0xFF1E2B33) else Color(0xFF212121))
                        .clickable { onOpenChapter(index) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                )
            }
        }
    }

    /** 缓存管理顶栏：返回书架 + 全部清除。 */
    @Composable
    private fun CacheBar(onBack: () -> Unit, onClearAll: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 书架", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("缓存管理", fontSize = 15.sp, color = Color(0xFFE0E0E0))
            Spacer(Modifier.weight(1f))
            Text("全部清除", fontSize = 12.sp, color = Color(0xFFFF8A80),
                modifier = Modifier.clickable(onClick = onClearAll).padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }

    /** 缓存管理：列出每本已缓存书（名称 + 已缓存章节数），可打开/清除；支持整本缓存入口引导。 */
    @Composable
    private fun CacheBody(books: List<ReaderStore.CachedBook>, onOpen: (Book) -> Unit, onClear: (String) -> Unit) {
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无章节缓存\n书籍详情页点「缓存」可整本缓存，离线阅读", fontSize = 13.sp, color = Color(0xFF666666),
                    textAlign = TextAlign.Center)
            }
            return
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(books) { _, entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(entry.book) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderCover(
                        url = entry.book.cover,
                        name = entry.book.name,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.book.name, fontSize = 14.sp, color = Color(0xFFE0E0E0), maxLines = 1)
                        Text("已缓存 ${entry.count} 章", fontSize = 11.sp, color = Color(0xFF888888))
                    }
                    Text("打开", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpen(entry.book) }.padding(horizontal = 8.dp, vertical = 6.dp))
                    Text("清除", fontSize = 12.sp, color = Color(0xFFFF8A80),
                        modifier = Modifier.clickable { onClear(entry.book.url) }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                HorizontalDivider(color = Color(0x14FFFFFF))
            }
        }
    }

    /** RSS 顶栏：返回搜索 + 源名 + 收藏到书架 + 刷新 + 源管理。 */
    @Composable
    private fun RssBar(
        title: String,
        inShelf: Boolean,
        onBack: () -> Unit,
        onManage: () -> Unit,
        onToggleShelf: () -> Unit,
        onRefresh: () -> Unit,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 搜索", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(title, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                if (inShelf) "已在书架" else "收藏",
                fontSize = 12.sp,
                color = if (inShelf) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onToggleShelf).padding(horizontal = 5.dp, vertical = 6.dp),
            )
            Text("刷新", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onRefresh).padding(horizontal = 5.dp, vertical = 6.dp))
            Text("源管理", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onManage).padding(horizontal = 5.dp, vertical = 6.dp))
        }
    }

    /** 收藏页顶栏：返回 + 数量 + 清空。 */
    @Composable
    private fun FavoritesBar(count: Int, onBack: () -> Unit, onClear: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 搜索", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("我的收藏（$count）", fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("清空", fontSize = 12.sp, color = Color(0xFFFF8A80),
                modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }

    /** RSS 正文页：源 chips（含「收藏」入口）+ 文章列表；已读置灰、只看未读过滤；收藏页展示跨源收藏。 */
    @Composable
    private fun RssBody(
        sources: List<RssRepository.RssSource>,
        active: String,
        articles: List<RssRepository.RssArticle>,
        favs: List<RssRepository.RssFav>,
        loading: Boolean,
        error: String,
        unreadOnly: Boolean,
        refreshKey: Int,
        onSelectSource: (String) -> Unit,
        onOpenArticle: (Int) -> Unit,
        onOpenFav: (Int) -> Unit,
        onRemoveFav: (String) -> Unit,
        onToggleUnread: () -> Unit,
    ) {
        val isFav = active == RSS_FAV
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sources.filter { it.enabled }.forEach { s ->
                    val selected = !isFav && s.url == active
                    Text(
                        s.name,
                        fontSize = 12.sp,
                        color = if (selected) Color(0xFF141414) else Color(0xFFCCCCCC),
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                            .clickable { onSelectSource(s.url) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // 收藏页入口
                val favSel = isFav
                Text(
                    "★收藏${if (favs.isEmpty()) "" else "(${favs.size})"}",
                    fontSize = 12.sp,
                    color = if (favSel) Color(0xFF141414) else Color(0xFFFFD54F),
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (favSel) Color(0xFFFFD54F) else Color(0x26FFFFFF))
                        .clickable { onSelectSource(RSS_FAV) }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (sources.none { it.enabled } && !isFav) {
                    Text("暂无启用源：点右上角「源管理」添加", fontSize = 12.sp, color = Color(0xFF666666))
                }
            }
            // 未读过滤（仅源列表页）
            if (!isFav) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (unreadOnly) "√ 只看未读" else "只看未读", fontSize = 12.sp,
                        color = if (unreadOnly) Color(0xFFFFD54F) else Color(0xFF999999),
                        modifier = Modifier.clickable(onClick = onToggleUnread).padding(vertical = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text("阅读后自动标记为已读", fontSize = 11.sp, color = Color(0xFF666666))
                }
            }
            HorizontalDivider(color = Color(0x16FFFFFF), modifier = Modifier.padding(bottom = 4.dp))
            when {
                loading && !isFav -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(30.dp))
                }
                isFav -> {
                    if (favs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("收藏里还没有文章\n在文章阅读页点「☆ 收藏」即可加入", fontSize = 13.sp,
                                color = Color(0xFF666666), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                            itemsIndexed(favs) { index, f ->
                                Row(Modifier.fillMaxWidth().clickable { onOpenFav(index) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.title.ifEmpty { "无标题" }, fontSize = 14.sp, color = Color(0xFFE0E0E0),
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val meta = listOfNotNull(
                                            f.source.ifEmpty { null },
                                            f.pubDate.ifEmpty { null },
                                        ).joinToString(" · ")
                                        if (meta.isNotEmpty()) {
                                            Text(meta, fontSize = 11.sp, color = Color(0xFF777777), maxLines = 1,
                                                modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                    Text("移除", fontSize = 12.sp, color = Color(0xFFFF8A80),
                                        modifier = Modifier.clickable { onRemoveFav(f.link) }.padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                                HorizontalDivider(color = Color(0x12FFFFFF))
                            }
                        }
                    }
                }
                articles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error.ifEmpty { "暂无文章" }, fontSize = 13.sp, color = Color(0xFF666666),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
                else -> {
                    val readMap = remember(articles, active, refreshKey) {
                        articles.associate { it.link.trim() to RssRepository.get().isRead(it.link.trim()) }
                    }
                    val shown = remember(articles, unreadOnly, readMap) {
                        articles.mapIndexed { i, a -> i to a }
                            .filter { (_, a) -> !unreadOnly || !(readMap[a.link.trim()] ?: false) }
                    }
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有未读文章（点「只看未读」取消过滤）", fontSize = 13.sp,
                                color = Color(0xFF666666), modifier = Modifier.padding(horizontal = 32.dp))
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                            itemsIndexed(shown) { _, (origIdx, art) ->
                                val read = readMap[art.link.trim()] ?: false
                                Column(
                                    Modifier.fillMaxWidth().clickable { onOpenArticle(origIdx) }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                ) {
                                    Text(
                                        (if (read) "· " else "") + art.title.ifEmpty { "无标题" },
                                        fontSize = 14.sp,
                                        color = if (read) Color(0xFF777777) else Color(0xFFE0E0E0),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (art.pubDate.isNotEmpty()) {
                                            Text(art.pubDate, fontSize = 11.sp, color = Color(0xFF777777), maxLines = 1)
                                        }
                                        val desc = stripHtml(art.desc)
                                        if (desc.isNotEmpty()) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(desc, fontSize = 11.sp, color = if (read) Color(0xFF5A5A5A) else Color(0xFF888888),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                HorizontalDivider(color = Color(0x12FFFFFF))
                            }
                        }
                    }
                }
            }
        }
    }

    /** 简易剥标签（列表摘要展示用；正文走 htmlToParagraphs）。 */
    private fun stripHtml(html: String): String {
        return Regex("(?i)<[^>]+>").replace(html, " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .trim()
    }

    /** 订阅源管理（视频板块式紧凑弹窗）：列表（开关/测试/删除）+ OPML 导入导出 + 添加（名称 + 地址）。 */
    @Composable
    private fun RssSourceDialog(
        sources: List<RssRepository.RssSource>,
        onClose: () -> Unit,
        onAdd: (String, String) -> Unit,
        onToggle: (String) -> Unit,
        onRemove: (String) -> Unit,
        onTest: (String) -> Unit,
        onImportOpml: () -> Unit,
        onExportOpml: () -> Unit,
    ) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
                    .widthIn(max = 460.dp).heightIn(max = 560.dp)
                    .padding(vertical = 18.dp),
            ) {
                Text("订阅源管理", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text("支持任意 RSS 地址（可直接用 RSSHub 订阅）；点按 ●/○ 启用停用", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    if (sources.isEmpty()) {
                        Text("暂无订阅源，在下方添加", fontSize = 12.sp, color = Color(0xFF777777),
                            modifier = Modifier.padding(vertical = 18.dp))
                    } else {
                        sources.forEach { s ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (s.enabled) "●" else "○", fontSize = 13.sp,
                                    color = if (s.enabled) MaterialTheme.colorScheme.primary else Color(0xFF555555),
                                    modifier = Modifier.clickable { onToggle(s.url) })
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontSize = 14.sp,
                                        color = if (s.enabled) Color(0xFFDDDDDD) else Color(0xFF777777), maxLines = 1)
                                    Text(s.url, fontSize = 10.sp, color = Color(0xFF666666), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                Text("测试", fontSize = 12.sp, color = Color(0xFF81C784),
                                    modifier = Modifier.clickable { onTest(s.url) }.padding(6.dp))
                                Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80),
                                    modifier = Modifier.clickable { onRemove(s.url) }.padding(6.dp))
                            }
                            HorizontalDivider(color = Color(0x16FFFFFF))
                        }
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onImportOpml) { Text("导入 OPML", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = onExportOpml) { Text("导出 OPML", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("源名称（如 IT之家）", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("RSS 地址（如 http://www.ithome.com/rss/）", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onClose) { Text("关闭", color = Color(0xFF888888)) }
                        Spacer(Modifier.width(20.dp))
                        Button(onClick = {
                            onAdd(name, url)
                            name = ""
                            url = ""
                        }) { Text("添加", fontSize = 13.sp) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReaderBar(
        chapterName: String,
        onBack: () -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onAddBookmark: () -> Unit,
        onOpenSettings: () -> Unit,
        ttsPlaying: Boolean,
        onToggleTts: () -> Unit,
        favVisible: Boolean,
        favOn: Boolean,
        onToggleFav: () -> Unit,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 目录", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(chapterName, fontSize = 14.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (favVisible) {
                Text(if (favOn) "★ 已藏" else "☆ 收藏", fontSize = 13.sp, color = if (favOn) Color(0xFFFFD54F) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onToggleFav).padding(horizontal = 6.dp, vertical = 6.dp))
            }
            Text("☆ 书签", fontSize = 13.sp, color = Color(0xFFFFD54F),
                modifier = Modifier.clickable(onClick = onAddBookmark).padding(horizontal = 6.dp, vertical = 6.dp))
            Text(if (ttsPlaying) "停止朗读" else "朗读", fontSize = 13.sp,
                color = if (ttsPlaying) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onToggleTts).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("设置", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenSettings).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("上一章", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onPrev).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("下一章", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onNext).padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }

    /** 正文块：段落 / 图片 / 视频，按原文档顺序内嵌渲染（图片视频直接混在文章流里，不再切换模式）。 */
    private sealed class RBlock {
        data class Text(val text: String) : RBlock()
        data class Image(val url: String) : RBlock()
        data class Video(val url: String) : RBlock()
    }

    /**
     * 正文阅读：段落 + 图片 + 视频全部按原顺序内嵌在同一个 LazyColumn（对齐国内小说/资讯 App 排版）。
     * - 块（段落/图片/视频）是渲染与定位的最小单位：断点/书签存「块号」，打开时 scrollToItem 精确恢复
     * - 字号/行距/主题实时应用到 Text，进度条按块比例
     */
    @Composable
    private fun ReaderBody(loading: Boolean, content: String, error: String) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
            return
        }
        if (content.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error.ifEmpty { "正文为空" }, fontSize = 13.sp, color = Color(0xFFFF8A80),
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
            return
        }
        val fs = ui.fontSize.coerceIn(12, 30)
        val lineSize = (fs * ui.lineHeight).coerceAtLeast(fs + 2f).sp
        val (bg, fg) = themeColors()
        val base = ui.book?.chapters?.getOrNull(ui.chapterIndex)?.url ?: ""
        val blocks = remember(content, base) { contentBlocks(content, base) }
        val listState = rememberLazyListState()
        // 渲染标识：正文 + 字号 + 行距 + 主题任一变化 → 恢复同一块位置（设置变更重排等价保留进度）
        val renderKey = content.hashCode().toString() + "|$fs|${ui.lineHeight}|${ui.theme}"
        Column(Modifier.fillMaxSize().background(bg)) {
            LinearProgressIndicator(
                progress = { ui.readPercent },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(blocks) { _, blk ->
                    when (blk) {
                        is RBlock.Text -> Text(
                            blk.text,
                            color = fg,
                            fontSize = fs.sp,
                            lineHeight = lineSize,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
                        )
                        is RBlock.Image -> InlineImage(blk.url)
                        is RBlock.Video -> Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp)).background(Color(0x1FFFFFFF))
                                .clickable { ui.videoPlayUrl = blk.url }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("▶ 视频", fontSize = 13.sp, color = Color(0xFF81C784))
                            Spacer(Modifier.width(10.dp))
                            Text(blk.url, fontSize = 11.sp, color = Color(0xFF999999), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text("点击播放", fontSize = 12.sp, color = Color(0xFF4FC3F7))
                        }
                    }
                }
            }
        }
        // 初次布局 / 内容或设置变化时：一次性恢复到目标块（消费 restorePara）
        LaunchedEffect(blocks, renderKey) {
            val rp = restorePara
            if (rp in blocks.indices) {
                restorePara = -1
                listState.scrollToItem(rp)
            }
            currentParaIndex = listState.firstVisibleItemIndex
            currentParaCount = blocks.size
            ui.readPercent = if (blocks.isEmpty()) 0f else (currentParaIndex.toFloat() / blocks.size)
        }
        // 滚动跟踪：首可见块号变化 → 节流保存断点
        LaunchedEffect(blocks) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
                if (idx != currentParaIndex) {
                    currentParaIndex = idx
                    maybeAutoSaveProgress()
                }
            }
        }
    }

    /** 内嵌图片：Glide 整宽加载，失败/加载中给占位反馈。 */
    @Composable
    private fun InlineImage(url: String) {
        val context = LocalContext.current
        var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(url) { mutableStateOf(false) }
        LaunchedEffect(url) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    Glide.with(context).asBitmap()
                        .apply(RequestOptions().timeout(15_000))
                        .load(url).submit().get()
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap == null) failed = true
        }
        val bmp = bitmap
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                failed -> Text("图片加载失败", fontSize = 12.sp, color = Color(0xFF666666),
                    modifier = Modifier.padding(vertical = 18.dp))
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "正文图片",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> CircularProgressIndicator(modifier = Modifier.size(26.dp).padding(vertical = 24.dp))
            }
        }
    }
    /** HTML 正文 → 有序块列表：段落文本与 <img>、mp4/m3u8 视频直链按原文档顺序交错（图片视频内嵌文中）。 */
    private fun contentBlocks(html: String, base: String): List<RBlock> {
        if (html.isBlank()) return emptyList()
        val imgRe = Regex("(?i)<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']")
        val vidRe = Regex("""https?://[^\s"'<>),；，。！？】]+\.(?:m3u8|mp4)(?:[?#][^\s"'<>),；，。！？】]*)?(?=[\s"'<>),；，。！？】]|$)""", RegexOption.IGNORE_CASE)
        data class M(val start: Int, val end: Int, val url: String, val isImg: Boolean)
        val marks = ArrayList<M>()
        imgRe.findAll(html).forEach { marks.add(M(it.range.first, it.range.last + 1, resolveImageSrc(base, it.groupValues[1]), true)) }
        vidRe.findAll(html).forEach { marks.add(M(it.range.first, it.range.last + 1, it.value, false)) }
        marks.sortBy { it.start }
        val out = ArrayList<RBlock>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString()
            sb.setLength(0)
            if (t.isBlank()) return
            // 文本切片里若有残留视频链接（非独立标签），剔除避免重复
            val cleaned = vidRe.replace(t, " ")
            for (p in htmlToParagraphs(cleaned)) if (p.isNotBlank()) out.add(RBlock.Text(p))
        }
        var cursor = 0
        for (m in marks) {
            if (m.start > cursor) sb.append(html, cursor, m.start)
            flush()
            if (m.isImg) out.add(RBlock.Image(m.url)) else out.add(RBlock.Video(m.url))
            cursor = m.end
        }
        if (cursor < html.length) sb.append(html, cursor, html.length)
        flush()
        return out
    }

    /** 图片相对路径按章节目录解析成绝对地址（data/ 等协议原样返回）。 */
    private fun resolveImageSrc(base: String, src: String): String {
        val s = src.trim().replace("&amp;", "&")
        if (s.startsWith("http") || s.startsWith("data:") || s.startsWith("//")) {
            return if (s.startsWith("//")) "https:" + s else s
        }
        return try {
            java.net.URL(java.net.URL(base), s).toString()
        } catch (e: Exception) {
            s
        }
    }

    /** HTML 正文 → 段落列表：块级/换行标签转换行，剥除其余标签，解码常用实体。 */
    private fun htmlToParagraphs(html: String): List<String> {
        var s = html ?: return emptyList()
        s = Regex("(?i)<(br|/p|/div|/h[1-6]|/li|/section)\\s*/?>").replace(s, "\n")
        s = Regex("(?i)<[^>]+>").replace(s, "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&ldquo;", "“").replace("&rdquo;", "”")
            .replace("&hellip;", "…").replace("&mdash;", "—")
        val out = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return if (out.isEmpty()) listOf("") else out
    }

    /** 主题 → (背景色, 前景色)；跟随系统时按系统深浅解析（浅色模式用暖白正文）。 */
    private fun themeColors(): Pair<Color, Color> = when (ThemeStore.get().resolveNovel()) {
        "sepia" -> Pair(Color(0xFF241B12), Color(0xFFD8C9A8))
        "night" -> Pair(Color(0xFF050505), Color(0xFF8A8A8A))
        "light" -> Pair(Color(0xFFF5F0E6), Color(0xFF3A3A3A))
        else -> Pair(Color(0xFF141414), Color(0xFFC9C9C9))
    }

    /** 板块内书源切换弹窗（视频板块式紧凑弹窗）：只列书源 + 点按启用/停用；底部「去设置导入」一键直达管理（导入/测试/删除）。 */
    @Composable
    private fun SourceSwitchDialog(
        sources: List<BookSource>,
        onClose: () -> Unit,
        onToggle: (String) -> Unit,
        onGoImport: () -> Unit,
    ) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
                    .widthIn(max = 460.dp).heightIn(max = 480.dp)
                    .padding(vertical = 18.dp),
            ) {
                Text("切换书源", fontSize = 16.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text("点按启用/停用要用来搜索的书源；已启用源全部参与搜索（共 ${sources.count { it.enabled }} 个）", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                    if (sources.isEmpty()) {
                        item {
                            Text("暂无书源", fontSize = 12.sp,
                                color = Color(0xFF777777), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
                        }
                    }
                    itemsIndexed(sources) { _, s ->
                        Row(Modifier.fillMaxWidth().clickable { onToggle(s.url) }
                            .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (s.enabled) "●  " else "○  ", fontSize = 14.sp,
                                color = if (s.enabled) MaterialTheme.colorScheme.primary else Color(0xFF555555))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontSize = 14.sp,
                                    color = if (s.enabled) Color(0xFFDDDDDD) else Color(0xFF777777), maxLines = 1)
                                Text(if (s.enabled) "正在使用" else "已停用", fontSize = 10.sp,
                                    color = Color(0xFF666666))
                            }
                        }
                        HorizontalDivider(color = Color(0x16FFFFFF))
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                Text("导入 / 测试 / 删除在「设置 → 书源管理」 ›", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onGoImport).padding(top = 10.dp))
            }
        }
    }

    /** 导入书籍：本地文件（TXT/EPUB）/ 远程直链 二选一。 */
    @Composable
    private fun ImportDialog(
        onPickLocal: () -> Unit,
        onRemote: () -> Unit,
        onClose: () -> Unit,
    ) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("导入书籍", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 14.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onPickLocal)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("本地文件", fontSize = 16.sp, color = Color(0xFFE0E0E0))
                    Spacer(Modifier.weight(1f))
                    Text("TXT / EPUB", fontSize = 13.sp, color = Color(0xFF888888))
                    Spacer(Modifier.width(10.dp))
                    Text("选择", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = Color(0x16FFFFFF))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onRemote)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("远程地址", fontSize = 16.sp, color = Color(0xFFE0E0E0))
                    Spacer(Modifier.weight(1f))
                    Text("粘贴 .txt / .epub 直链", fontSize = 13.sp, color = Color(0xFF888888))
                    Spacer(Modifier.width(10.dp))
                    Text("输入", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = Color(0x16FFFFFF))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 远程书籍导入：输入 .txt/.epub 直链，拉取后按本地流程切章入书架。 */
    @Composable
    private fun RemoteImportDialog(importing: Boolean, onImport: (String) -> Unit, onClose: () -> Unit) {
        var url by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("远程书籍导入", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp))
                Text("粘贴书籍文件直链（.txt 自动切章 / .epub 自动解析），拉到服务器后离线可读", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                    placeholder = { Text("https://example.com/book.epub", color = Color(0xFF666666), fontSize = 13.sp) },
                    maxLines = 2,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onClose) { Text("取消", color = Color(0xFF888888)) }
                    Spacer(Modifier.width(20.dp))
                    Button(onClick = { onImport(url.trim()) }, enabled = !importing && url.isNotBlank()) {
                        Text(if (importing) "导入中…" else "导入", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    /** 全文搜索弹窗：输入关键词，在书架已缓存/本地书籍章节内定位，点结果直接跳到段落。 */
    @Composable
    private fun FulltextDialog(
        searching: Boolean,
        keyword: String,
        hits: List<FulltextHit>,
        onKeyword: (String) -> Unit,
        onSearch: () -> Unit,
        onOpen: (FulltextHit) -> Unit,
        onClose: () -> Unit,
    ) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("全文搜索", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp))
                Text("搜索书架中已缓存/本地书籍的正文；远程书请先「下载全书」", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = onKeyword,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入关键词", color = Color(0xFF666666), fontSize = 13.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSearch, enabled = !searching && keyword.isNotBlank()) {
                        Text(if (searching) "搜索中…" else "搜索", fontSize = 13.sp)
                    }
                }
                if (hits.isEmpty() && !searching) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("暂无结果", fontSize = 13.sp, color = Color(0xFF666666))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 6.dp)) {
                        itemsIndexed(hits) { _, h ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onOpen(h) }
                                    .padding(horizontal = 20.dp, vertical = 7.dp),
                            ) {
                                Text(h.bookName + " · " + h.chapterName, fontSize = 13.sp, color = Color(0xFF4FC3F7), maxLines = 1)
                                Text(h.snippet, fontSize = 12.sp, color = Color(0xFFBBBBBB), maxLines = 2)
                            }
                            HorizontalDivider(color = Color(0x16FFFFFF))
                        }
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 阅读统计弹窗：今日阅读时长 + 最近阅读记录（点记录继续阅读）。 */
    @Composable
    private fun StatsDialog(onClose: () -> Unit, onOpenRecord: (String, String) -> Unit) {
        val stats = remember { ReaderStore.get().readingStats() }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("阅读统计", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp))
                Text(
                    "今日阅读 ${stats.todayMs / 60000} 分钟 · 最近在读 ${stats.records.size} 本",
                    fontSize = 13.sp, color = Color(0xFF4FC3F7), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                if (stats.records.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("暂无阅读记录", fontSize = 13.sp, color = Color(0xFF666666))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
                        itemsIndexed(stats.records) { _, r ->
                            Column(
                                Modifier.fillMaxWidth().clickable { if (r.url.isNotBlank()) onOpenRecord(r.url, r.name) }
                                    .padding(horizontal = 24.dp, vertical = 7.dp),
                            ) {
                                Text(r.name, fontSize = 14.sp, color = Color(0xFFDDDDDD), maxLines = 1)
                                Row(Modifier.fillMaxWidth()) {
                                    Text(r.chapterName.ifEmpty { "进度 ${(r.percent * 100).toInt()}%" },
                                        fontSize = 12.sp, color = Color(0xFF888888), maxLines = 1)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                                            .format(java.util.Date(r.time)),
                                        fontSize = 11.sp, color = Color(0xFF555555),
                                    )
                                }
                            }
                            HorizontalDivider(color = Color(0x14FFFFFF))
                        }
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 听书朗读面板：播放/暂停、上下章、语速、引擎（本机/在线）与在线接口模板。 */
    @Composable
    private fun TtsPanel(
        playing: Boolean,
        speed: Float,
        engine: String,
        onlineUrl: String,
        status: String,
        chapterName: String,
        onTogglePlay: () -> Unit,
        onPrevChapter: () -> Unit,
        onNextChapter: () -> Unit,
        onSpeed: (Float) -> Unit,
        onEngine: (String) -> Unit,
        onOnlineUrl: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        var urlText by remember { mutableStateOf(onlineUrl) }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.fillMaxSize().background(Color(0xE6000000))
                    .padding(horizontal = 28.dp).verticalScroll(rememberScrollState()),
            ) {
                Text("听书朗读", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp))
                Text(chapterName, fontSize = 12.sp, color = Color(0xFFBBBBBB), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(), maxLines = 1)
                Text(status.ifEmpty { if (playing) "朗读中…" else "已停止" }, fontSize = 12.sp,
                    color = if (playing) MaterialTheme.colorScheme.primary else Color(0xFF888888),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                // 播放控制
                Row(Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("上一章", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onPrevChapter).padding(horizontal = 16.dp, vertical = 10.dp))
                    Text(if (playing) "暂停" else "播放", fontSize = 18.sp, color = Color(0xFFFFD54F),
                        modifier = Modifier.clickable(onClick = onTogglePlay).padding(horizontal = 24.dp, vertical = 10.dp))
                    Text("下一章", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onNextChapter).padding(horizontal = 16.dp, vertical = 10.dp))
                }
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
                // 语速
                Text("语速（本机朗读生效）", fontSize = 13.sp, color = Color(0xFFDDDDDD))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    for (opt in listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)) {
                        val sel = Math.abs(speed - opt) < 0.01f
                        Text(
                            if (opt == opt.toInt().toFloat()) opt.toInt().toString() else opt.toString(),
                            fontSize = 14.sp,
                            color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                .clickable { onSpeed(opt) }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
                // 引擎选择
                Text("朗读引擎", fontSize = 13.sp, color = Color(0xFFDDDDDD))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    for ((key, label) in listOf("local" to "本机朗读", "online" to "在线朗读")) {
                        val sel = engine == key
                        Text(
                            label,
                            fontSize = 14.sp,
                            color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                .clickable { onEngine(key) }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                if (engine == "online") {
                    Text("在线接口（GET 模板，{text} 替换为编码后的句子；需返回 mp3 音频）", fontSize = 11.sp,
                        color = Color(0xFF777777), modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it; onOnlineUrl(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        placeholder = { Text("https://…?text={text}", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Text("内置示例为有道词典语音（短句）。可换成自建 Edge TTS / 百度等任何返回 mp3 的接口",
                        fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text("使用设备自带 TTS 引擎，离线可用；需系统已安装 TTS 语音包（多数 TV 盒子没有）",
                        fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.padding(top = 8.dp))
                }
                Text("停止朗读并关闭", fontSize = 13.sp, color = Color(0xFFFF8A80), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onClose).padding(top = 18.dp, bottom = 6.dp))
            }
        }
    }

    /** 订阅源视频播放：RSS 正文里的 mp4/m3u8 直链，内嵌 MediaPlayer 全屏播放。 */
    @Composable
    private fun VideoDialog(url: String, onClose: () -> Unit) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            var playing by remember { mutableStateOf(true) }
            var err by remember { mutableStateOf("") }
            val player = remember { MediaPlayer() }
            val ready = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
            DisposableEffect(url) {
                try {
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    player.setDataSource(url)
                    player.setOnPreparedListener {
                        ready.set(true)
                        player.start()
                    }
                    player.setOnErrorListener { _, _, _ ->
                        err = "播放失败：格式或地址不可用（请返回或更换其它视频）"
                        playing = false
                        true
                    }
                    player.setOnCompletionListener { playing = false }
                    player.prepareAsync()
                } catch (e: Exception) {
                    err = "播放失败：" + (e.message ?: "")
                }
                onDispose {
                    try {
                        player.release()
                    } catch (_: Exception) {
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable {
                    if (playing) {
                        try {
                            player.pause()
                        } catch (_: Exception) {
                        }
                    } else {
                        try {
                            if (ready.get()) player.start()
                        } catch (_: Exception) {
                        }
                    }
                    playing = !playing
                },
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                                    try {
                                        player.setSurface(Surface(s))
                                        if (ready.get() && !player.isPlaying) player.start()
                                    } catch (_: Exception) {
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0x99000000)).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (err.isNotEmpty()) {
                        Text(err, fontSize = 13.sp, color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    } else {
                        Text(if (playing) "点击屏幕暂停" else "点击屏幕继续", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                    }
                    Text("关闭", fontSize = 14.sp, color = Color(0xFFE0E0E0),
                        modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Text(url.take(110), fontSize = 10.sp, color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 20.dp, vertical = 8.dp), maxLines = 1)
            }
        }
    }

    /** 阅读设置弹窗：字号滑动调节 / 行距 / 主题，改动即生效并持久化。 */
    @Composable
    private fun SettingsDialog(
        fontSize: Int,
        lineHeight: Float,
        theme: String,
        onFontSize: (Int) -> Unit,
        onLineHeight: (Float) -> Unit,
        onTheme: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        var sliderValue by remember { mutableStateOf(fontSize.toFloat()) }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("阅读设置", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp))
                Column(
                    Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp),
                ) {
                    // 字号
                    Text("字号  ${fontSize}sp", fontSize = 14.sp, color = Color(0xFFDDDDDD),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { onFontSize(sliderValue.toInt()) },
                        valueRange = 12f..30f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
                    // 行距
                    Text("行距", fontSize = 14.sp, color = Color(0xFFDDDDDD))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        for (opt in listOf(1.5f, 1.8f, 2.0f, 2.4f)) {
                            val selected = Math.abs(lineHeight - opt) < 0.01f
                            Text(
                                if (opt == opt.toInt().toFloat()) opt.toInt().toString() else opt.toString(),
                                fontSize = 14.sp,
                                color = if (selected) Color(0xFF141414) else Color(0xFFCCCCCC),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                    .clickable { onLineHeight(opt) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
                    // 主题
                    Text("主题", fontSize = 14.sp, color = Color(0xFFDDDDDD))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        for ((key, label) in listOf("system" to "跟随系统", "dark" to "深色", "sepia" to "护眼", "night" to "夜间")) {
                            val selected = theme == key
                            Text(
                                label,
                                fontSize = 14.sp,
                                color = if (selected) Color(0xFF141414) else Color(0xFFCCCCCC),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                    .clickable { onTheme(key) }
                                    .padding(horizontal = 13.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    // 本地 TXT 切章：内置默认正则即可，不再提供设置项
                    Spacer(Modifier.height(16.dp))
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 封面桥：Glide 异步加载封面并淡入，无封面/失败时用色块 + 书名首字占位。 */
    @Composable
    private fun ReaderCover(url: String?, name: String, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(url) {
            if (url.isNullOrEmpty()) {
                bitmap = null
                return@LaunchedEffect
            }
            bitmap = withContext(Dispatchers.IO) {
                try {
                    Glide.with(context).asBitmap()
                        .apply(RequestOptions().override(160, 160).timeout(8000))
                        .load(url).submit().get()
                } catch (e: Exception) {
                    null
                }
            }
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
            Box(modifier.background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
                Text(name.take(1).ifEmpty { "书" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
            }
        }
    }
}