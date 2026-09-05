package com.fongmi.android.tv.ui.compose

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.fongmi.android.tv.App
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Config
import com.fongmi.android.tv.bean.History
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.bean.Word
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.model.SearchBox
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.activity.FolderActivity
import com.fongmi.android.tv.ui.activity.SearchActivity
import com.fongmi.android.tv.ui.activity.VideoActivity
import com.fongmi.android.tv.ui.dialog.SyncDialog
import com.fongmi.android.tv.utils.Notify
import com.github.catvod.net.OkHttp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

private val Bg = Color(0xFF141414)
private val Card = Color(0xFF1E1E1E)
private val Accent = Color(0xFF4FC3F7)

@Composable
fun VodPageContainer(page: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent,
        background = Bg,
        surface = Card,
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        secondary = Color(0xFF999999),
    )) {
        page()
    }
}

/** Java Activity 注入入口（onBack 用 Runnable，避免 Java lambda 与 kotlin.Unit 的兼容问题）。 */
fun attachVodSearchPage(view: ComposeView, activity: ComponentActivity, keyword: String?, onBack: Runnable) {
    view.setContent { VodPageContainer { VodSearchPage(activity, keyword) { onBack.run() } } }
}

fun attachVodKeepPage(view: ComposeView, activity: ComponentActivity, onBack: Runnable) {
    view.setContent { VodPageContainer { VodKeepPage(activity) { onBack.run() } } }
}

fun attachVodHistoryPage(view: ComposeView, activity: ComponentActivity, onBack: Runnable) {
    view.setContent { VodPageContainer { VodHistoryPage(activity) { onBack.run() } } }
}

/** 竖版影片封面：Glide 异步加载（限解码尺寸 + 超时），失败用色块 + 首字占位。 */
@Composable
private fun VodCover(url: String?, name: String, modifier: Modifier = Modifier, pixels: Int = 320) {
    val context = LocalContext.current
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty()) {
            bmp = null
            return@LaunchedEffect
        }
        bmp = withContext(Dispatchers.IO) {
            try {
                Glide.with(context).asBitmap()
                    .apply(RequestOptions().override(pixels, pixels).timeout(8000))
                    .load(url).submit().get()
            } catch (e: Exception) {
                null
            }
        }
    }
    val image = bmp
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = name,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
            Text(name.take(1).ifEmpty { "影" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF555555))
        }
    }
}

/** 影片卡片：海报 + 片名 + 备注 +（可选进度条）；用于网格，支持长按进入删除模式。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VodCard(
    name: String,
    pic: String?,
    remark: String,
    progress: Float?,
    delete: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.padding(6.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            VodCover(pic, name, Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)), pixels = 420)
            if (progress != null && progress in 0.001f..0.99f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                )
            }
            if (delete) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFFE5484D)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 11.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, fontSize = 13.sp, color = Color(0xFFE0E0E0), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (remark.isNotBlank()) {
            Text(remark, fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PageTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Bg).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹ 返回", fontSize = 14.sp, color = Accent,
            modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 6.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
private fun TopAction(text: String, color: Color = Accent, onClick: () -> Unit) {
    Text(text, fontSize = 13.sp, color = color,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        color = if (selected) Color(0xFF141414) else Color(0xFFCCCCCC),
        modifier = Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent else Color(0x26FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
    Spacer(Modifier.width(6.dp))
}

/** 网格列数：横屏 5 / 小平板 4 / 竖屏 3。 */
@Composable
private fun gridCols(): Int {
    val c = LocalContext.current
    return when {
        c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE -> 5
        c.resources.configuration.screenWidthDp >= 600 -> 4
        else -> 3
    }
}

// ==================== 搜索 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VodSearchPage(activity: ComponentActivity, keyword: String?, onBack: () -> Unit) {
    var kw by remember { mutableStateOf(keyword ?: "") }
    var searched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var records by remember { mutableStateOf(loadRecords()) }
    var bySite by remember { mutableStateOf(linkedMapOf<String, MutableList<Vod>>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val sites = remember { VodConfig.get().getSites().filter { it.isSearchable } }
    val siteNames = remember(sites) { sites.associate { it.getKey() to it.getName() } }

    fun runSearch() {
        val text = kw.trim()
        if (text.isEmpty()) return
        putRecord(text)
        records = loadRecords()
        bySite = linkedMapOf()
        selected = null
        searched = true
        busy = true
        val pending = arrayOf(sites.size)
        SearchBox.start(sites, text) { result ->
            val site = result.getVod()?.getSite() ?: return@start
            val key = site.getKey()
            handler.post {
                bySite.getOrPut(key) { mutableListOf() }.addAll(result.getList())
                bySite = LinkedHashMap(bySite) // 触发重组
                if (pending[0] > 0) pending[0]--
                if (pending[0] == 0) busy = false
            }
        }
        handler.postDelayed({ busy = false }, 8000)
    }

    LaunchedEffect(Unit) {
        val cached = Setting.getHot()
        if (cached.isNotEmpty() && words.isEmpty()) {
            words = Word.objectFrom(cached).getData().map { it.getTitle() }
        }
        try {
            val body = OkHttp.newCall("https://api.web.360kan.com/v1/rank?cat=1")
                .execute().body?.string()
            if (!body.isNullOrEmpty()) {
                val data = Word.objectFrom(body).getData().map { it.getTitle() }
                if (data.isNotEmpty()) words = data
                Setting.putHot(body)
            }
        } catch (e: Exception) {
        }
    }
    DisposableEffect(Unit) {
        onDispose { SearchBox.stop() }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        PageTopBar("搜索", onBack = onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = kw,
                onValueChange = { kw = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("影片名 / 关键词", color = Color(0xFF666666), fontSize = 14.sp) },
                maxLines = 1,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { runSearch() }, enabled = kw.isNotBlank()) { Text("搜索", fontSize = 14.sp) }
        }
        when {
            !searched -> {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    if (records.isNotEmpty()) {
                        Text("搜索历史", fontSize = 13.sp, color = Color(0xFF999999),
                            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                        FlowRow(Modifier.padding(horizontal = 12.dp)) {
                            records.forEach { r -> Chip(r, onClick = { kw = r; runSearch() }) }
                        }
                    }
                    Text("热门", fontSize = 13.sp, color = Color(0xFF999999),
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                    FlowRow(Modifier.padding(horizontal = 12.dp)) {
                        words.forEach { w -> Chip(w, onClick = { kw = w; runSearch() }) }
                    }
                }
            }
            bySite.isEmpty() && !busy -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无结果", fontSize = 13.sp, color = Color(0xFF666666))
                }
            }
            else -> {
                if (bySite.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Chip("全部 ${bySite.values.sumOf { it.size }}", selected = selected == null) { selected = null }
                        bySite.keys.forEach { key ->
                            Chip("${siteNames[key].orEmpty()} ${bySite[key]?.size ?: 0}", selected = selected == key) { selected = key }
                        }
                    }
                }
                val shown = if (selected == null) bySite.values.flatten().distinctBy { it.getId() } else bySite[selected].orEmpty()
                if (shown.isEmpty() && busy) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                } else if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该站点无结果", fontSize = 13.sp, color = Color(0xFF666666))
                    }
                } else {
                    LazyVerticalGrid(GridCells.Fixed(gridCols()), Modifier.fillMaxSize()) {
                        items(shown) { v ->
                            VodCard(
                                name = v.getName(),
                                pic = v.getPic().ifEmpty { null },
                                remark = v.getRemarks(),
                                progress = null,
                                delete = false,
                                onClick = { openVod(activity, v) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadRecords(): List<String> {
    val json = Setting.getKeyword()
    if (json.isEmpty()) return emptyList()
    return try {
        val list = App.gson().fromJson(json, List::class.java) as? List<*>
        list?.mapNotNull { it as? String } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun putRecord(text: String) {
    val list = loadRecords().toMutableList()
    list.remove(text)
    list.add(0, text)
    if (list.size > 10) list.removeAt(list.size - 1)
    Setting.putKeyword(App.gson().toJson(list))
}

private fun openVod(activity: ComponentActivity, v: Vod) {
    if (v.isFolder()) FolderActivity.start(activity, v.getSiteKey(), Result.folder(v))
    else VideoActivity.collect(activity, v.getSiteKey(), v.getId(), v.getName(), v.getPic())
}

// ==================== 收藏 ====================

@Composable
fun VodKeepPage(activity: ComponentActivity, onBack: () -> Unit) {
    var items by remember { mutableStateOf(Keep.getVod()) }
    var deleteMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun reload() {
        items = Keep.getVod()
        if (items.isEmpty()) deleteMode = false
    }
    val bus = remember {
        object : Any() {
            @Subscribe(threadMode = ThreadMode.MAIN)
            fun onRefresh(event: RefreshEvent) {
                if (event.getType() == RefreshEvent.Type.KEEP) reload()
            }
        }
    }
    DisposableEffect(Unit) {
        EventBus.getDefault().register(bus)
        onDispose { EventBus.getDefault().unregister(bus) }
    }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize().background(Bg)) {
        PageTopBar("收藏", onBack = onBack) {
            TopAction("同步") { (activity as? FragmentActivity)?.let { SyncDialog.create().keep()?.show(it) } }
            TopAction(if (deleteMode) "完成" else "删除") {
                deleteMode = !deleteMode
                if (!deleteMode) reload()
            }
            TopAction("清空") {
                if (items.isEmpty()) return@TopAction
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dialog_delete_record)
                    .setMessage(R.string.dialog_delete_keep)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive) { _, _ -> Keep.deleteAll(); reload() }
                    .show()
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无收藏", fontSize = 13.sp, color = Color(0xFF666666))
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(gridCols()), Modifier.fillMaxSize()) {
                items(items) { keep ->
                    VodCard(
                        name = keep.getVodName(),
                        pic = keep.getVodPic().ifEmpty { null },
                        remark = keep.getSiteName(),
                        progress = null,
                        delete = deleteMode,
                        onClick = {
                            if (deleteMode) {
                                keep.delete()
                                reload()
                            } else {
                                openKeep(activity, keep)
                            }
                        },
                        onLongClick = { deleteMode = true },
                    )
                }
            }
        }
    }
}

private fun openKeep(activity: ComponentActivity, item: Keep) {
    val config = Config.find(item.getCid())
    if (config == null) {
        SearchActivity.start(activity, item.getVodName())
    } else if (item.getCid() != VodConfig.getCid()) {
        VodConfig.load(config, object : Callback() {
            override fun success() {
                VideoActivity.start(activity, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic())
            }

            override fun error(msg: String) {
                Notify.show(msg)
            }
        })
    } else {
        VideoActivity.start(activity, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic())
    }
}

// ==================== 历史 ====================

@Composable
fun VodHistoryPage(activity: ComponentActivity, onBack: () -> Unit) {
    var items by remember { mutableStateOf(History.get()) }
    var deleteMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun reload() {
        items = History.get()
        if (items.isEmpty()) deleteMode = false
    }
    val bus = remember {
        object : Any() {
            @Subscribe(threadMode = ThreadMode.MAIN)
            fun onRefresh(event: RefreshEvent) {
                if (event.getType() == RefreshEvent.Type.HISTORY) reload()
            }
        }
    }
    DisposableEffect(Unit) {
        EventBus.getDefault().register(bus)
        onDispose { EventBus.getDefault().unregister(bus) }
    }
    LaunchedEffect(Unit) { reload() }
    val continues = items.filter { it.isContinue() }
    val others = items.filterNot { it.isContinue() }
    Column(Modifier.fillMaxSize().background(Bg)) {
        PageTopBar("最近观看", onBack = onBack) {
            TopAction("同步") { (activity as? FragmentActivity)?.let { SyncDialog.create().history()?.show(it) } }
            TopAction(if (deleteMode) "完成" else "删除") {
                deleteMode = !deleteMode
                if (!deleteMode) reload()
            }
            TopAction("清空") {
                if (items.isEmpty()) return@TopAction
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dialog_delete_record)
                    .setMessage(R.string.dialog_delete_history)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive) { _, _ -> History.clear(VodConfig.getCid()); reload() }
                    .show()
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无观看记录", fontSize = 13.sp, color = Color(0xFF666666))
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(gridCols()), Modifier.fillMaxSize()) {
                if (continues.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("继续观看", fontSize = 14.sp, color = Color(0xFFDDDDDD),
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            items(continues) { h ->
                                ContinueCard(h, deleteMode) {
                                    if (deleteMode) {
                                        h.delete()
                                        reload()
                                    } else {
                                        VideoActivity.start(activity, h.getSiteKey(), h.getVodId(), h.getVodName(), h.getVodPic())
                                    }
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("最近观看", fontSize = 14.sp, color = Color(0xFFDDDDDD),
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
                }
                items(others) { h ->
                    VodCard(
                        name = h.getVodName(),
                        pic = h.getVodPic().ifEmpty { null },
                        remark = h.getVodRemarks(),
                        progress = h.progressRatio(),
                        delete = deleteMode,
                        onClick = {
                            if (deleteMode) {
                                h.delete()
                                reload()
                            } else {
                                VideoActivity.start(activity, h.getSiteKey(), h.getVodId(), h.getVodName(), h.getVodPic())
                            }
                        },
                        onLongClick = { deleteMode = true },
                    )
                }
            }
        }
    }
}

/** 继续观看卡片：横版封面 + 片名 + 进度条 + 备注。 */
@Composable
private fun ContinueCard(h: History, delete: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(168.dp).padding(6.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            VodCover(h.getVodPic().ifEmpty { null }, h.getVodName(),
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
            if (delete) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFFE5484D)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 11.sp, color = Color.White)
                }
            }
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("继续播放", fontSize = 11.sp, color = Accent)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(h.getVodName(), fontSize = 13.sp, color = Color(0xFFE0E0E0), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val ratio = h.progressRatio()
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(h.getVodRemarks(), fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 是否属于「继续观看」：有位置、有总时长、未看完也没刚开播。 */
private fun History.isContinue(): Boolean {
    return getPosition() > 0 && getDuration() > 0 && progressRatio() in 0.02f..0.98f
}

private fun History.progressRatio(): Float {
    if (getDuration() <= 0 || getPosition() <= 0) return 0f
    return (getPosition().toFloat() / getDuration().toFloat()).coerceIn(0f, 1f)
}