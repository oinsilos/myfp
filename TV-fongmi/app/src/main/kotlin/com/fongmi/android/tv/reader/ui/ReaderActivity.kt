package com.fongmi.android.tv.reader.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.fongmi.android.tv.reader.Book
import com.fongmi.android.tv.reader.BookSource
import com.fongmi.android.tv.reader.ReaderRepository
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.utils.Notify
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阅读（书源）界面：搜索 → 书籍详情/目录 → 正文阅读（Compose 段落排版，断点按段落号恢复）。
 * 书源管理：内置 + 粘贴 JSON/URL 导入；搜索并发跑全部启用书源。
 */
class ReaderActivity : AppCompatActivity() {

    private class UiState {
        var results by mutableStateOf<List<Book>>(emptyList())
        var searching by mutableStateOf(false)
        var keyword by mutableStateOf("")
        // 书源管理
        var sources by mutableStateOf<List<BookSource>>(emptyList())
        var sourceDialogVisible by mutableStateOf(false)
        var importing by mutableStateOf(false)
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
        /** 当前详情书是否在书架（Compose 可观察，收藏/移出即时刷新按钮）。 */
        var inShelf by mutableStateOf(false)
        // 批2：章节缓存 + 本地 TXT 导入 + 阅读设置
        var cacheText by mutableStateOf("")
        var settingsDialogVisible by mutableStateOf(false)
        // 阅读设置（Compose 可观察：改动即重绘正文样式；持久化在 ReaderStore）
        var fontSize by mutableStateOf(17)
        var lineHeight by mutableStateOf(1.9f)
        var theme by mutableStateOf("dark")
        // 缓存管理页：列出已缓存的书，支持单本/全部清除
        var cacheMode by mutableStateOf(false)
        var cacheBooks by mutableStateOf<List<ReaderStore.CachedBook>>(emptyList())
        // 章内阅读百分比（滚动节流更新，供阅读页顶部进度条展示）
        var readPercent by mutableStateOf(0f)
    }

    private val ui = UiState()
    /** 当前章首可见段落号（LazyColumn 滚动实时更新，退出/切章时落盘）。 */
    private var currentParaIndex = 0
    /** 当前章段落总数（正文到达后按 htmlToParagraphs 计算）。 */
    private var currentParaCount = 0
    /** 滚动进度节流落盘时间戳（2s 最多写一次，进程被杀也能续读）。 */
    private var lastAutoSaveAt = 0L
    /** 待恢复的段落号（openChapter 从进度库读到后置值；阅读页首次布局消费后复位 -1）。 */
    private var restorePara = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 搜索兜底：底层（书源网络/规则求值）无论怎样，25s 内必须结束搜索态，绝不无限转圈。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val SEARCH_TIMEOUT_MS = 25_000L

    /** 本地 TXT 书籍文件选择器（SAF）。 */
    private val localTxtPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocalTxt(uri)
    }

    companion object {
        private const val TAG = "ReaderActivity"

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, ReaderActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderRepository.get().init(this)
        ReaderStore.get().init(this)
        val rdr = ReaderStore.get()
        ui.fontSize = rdr.fontSize
        ui.lineHeight = rdr.lineHeight
        ui.theme = rdr.theme
        refreshSources()
        refreshShelves()
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
                    onOpenSettings = { ui.settingsDialogVisible = true },
                    onCacheBook = ::cacheCurrentBook,
                    onImportLocalTxt = { localTxtPicker.launch(arrayOf("text/*")) },
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
                    onOpenSources = { ui.sourceDialogVisible = true },
                    onImport = { importSource(it) },
                    onToggleSource = { toggleSource(it) },
                    onRemoveSource = { removeSource(it) },
                )
            }
        }
    }

    /** 退到后台/切走时立即落盘进度，保证系统杀进程后也能断点续读。 */
    override fun onPause() {
        super.onPause()
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
            runOnUiThread {
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
            ui.cacheText = "本地文件 · ${book.chapters.size} 节"
            if (autoContinue) {
                val p = ReaderStore.get().progress(book.url)
                if (p != null && p.chapter in 0 until book.chapters.size) openChapter(p.chapter)
                else if (book.chapters.isNotEmpty()) openChapter(0)
            }
            return
        }
        ReaderRepository.get().detail(book).whenComplete { b, e1 ->
            val bd = b ?: book
            ReaderRepository.get().toc(bd).whenComplete { tb, e2 ->
                runOnUiThread {
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
            return
        }
        ReaderRepository.get().chapter(book.chapters[index].url, book.source).whenComplete { html, e ->
            runOnUiThread {
                ui.contentLoading = false
                if (e != null || html == null) {
                    // 网络失败时也尝试缓存兜底（部分缓存场景）
                    val fallback = ReaderStore.get().cachedChapter(book.url, index)
                    if (fallback != null && fallback.isNotEmpty()) {
                        ui.content = fallback
                    } else {
                        ui.contentError = "正文加载失败：" + friendly(e ?: Exception("空正文"))
                    }
                } else {
                    ui.content = html
                    Thread { ReaderStore.get().cacheChapter(book.url, index, html) }.start()
                }
            }
        }
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
                        runOnUiThread { ui.cacheText = "缓存 $done/$all" }
                    }
                } catch (ignored: Exception) {
                }
            }
            runOnUiThread {
                ui.cacheText = "已缓存 $cached/$total 章"
                Notify.show("缓存完成：$cached/$total 章")
            }
        }.start()
    }

    /** 本地 TXT 书籍导入：读文本 → 按章节标题切章 → 逐章写入缓存 → 入书架并打开。 */
    private fun importLocalTxt(uri: Uri) {
        Thread {
            try {
                val name = queryName(contentResolver, uri) ?: "本地书籍.txt"
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    runOnUiThread { Notify.show("文本内容为空") }
                    return@Thread
                }
                val bookUrl = "local_txt://" + abs(name.hashCode())
                val book = Book(bookUrl, name.removeSuffix(".txt").ifEmpty { "本地书籍" }, "本地文件", "")
                book.source = "local"
                var idx = 0
                for ((title, body) in splitChapters(text)) {
                    val html = "<p>" + body.replace("\n", "<br/>") + "</p>"
                    ReaderStore.get().cacheChapter(bookUrl, idx, html)
                    book.chapters.add(Book.Chapter(title.ifEmpty { "第${idx + 1}节" }, "local_txt#$idx"))
                    idx++
                }
                ReaderStore.get().addToShelf(book)
                runOnUiThread {
                    refreshShelves()
                    Notify.show("已导入「${book.name}」${book.chapters.size} 节")
                    // 覆盖简要信息后直接进书架详情（不再请求网络）
                    openBook(book, false)
                    ui.shelfMode = false
                }
            } catch (e: Exception) {
                runOnUiThread { Notify.show("导入失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    /** 文本切章：优先「第X章/卷/回/节」标题分割，无标题时整篇作一节。 */
    private fun splitChapters(text: String): List<Pair<String, String>> {
        val titleRe = Regex("^\\s*(第[0-9零一二三四五六七八九十百千万]+[章节卷回部篇集])")
        val out = mutableListOf<Pair<String, String>>()
        var curTitle = ""
        val curBody = StringBuilder()
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t.isNotEmpty() && titleRe.containsMatchIn(t) && curBody.isNotEmpty()) {
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

    /** 退出阅读页（返回目录）：落盘当前章内进度。 */
    private fun leaveReader() {
        saveProgress()
        ui.reading = false
    }

    private fun saveProgress() {
        val book = ui.book ?: return
        if (ui.content.isEmpty()) return
        ReaderStore.get().saveProgressPara(book.url, ui.chapterIndex, currentParaIndex, currentParaCount)
    }

    /** 滚动中节流保存进度（2s 一次），防止进程被杀时丢失断点；同时刷新阅读页进度条。 */
    private fun maybeAutoSaveProgress() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSaveAt < 2000L) return
        lastAutoSaveAt = now
        ui.readPercent = if (currentParaCount <= 0) 0f else (currentParaIndex.toFloat() / currentParaCount).coerceIn(0f, 1f)
        mainHandler.post { saveProgress() }
    }

    private fun refreshCacheBooks() {
        ui.cacheBooks = ReaderStore.get().cachedBooks()
    }

    /** 设置变更：写入 ReaderStore 持久化，并以新样式重载当前章（进度位置保留）。 */
    private fun applySettings() {
        val s = ReaderStore.get()
        s.fontSize = ui.fontSize
        s.lineHeight = ui.lineHeight
        s.theme = ui.theme
        s.saveSettings()
        if (ui.reading) openChapter(ui.chapterIndex)
    }

    private fun refreshShelves() {
        ui.shelves = ReaderStore.get().shelf()
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

    private fun importSource(text: String) {
        if (text.isBlank()) return
        ui.importing = true
        ReaderRepository.get().importSource(text.trim()).whenComplete { n, _ ->
            runOnUiThread {
                ui.importing = false
                refreshSources()
                if (n != null && n > 0) {
                    Notify.show("已导入 $n 个书源")
                } else {
                    Notify.show("导入失败：不是合法的书源 JSON/URL")
                }
            }
        }
    }

    private fun toggleSource(url: String) {
        ReaderRepository.get().toggleSource(url)
        refreshSources()
    }

    private fun removeSource(url: String) {
        ReaderRepository.get().removeSource(url)
        refreshSources()
        Notify.show("已删除该书源")
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
        onOpenSettings: () -> Unit,
        onCacheBook: () -> Unit,
        onImportLocalTxt: () -> Unit,
        onOpenCache: () -> Unit,
        onCloseCache: () -> Unit,
        onClearBookCache: (String) -> Unit,
        onClearAllCache: () -> Unit,
        onOpenSources: () -> Unit,
        onImport: (String) -> Unit,
        onToggleSource: (String) -> Unit,
        onRemoveSource: (String) -> Unit,
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
                    )
                } else if (ui.cacheMode) {
                    CacheBar(onBack = onCloseCache, onClearAll = onClearAllCache)
                } else if (currentBook != null) {
                    BookBar(book = currentBook, onBack = onBackFromBook)
                } else if (ui.shelfMode) {
                    ShelfBar(count = ui.shelves.size, onBack = onBackFromShelf, onOpenCache = onOpenCache, onOpenSources = onOpenSources)
                } else {
                    SearchTop(
                        keyword = keyword,
                        onKeyword = { keyword = it },
                        onSearch = { onSearch(keyword) },
                        sources = ui.sources,
                        onOpenSources = onOpenSources,
                        shelfCount = ui.shelves.size,
                        onOpenShelf = onOpenShelf,
                    )
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                if (ui.reading) {
                    ReaderBody(
                        loading = ui.contentLoading,
                        content = ui.content,
                        error = ui.contentError,
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
                        onOpenBook = onOpenShelfBook,
                        onRemove = onRemoveShelf,
                        onImportLocalTxt = onImportLocalTxt,
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
        if (ui.sourceDialogVisible) {
            SourceDialog(
                sources = ui.sources,
                importing = ui.importing,
                onClose = { ui.sourceDialogVisible = false },
                onImport = onImport,
                onToggle = onToggleSource,
                onRemove = onRemoveSource,
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
                Text("输入书名搜索；书源可在右上角导入/切换", fontSize = 13.sp, color = Color(0xFF666666))
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(results) { _, book ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenBook(book) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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

    /** 书架顶栏：返回搜索 + 标题；右侧缓存管理与书源入口。 */
    @Composable
    private fun ShelfBar(count: Int, onBack: () -> Unit, onOpenCache: () -> Unit, onOpenSources: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 搜索", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("书架 ($count)", fontSize = 15.sp, color = Color(0xFFE0E0E0))
            Spacer(Modifier.weight(1f))
            Text("缓存", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenCache).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("书源", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenSources).padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }

    /** 书架：封面 + 书名 + 进度，行尾移出；列表顶部固定「导入本地 TXT」入口。 */
    @Composable
    private fun ShelfBody(shelves: List<Book>, onOpenBook: (Book) -> Unit, onRemove: (String) -> Unit, onImportLocalTxt: () -> Unit) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "import") {
                Row(
                    Modifier.fillMaxWidth().clickable { onImportLocalTxt() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF245060)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("TXT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导入本地 TXT 小说", fontSize = 14.sp, color = Color(0xFFE0E0E0))
                        Text("选择 .txt 文件，按「第X章」自动切分章节加入书架，离线可读", fontSize = 11.sp,
                            color = Color(0xFF888888), maxLines = 2)
                    }
                    Text("导入", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = Color(0x16FFFFFF))
            }
            if (shelves.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                        Text("书架还空着：搜索到书后点「加入书架」", fontSize = 13.sp, color = Color(0xFF666666))
                    }
                }
                return@LazyColumn
            }
            itemsIndexed(shelves) { _, book ->
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
                    Text("移出", fontSize = 12.sp, color = Color(0xFFFF8A80),
                        modifier = Modifier.clickable { onRemove(book.url) }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                HorizontalDivider(color = Color(0x14FFFFFF))
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

    @Composable
    private fun ReaderBar(
        chapterName: String,
        onBack: () -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onAddBookmark: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 目录", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(chapterName, fontSize = 14.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("☆ 书签", fontSize = 13.sp, color = Color(0xFFFFD54F),
                modifier = Modifier.clickable(onClick = onAddBookmark).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("设置", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenSettings).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("上一章", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onPrev).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("下一章", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onNext).padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }

    /**
     * 正文阅读（对齐 legado/国内小说 App 的做法：不用 WebView，Compose 自己排版文本）。
     * - 段落是渲染与定位的最小单位：断点/书签存“章内段落号”，打开时 scrollToItem 精确恢复，与字号/布局无关
     * - 字号/行距/主题实时应用到 Text，进度条按段落比例
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
        val paras = remember(content) { htmlToParagraphs(content) }
        val listState = rememberLazyListState()
        // 渲染标识：正文 + 字号 + 行距 + 主题任一变化 → 恢复同一段落位置（设置变更重排等价保留进度）
        val renderKey = content.hashCode().toString() + "|$fs|${ui.lineHeight}|${ui.theme}"
        Column(Modifier.fillMaxSize().background(bg)) {
            LinearProgressIndicator(
                progress = { ui.readPercent },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(paras) { index, paragraph ->
                    Text(
                        text = paragraph.ifEmpty { " " },
                        fontSize = fs.sp,
                        lineHeight = lineSize,
                        color = fg,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
                    )
                }
            }
        }
        // 初次布局 / 内容或设置变化时：一次性恢复到目标段落（消费 restorePara）
        LaunchedEffect(paras, renderKey) {
            val rp = restorePara
            if (rp in paras.indices) {
                restorePara = -1
                listState.scrollToItem(rp)
            }
            currentParaIndex = listState.firstVisibleItemIndex
            currentParaCount = paras.size
            ui.readPercent = if (paras.isEmpty()) 0f else (currentParaIndex.toFloat() / paras.size)
        }
        // 滚动跟踪：首可见段落号变化 → 节流保存断点
        LaunchedEffect(paras) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
                if (idx != currentParaIndex) {
                    currentParaIndex = idx
                    maybeAutoSaveProgress()
                }
            }
        }
    }

    /** HTML 正文 → 段落列表：块级/换行标签转换行，剥除其余标签，解码常用实体。 */
    private fun htmlToParagraphs(html: String): List<String> {
        var s = html ?: return emptyList()
        s = Regex("(?i)<(br|/p|/div|/h[1-6]|/li|/section)\\s*/?>").replace(s, "\n")
        s = Regex("(?i)<img[^>]*>").replace(s, "[图片]\n")
        s = Regex("(?i)<[^>]+>").replace(s, "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&ldquo;", "“").replace("&rdquo;", "”")
            .replace("&hellip;", "…").replace("&mdash;", "—")
        val out = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return if (out.isEmpty()) listOf("") else out
    }

    /** 主题 → (背景色, 前景色)。 */
    private fun themeColors(): Pair<Color, Color> = when (ui.theme) {
        "sepia" -> Pair(Color(0xFF241B12), Color(0xFFD8C9A8))
        "night" -> Pair(Color(0xFF050505), Color(0xFF8A8A8A))
        else -> Pair(Color(0xFF141414), Color(0xFFC9C9C9))
    }

    @Composable
    private fun SourceDialog(
        sources: List<BookSource>,
        importing: Boolean,
        onClose: () -> Unit,
        onImport: (String) -> Unit,
        onToggle: (String) -> Unit,
        onRemove: (String) -> Unit,
    ) {
        var url by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                Text("书源管理", fontSize = 16.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp))
                Text("支持粘贴 legado bookSource JSON（单个或数组），或书源下载链接", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    itemsIndexed(sources) { _, s ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (s.enabled) "●" else "○", fontSize = 13.sp,
                                color = if (s.enabled) MaterialTheme.colorScheme.primary else Color(0xFF555555),
                                modifier = Modifier.clickable { onToggle(s.url) })
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontSize = 14.sp, color = if (s.enabled) Color(0xFFDDDDDD) else Color(0xFF777777), maxLines = 1)
                                Text(s.url, fontSize = 10.sp, color = Color(0xFF666666), maxLines = 1)
                            }
                            Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80),
                                modifier = Modifier.clickable { onRemove(s.url) }.padding(6.dp))
                        }
                        HorizontalDivider(color = Color(0x16FFFFFF))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("粘贴书源 JSON 或链接", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 3,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onImport(url) }, enabled = !importing) { Text(if (importing) "导入中…" else "导入", fontSize = 13.sp) }
                }
                Text("点击下方关闭", fontSize = 12.sp, color = Color(0xFF555555), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() }.padding(vertical = 10.dp))
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
                        for ((key, label) in listOf("dark" to "深色", "sepia" to "护眼", "night" to "夜间")) {
                            val selected = theme == key
                            Text(
                                label,
                                fontSize = 14.sp,
                                color = if (selected) Color(0xFF141414) else Color(0xFFCCCCCC),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                    .clickable { onTheme(key) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text("点击下方关闭", fontSize = 12.sp, color = Color(0xFF555555), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onClose() }.padding(vertical = 10.dp))
            }
        }
    }

    /** 封面桥：优先 Glide 加载封面，无封面/失败时用色块 + 书名首字占位。 */
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