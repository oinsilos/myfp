package com.fongmi.android.tv.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.fongmi.android.tv.reader.Book
import com.fongmi.android.tv.reader.BookSource
import com.fongmi.android.tv.reader.ReaderRepository
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.utils.Notify

/**
 * 阅读（书源）界面：搜索 → 书籍详情/目录 → WebView 正文阅读。
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
    }

    private val ui = UiState()
    /** 当前章内滚动进度（0~1，WebView 回调实时刷新，退出阅读/切章时落盘）。 */
    private var currentPercent = 0f
    /** 待恢复的章内滚动位置（openChapter 从进度库读到后置值；WebView 加载完消费后复位 -1）。 */
    private var restorePercent = -1f
    /** 搜索兜底：底层（书源网络/规则求值）无论怎样，25s 内必须结束搜索态，绝不无限转圈。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val SEARCH_TIMEOUT_MS = 25_000L

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
                    onOpenBookmark = { openChapter(it) },
                    onRemoveBookmark = ::removeBookmark,
                    onRemoveShelf = { url ->
                        ReaderStore.get().removeFromShelf(url)
                        refreshShelves()
                        Notify.show("已移出书架")
                    },
                    onOpenSources = { ui.sourceDialogVisible = true },
                    onImport = { importSource(it) },
                    onToggleSource = { toggleSource(it) },
                    onRemoveSource = { removeSource(it) },
                )
            }
        }
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

    private fun openChapter(index: Int) {
        val book = ui.book ?: return
        if (index < 0 || index >= book.chapters.size) return
        if (ui.reading) saveProgress()
        ui.chapterIndex = index
        ui.reading = true
        currentPercent = 0f
        ui.contentLoading = true
        ui.contentError = ""
        ui.content = ""
        // 断点续读：该章有保存过位置则 WebView 加载完恢复
        val p = ReaderStore.get().progress(book.url)
        restorePercent = if (p != null && p.chapter == index) p.percent else -1f
        ReaderRepository.get().chapter(book.chapters[index].url, book.source).whenComplete { html, e ->
            runOnUiThread {
                ui.contentLoading = false
                if (e != null || html == null) {
                    ui.contentError = "正文加载失败：" + friendly(e ?: Exception("空正文"))
                } else {
                    ui.content = html
                }
            }
        }
    }

    /** 退出阅读页（返回目录）：落盘当前章内进度。 */
    private fun leaveReader() {
        saveProgress()
        ui.reading = false
    }

    private fun saveProgress() {
        val book = ui.book ?: return
        ReaderStore.get().saveProgress(book.url, ui.chapterIndex, currentPercent)
    }

    private fun refreshShelves() {
        ui.shelves = ReaderStore.get().shelf()
    }

    private fun refreshBookmarks() {
        ui.bookmarks = ui.book?.let { ReaderStore.get().bookmarks(it.url) } ?: emptyList()
    }

    /** 收藏/移出书架（未收藏则进架，已收藏则移出）。 */
    private fun toggleShelf(book: Book) {
        val store = ReaderStore.get()
        val book0 = ui.book ?: return
        if (store.inShelf(book0.url)) {
            store.removeFromShelf(book0.url)
            Notify.show("已移出书架")
        } else {
            store.addToShelf(book0)
            Notify.show("已加入书架")
        }
        refreshShelves()
    }

    /** 阅读页快捷加书签：记录当前章节 + 章内进度。 */
    private fun addBookmark() {
        val book = ui.book ?: return
        val chapterName = book.chapters.getOrNull(ui.chapterIndex)?.name ?: ""
        ReaderStore.get().addBookmark(book.url, ui.chapterIndex, chapterName, currentPercent)
        refreshBookmarks()
        Notify.show("已添加书签：${chapterName.ifEmpty { "第${ui.chapterIndex + 1}章" }}")
    }

    /** 删除某书签（按章节号）。 */
    private fun removeBookmark(chapter: Int) {
        val book = ui.book ?: return
        ReaderStore.get().removeBookmark(book.url, chapter)
        refreshBookmarks()
    }

    /** 书架/进度/书签 复用的进度文案。 */
    private fun progressText(url: String, chapters: Int): String {
        val p = ReaderStore.get().progress(url) ?: return ""
        return "读到第 ${p.chapter + 1} 章 · ${(p.percent * 100).toInt()}%"
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
        onToggleShelf: (Book) -> Unit,
        onOpenShelf: () -> Unit,
        onBackFromShelf: () -> Unit,
        onAddBookmark: () -> Unit,
        onOpenBookmark: (Int) -> Unit,
        onRemoveBookmark: (Int) -> Unit,
        onRemoveShelf: (String) -> Unit,
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
                    )
                } else if (currentBook != null) {
                    BookBar(
                        book = currentBook,
                        onBack = onBackFromBook,
                        inShelf = ReaderStore.get().inShelf(currentBook.url),
                        onToggleShelf = { onToggleShelf(currentBook) },
                    )
                } else if (ui.shelfMode) {
                    ShelfBar(count = ui.shelves.size, onBack = onBackFromShelf, onOpenSources = onOpenSources)
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
                        baseUrl = ui.book?.chapters?.getOrNull(ui.chapterIndex)?.url,
                    )
                } else if (currentBook != null) {
                    BookBody(
                        book = currentBook,
                        loading = ui.detailLoading,
                        error = ui.detailError,
                        inShelf = ReaderStore.get().inShelf(currentBook.url),
                        bookmarks = ui.bookmarks,
                        onOpenChapter = onOpenChapter,
                        onToggleShelf = { onToggleShelf(currentBook) },
                        onOpenBookmark = onOpenBookmark,
                        onRemoveBookmark = onRemoveBookmark,
                    )
                } else if (ui.shelfMode) {
                    ShelfBody(
                        shelves = ui.shelves,
                        onOpenBook = onOpenShelfBook,
                        onRemove = onRemoveShelf,
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

    /** 书架顶栏：返回搜索 + 标题；右侧保留书源入口。 */
    @Composable
    private fun ShelfBar(count: Int, onBack: () -> Unit, onOpenSources: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 搜索", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("书架 ($count)", fontSize = 15.sp, color = Color(0xFFE0E0E0))
            Spacer(Modifier.weight(1f))
            Text("书源", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenSources).padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }

    /** 书架列表：点击续读（自动跳到上次读到的章节），行尾可移除。 */
    @Composable
    private fun ShelfBody(shelves: List<Book>, onOpenBook: (Book) -> Unit, onRemove: (String) -> Unit) {
        if (shelves.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("书架空空如也：从搜索结果点开书籍 → 右上角收藏", fontSize = 13.sp, color = Color(0xFF666666))
            }
            return
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(shelves) { _, book ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenBook(book) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(book.name, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
                        Text(
                            listOfNotNull(book.author.takeIf { it.isNotEmpty() }, sourceName(book.source), progressText(book.url, -1).takeIf { it.isNotEmpty() })
                                .joinToString(" · "),
                            fontSize = 12.sp,
                            color = Color(0xFF888888),
                            maxLines = 1,
                        )
                    }
                    Text("移出", fontSize = 12.sp, color = Color(0xFFFF8A80),
                        modifier = Modifier.clickable { onRemove(book.url) }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                HorizontalDivider(color = Color(0x14FFFFFF))
            }
        }
    }

    @Composable
    private fun BookBar(book: Book, onBack: () -> Unit, inShelf: Boolean, onToggleShelf: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(book.name, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                if (inShelf) "✓ 在书架" else "＋ 书架",
                fontSize = 13.sp,
                color = if (inShelf) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onToggleShelf).padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }

    @Composable
    private fun BookBody(
        book: Book,
        loading: Boolean,
        error: String,
        inShelf: Boolean,
        bookmarks: List<ReaderStore.Bookmark>,
        onOpenChapter: (Int) -> Unit,
        onToggleShelf: () -> Unit,
        onOpenBookmark: (Int) -> Unit,
        onRemoveBookmark: (Int) -> Unit,
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
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(book.name, fontSize = 17.sp, color = Color(0xFFF0F0F0))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (book.author.isNotEmpty()) {
                            Text(book.author, fontSize = 12.sp, color = Color(0xFF999999), modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (inShelf) "✓ 已收藏" else "＋ 收藏到书架",
                            fontSize = 12.sp,
                            color = if (inShelf) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onToggleShelf).padding(horizontal = 4.dp),
                        )
                    }
                    if (book.intro.isNotEmpty()) {
                        Text(book.intro, fontSize = 12.sp, color = Color(0xFFAAAAAA), lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    // 断点续读：显示上次读到哪，一键继续
                    if (progress != null && progress.chapter in 0 until book.chapters.size) {
                        Text(
                            "继续阅读：${book.chapters[progress.chapter].name}（${(progress.percent * 100).toInt()}%）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenChapter(progress.chapter) }
                                .padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    // 书签列表
                    if (bookmarks.isNotEmpty()) {
                        Text("书签 (${bookmarks.size})", fontSize = 13.sp, color = Color(0xFFCCCCCC),
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        bookmarks.forEach { bm ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${book.chapters.getOrNull(bm.chapter)?.name?.takeIf { it.isNotEmpty() } ?: "第${bm.chapter + 1}章"}（${(bm.percent * 100).toInt()}%）",
                                    fontSize = 12.sp,
                                    color = Color(0xFF9E9E9E),
                                    modifier = Modifier.weight(1f).clickable { onOpenBookmark(bm.chapter) }
                                        .padding(vertical = 4.dp),
                                    maxLines = 1,
                                )
                                Text("×", fontSize = 14.sp, color = Color(0xFFFF8A80),
                                    modifier = Modifier.clickable { onRemoveBookmark(bm.chapter) }.padding(horizontal = 8.dp))
                            }
                        }
                    }
                    Text("目录（${book.chapters.size} 章）", fontSize = 13.sp, color = Color(0xFFCCCCCC),
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
            }
            itemsIndexed(book.chapters) { index, chapter ->
                Text(
                    chapter.name,
                    fontSize = 13.sp,
                    color = Color(0xFFBBBBBB),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenChapter(index) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
                HorizontalDivider(color = Color(0x10FFFFFF))
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
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 目录", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(chapterName, fontSize = 14.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("☆ 书签", fontSize = 13.sp, color = Color(0xFFFFD54F),
                modifier = Modifier.clickable(onClick = onAddBookmark).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("上一章", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onPrev).padding(horizontal = 6.dp, vertical = 6.dp))
            Text("下一章", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onNext).padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun ReaderBody(loading: Boolean, content: String, error: String, baseUrl: String?) {
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
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(0xFF141414.toInt())
                    webChromeClient = WebChromeClient()
                    // 断点续读：新章节加载完成即恢复到上次滚动位置（一次性消费 restorePercent）
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (restorePercent >= 0f) {
                                val rp = restorePercent
                                restorePercent = -1f
                                view?.post {
                                    val max = (view.contentHeight - view.height).coerceAtLeast(0)
                                    view.scrollTo(0, (rp * max).toInt())
                                }
                            }
                        }
                    }
                    settings.javaScriptEnabled = false
                    settings.defaultTextEncodingName = "utf-8"
                    // 章内滚动进度实时跟踪（保存断点/书签用）
                    setOnScrollChangeListener { _, scrollY, _, _, _ ->
                        val max = contentHeight - height
                        currentPercent = if (max <= 0) 0f else (scrollY.toFloat() / max).coerceIn(0f, 1f)
                    }
                }
            },
            update = { web ->
                if (web.tag != content.hashCode()) {
                    web.tag = content.hashCode()
                    val html = "<html><head><meta charset=\"utf-8\"><style>" +
                            "body{background:#141414;color:#C9C9C9;font-size:17px;line-height:1.9;" +
                            "padding:4px 14px 24px;word-wrap:break-word;overflow-wrap:break-word;" +
                            "white-space:pre-wrap;}" +
                            "p{margin:6px 0;}img{max-width:100%;height:auto;}h1,h2,h3{color:#F0F0F0;}" +
                            "a{color:#4FC3F7;}</style></head><body>" + content + "</body></html>"
                    web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            },
        )
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
}