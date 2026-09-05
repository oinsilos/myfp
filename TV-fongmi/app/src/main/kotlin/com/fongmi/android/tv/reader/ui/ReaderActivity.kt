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
    }

    private val ui = UiState()
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
        refreshSources()
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
                    onOpenBook = { openBook(it) },
                    onBackFromBook = { ui.book = null; ui.reading = false },
                    onOpenChapter = { openChapter(it) },
                    onPrev = { step(-1) },
                    onNext = { step(1) },
                    onBackFromReader = { ui.reading = false },
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

    private fun openBook(book: Book) {
        ui.book = book
        ui.reading = false
        ui.detailLoading = true
        ui.detailError = ""
        ReaderRepository.get().detail(book).whenComplete { b, e1 ->
            val bd = b ?: book
            ReaderRepository.get().toc(bd).whenComplete { tb, e2 ->
                runOnUiThread {
                    ui.detailLoading = false
                    ui.book = tb ?: bd
                    val err: Throwable? = (e2 as? Throwable) ?: (e1 as? Throwable)
                    if (err != null) ui.detailError = "详情/目录失败：" + friendly(err)
                }
            }
        }
    }

    private fun openChapter(index: Int) {
        val book = ui.book ?: return
        if (index < 0 || index >= book.chapters.size) return
        ui.chapterIndex = index
        ui.reading = true
        ui.contentLoading = true
        ui.contentError = ""
        ui.content = ""
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
        onBackFromBook: () -> Unit,
        onOpenChapter: (Int) -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onBackFromReader: () -> Unit,
        onOpenSources: () -> Unit,
        onImport: (String) -> Unit,
        onToggleSource: (String) -> Unit,
        onRemoveSource: (String) -> Unit,
    ) {
        var keyword by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                // 顶栏：书源入口 + 阅读态返回
                if (ui.reading) {
                    ReaderBar(
                        chapterName = ui.book?.chapters?.getOrNull(ui.chapterIndex)?.name ?: "阅读",
                        onBack = onBackFromReader,
                        onPrev = onPrev,
                        onNext = onNext,
                    )
                } else if (ui.book != null) {
                    BookBar(book = ui.book!!, onBack = onBackFromBook)
                } else {
                    SearchTop(
                        keyword = keyword,
                        onKeyword = { keyword = it },
                        onSearch = { onSearch(keyword) },
                        sources = ui.sources,
                        onOpenSources = onOpenSources,
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
                } else if (ui.book != null) {
                    BookBody(
                        book = ui.book!!,
                        loading = ui.detailLoading,
                        error = ui.detailError,
                        onOpenChapter = onOpenChapter,
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
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "书源 ${enabledCount(sources)} 个 ▾",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenSources).padding(horizontal = 6.dp, vertical = 4.dp),
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

    @Composable
    private fun BookBar(book: Book, onBack: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(book.name, fontSize = 15.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(52.dp))
        }
    }

    @Composable
    private fun BookBody(book: Book, loading: Boolean, error: String, onOpenChapter: (Int) -> Unit) {
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
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(book.name, fontSize = 17.sp, color = Color(0xFFF0F0F0))
                    if (book.author.isNotEmpty()) {
                        Text(book.author, fontSize = 12.sp, color = Color(0xFF999999), modifier = Modifier.padding(top = 2.dp))
                    }
                    if (book.intro.isNotEmpty()) {
                        Text(book.intro, fontSize = 12.sp, color = Color(0xFFAAAAAA), lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 8.dp))
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
    private fun ReaderBar(chapterName: String, onBack: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 目录", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(chapterName, fontSize = 14.sp, color = Color(0xFFE0E0E0), maxLines = 1)
            Spacer(Modifier.weight(1f))
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
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = false
                    settings.defaultTextEncodingName = "utf-8"
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