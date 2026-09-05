package com.fongmi.android.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.fongmi.android.tv.music.core.MusicLibrary
import com.fongmi.android.tv.reader.ReaderRepository
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.reader.RssRepository

import org.json.JSONObject

/** 全模块统一备份：阅读库 + 书源 + 订阅源 + 音乐本地库合并为单一 JSON（备份/恢复只此一份）。 */
object UnifiedBackup {

    public fun export(): String {
        val root = JSONObject()
        root.put("app", "TV-fongmi 统一备份")
        root.put("reader", JSONObject(ReaderStore.get().exportJson()))
        root.put("sources", JSONObject().put("items", ReaderRepository.get().exportSources()).toString())
        root.put("rss_sources", RssRepository.get().exportSources())
        root.put("rss_favorites", RssRepository.get().exportFavorites())
        root.put("music", JSONObject(MusicLibrary.get().exportJson()))
        return root.toString(2)
    }

    /** 恢复统一备份（兼容旧版平铺备份：旧的 reader 数据在顶层也能恢复）。 */
    public fun import(text: String): Boolean {
        if (text == null || text.isBlank()) return false
        val root = JSONObject(text)
        var ok = false
        // 兼容旧版平铺备份（shelf 等在顶层）
        try {
            ok = ReaderStore.get().importJson(text) || ok
        } catch (_: Exception) {
        }
        root.optJSONObject("reader")?.let {
            ok = ReaderStore.get().importJson(it.toString()) || ok
        }
        root.optString("sources", "").let { srcs ->
            if (srcs.isNotBlank()) {
                try {
                    val items = JSONObject(srcs).optString("items", "")
                    if (items.isNotBlank()) ok = ReaderRepository.get().importSources(items) || ok
                } catch (_: Exception) {
                }
            }
        }
        val rss = root.optString("rss_sources", "")
        if (rss.isNotBlank()) ok = RssRepository.get().importSources(rss) || ok
        val rfav = root.optString("rss_favorites", "")
        if (rfav.isNotBlank()) ok = RssRepository.get().importFavorites(rfav) || ok
        root.optJSONObject("music")?.let {
            ok = MusicLibrary.get().importJson(it.toString()) || ok
        }
        return ok
    }
}

/**
 * 全模块统一设置弹窗（音乐 / 小说 / 底部设置 tab 共用同一份内容）：
 * - 主题/皮肤（深色/护眼/夜间）+ 阅读字号/行距（改动即生效并持久化）
 * - 备份/恢复（单一 JSON 覆盖 书架/进度/书签/设置/书源/订阅源/音乐收藏歌单）
 * - 说明：源连通性测试不放在这里，保留在各板块内（书源/订阅源/音乐插件源）
 */
@Composable
public fun UnifiedSettingsDialog(
    theme: String,
    fontSize: Int,
    lineHeight: Float,
    onTheme: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        UnifiedSettingsPage(
            theme = theme,
            fontSize = fontSize,
            lineHeight = lineHeight,
            onTheme = onTheme,
            onFontSize = onFontSize,
            onLineHeight = onLineHeight,
            onExport = onExport,
            onImport = onImport,
            onClose = onClose,
        )
    }
}

/** 统一设置内容页（非弹窗版，供底部「设置」tab 直接铺成页面；弹窗版复用它）。 */
@Composable
public fun UnifiedSettingsPage(
    theme: String,
    fontSize: Int,
    lineHeight: Float,
    onTheme: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    var sliderValue by remember { mutableStateOf(fontSize.toFloat()) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF141414))
            .verticalScroll(rememberScrollState()).padding(horizontal = 30.dp),
    ) {
        Text("统一设置", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 14.dp))
        Text("视频 · 音乐 · 小说 三板块共用：主题/皮肤 与 备份/恢复", fontSize = 11.sp,
            color = Color(0xFF888888), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        // 主题 / 皮肤
        Text("主题 / 皮肤", fontSize = 14.sp, color = Color(0xFFDDDDDD),
            modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for ((key, label) in listOf("dark" to "深色", "sepia" to "护眼", "night" to "夜间")) {
                val sel = theme == key
                Text(
                    label,
                    fontSize = 14.sp,
                    color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                        .clickable { onTheme(key) }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 12.dp))
        // 阅读字号 / 行距
        Text("阅读字号  ${fontSize}sp", fontSize = 14.sp, color = Color(0xFFDDDDDD))
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onFontSize(sliderValue.toInt()) },
            valueRange = 12f..30f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("行距", fontSize = 14.sp, color = Color(0xFFDDDDDD))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for (opt in listOf(1.5f, 1.8f, 2.0f, 2.4f)) {
                val sel = Math.abs(lineHeight - opt) < 0.01f
                Text(
                    if (opt == opt.toInt().toFloat()) opt.toInt().toString() else opt.toString(),
                    fontSize = 14.sp,
                    color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                        .clickable { onLineHeight(opt) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 12.dp))
        // 备份 / 恢复
        Text("备份 / 恢复", fontSize = 14.sp, color = Color(0xFFDDDDDD))
        Text("单一 JSON 覆盖书架、进度、书签、阅读设置、主题、书源、订阅源、音乐收藏/最近/歌单",
            fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 4.dp))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExport)
                .padding(top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("导出备份", fontSize = 15.sp, color = Color(0xFFE0E0E0))
            Spacer(Modifier.weight(1f))
            Text("保存 .json", fontSize = 12.sp, color = Color(0xFF888888))
            Spacer(Modifier.width(10.dp))
            Text("导出", fontSize = 13.sp, color = Color(0xFF4FC3F7))
        }
        HorizontalDivider(color = Color(0x16FFFFFF))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onImport)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("从备份恢复", fontSize = 15.sp, color = Color(0xFFE0E0E0))
            Spacer(Modifier.weight(1f))
            Text("选择 .json", fontSize = 12.sp, color = Color(0xFF888888))
            Spacer(Modifier.width(10.dp))
            Text("恢复", fontSize = 13.sp, color = Color(0xFF4FC3F7))
        }
        HorizontalDivider(color = Color(0x16FFFFFF))
        // 源连通性测试说明（不并入统一设置）
        Text("源连通性测试保留在各板块内：小说 → 书源/订阅源管理；音乐 → 插件源管理",
            fontSize = 11.sp, color = Color(0xFF777777), modifier = Modifier.padding(top = 12.dp))
        Text("点击下方关闭", fontSize = 12.sp, color = Color(0xFF555555), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClose).padding(vertical = 16.dp))
    }
}