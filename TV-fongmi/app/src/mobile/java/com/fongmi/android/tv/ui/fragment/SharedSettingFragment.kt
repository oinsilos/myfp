package com.fongmi.android.tv.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.fragment.app.Fragment
import com.fongmi.android.tv.music.core.MusicLibrary
import com.fongmi.android.tv.reader.ReaderRepository
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.reader.RssRepository
import com.fongmi.android.tv.ui.activity.HomeActivity
import com.fongmi.android.tv.ui.common.ThemeStore
import com.fongmi.android.tv.ui.common.UnifiedBackup
import com.fongmi.android.tv.utils.Notify
import java.nio.charset.StandardCharsets

/**
 * 底部「设置」tab：三板块共用的轻设置页。
 * - 顶部三张板块入口卡片：视频设置 / 音乐音源 / 书源管理（一层直达，不再有「设置里的设置」）
 * - 通用：主题/皮肤（全局 ThemeStore，三板块共用；护眼暖色作用于小说正文，影音页恒定深色）
 * - 阅读项仅作用于小说；备份/恢复单一 JSON 覆盖三板块
 * - 源连通性测试不在这里，保留在各板块内（书源/订阅源/音乐插件源）
 */
class SharedSettingFragment : Fragment() {

    private val backupExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportBackup(uri)
    }

    private val backupImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = requireContext()
        ReaderRepository.get().init(ctx)
        ReaderStore.get().init(ctx)
        RssRepository.get().init(ctx)
        MusicLibrary.get().init(ctx)
        ThemeStore.get().init(ctx)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    background = Color(0xFF141414),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color(0xFFE0E0E0),
                    onSurface = Color(0xFFE0E0E0),
                    secondary = Color(0xFF999999),
                )) {
                    SharedSettingContent(
                        onOpenVideoSettings = { (activity as? HomeActivity)?.change(4) },
                        onOpenMusicSources = { (activity as? HomeActivity)?.openMusicSources() },
                        onOpenReadSources = { (activity as? HomeActivity)?.openReadSources() },
                    )
                }
            }
        }
    }

    @Composable
    private fun SharedSettingContent(
        onOpenVideoSettings: () -> Unit,
        onOpenMusicSources: () -> Unit,
        onOpenReadSources: () -> Unit,
    ) {
        val ts = ThemeStore.get()
        var theme by remember { mutableStateOf(ts.theme) }
        var fontSize by remember { mutableStateOf(ReaderStore.get().fontSize) }
        var lineHeight by remember { mutableStateOf(ReaderStore.get().lineHeight) }
        var sliderValue by remember { mutableStateOf(fontSize.toFloat()) }
        Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text("设置", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp))
                Text("视频 · 音乐 · 小说 三板块共用；配置源各自板块内管理", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                // ---- 板块入口卡片：一层直达各板块设置 ----
                Text("板块设置", fontSize = 13.sp, color = Color(0xFF999999),
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                SectionRow("视频设置", "点播 · 播放器 · 弹幕 · 预加载 · 解码", onOpenVideoSettings)
                SectionRow("音乐音源", "插件源导入 · 切换 · 测试", onOpenMusicSources)
                SectionRow("书源管理", "书源导入 · 测试 · 订阅源", onOpenReadSources)
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 16.dp))
                // ---- 通用：主题/皮肤 ----
                Text("主题 / 皮肤", fontSize = 14.sp, color = Color(0xFFDDDDDD))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    for ((key, label) in listOf("dark" to "深色", "sepia" to "护眼", "night" to "夜间")) {
                        val sel = theme == key
                        Text(
                            label,
                            fontSize = 14.sp,
                            color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                .clickable {
                                    ts.theme = key
                                    theme = key
                                }
                                .padding(horizontal = 18.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Text("护眼（暖色）用于小说正文；视频/音乐播放页恒定深色", fontSize = 11.sp,
                    color = Color(0xFF777777), modifier = Modifier.padding(top = 4.dp))
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 16.dp))
                // ---- 阅读（仅小说） ----
                Text("阅读字号（仅小说）  ${fontSize}sp", fontSize = 14.sp, color = Color(0xFFDDDDDD))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        fontSize = sliderValue.toInt()
                        val rs = ReaderStore.get()
                        rs.fontSize = fontSize
                        rs.saveSettings()
                    },
                    valueRange = 12f..30f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("行距（仅小说）", fontSize = 14.sp, color = Color(0xFFDDDDDD))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    for (opt in listOf(1.5f, 1.8f, 2.0f, 2.4f)) {
                        val sel = Math.abs(lineHeight - opt) < 0.01f
                        Text(
                            if (opt == opt.toInt().toFloat()) opt.toInt().toString() else opt.toString(),
                            fontSize = 14.sp,
                            color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                .clickable {
                                    lineHeight = opt
                                    val rs = ReaderStore.get()
                                    rs.lineHeight = opt
                                    rs.saveSettings()
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 16.dp))
                // ---- 数据：备份 / 恢复 ----
                Text("备份 / 恢复", fontSize = 14.sp, color = Color(0xFFDDDDDD))
                Text("单一 JSON 覆盖书架、进度、书签、设置、书源、订阅源、音乐收藏/歌单",
                    fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { backupExportPicker.launch("tv_fongmi_backup.json") }
                        .padding(top = 14.dp, bottom = 10.dp),
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
                    Modifier.fillMaxWidth().clickable { backupImportPicker.launch(arrayOf("*/*")) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("从备份恢复", fontSize = 15.sp, color = Color(0xFFE0E0E0))
                    Spacer(Modifier.weight(1f))
                    Text("选择 .json", fontSize = 12.sp, color = Color(0xFF888888))
                    Spacer(Modifier.width(10.dp))
                    Text("恢复", fontSize = 13.sp, color = Color(0xFF4FC3F7))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    /** 板块入口卡片行：图标位 + 主标题 + 副说明 + "›"。 */
    @Composable
    private fun SectionRow(title: String, sub: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E1E1E)).clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = Color(0xFFE0E0E0))
                Text(sub, fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 3.dp))
            }
            Text("›", fontSize = 18.sp, color = Color(0xFF4FC3F7))
        }
        Spacer(Modifier.height(8.dp))
    }

    private fun exportBackup(uri: Uri) {
        val resolver = requireContext().contentResolver
        Thread {
            try {
                resolver.openOutputStream(uri)?.bufferedWriter(StandardCharsets.UTF_8)?.use {
                    it.write(UnifiedBackup.export())
                }
                requireActivity().runOnUiThread { Notify.show("备份完成（音乐/阅读/订阅/设置）") }
            } catch (e: Exception) {
                requireActivity().runOnUiThread { Notify.show("导出失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    private fun importBackup(uri: Uri) {
        val resolver = requireContext().contentResolver
        Thread {
            try {
                val text = resolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    requireActivity().runOnUiThread { Notify.show("备份文件为空") }
                    return@Thread
                }
                val ok = UnifiedBackup.import(text)
                requireActivity().runOnUiThread {
                    Notify.show(if (ok) "恢复完成（音乐/阅读/订阅）" else "恢复失败：不是有效的备份文件")
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread { Notify.show("恢复失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    companion object {
        @JvmStatic
        fun newInstance(): SharedSettingFragment = SharedSettingFragment()
    }
}