package com.fongmi.android.tv.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.Fragment
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.api.config.WallConfig
import com.fongmi.android.tv.bean.Config
import com.fongmi.android.tv.event.ConfigEvent
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.music.core.MusicLibrary
import com.fongmi.android.tv.music.plugin.MusicRepository
import com.fongmi.android.tv.reader.BookSource
import com.fongmi.android.tv.reader.ReaderRepository
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.reader.RssRepository
import com.fongmi.android.tv.setting.PlayerSetting
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.activity.HomeActivity
import com.fongmi.android.tv.ui.common.ThemeStore
import com.fongmi.android.tv.ui.common.UnifiedBackup
import com.fongmi.android.tv.Updater
import com.fongmi.android.tv.BuildConfig
import com.fongmi.android.tv.R
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.utils.FileUtil
import com.fongmi.android.tv.utils.Notify
import com.github.catvod.bean.Doh
import com.github.catvod.net.OkHttp
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

    /** 本地 JS 插件文件选择器（音乐音源弹窗内「本地文件」导入）。 */
    private val localPluginPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocalPlugin(uri)
    }

    /** 数据版本号：音乐/书源弹窗内增删改后自增，触发弹窗列表重读（Compose 观察）。 */
    private val musicSourceVersion = mutableStateOf(0)
    private val readSourceVersion = mutableStateOf(0)

    /** 管理弹窗显隐：提为 Fragment 级状态，供音乐/小说板块「去设置导入」跳转后直接打开对应弹窗。 */
    private val musicSourceVisible = mutableStateOf(false)
    private val readSourceVisible = mutableStateOf(false)
    private val videoSettingVisible = mutableStateOf(false)
    private val netSettingVisible = mutableStateOf(false)

    /** 板块内「去设置导入」跳转：音乐音源管理弹窗。 */
    fun openMusicSourceDialog() {
        musicSourceVisible.value = true
    }

    /** 板块内「去设置导入」跳转：书源管理弹窗。 */
    fun openReadSourceDialog() {
        readSourceVisible.value = true
    }

    private fun importLocalPlugin(uri: Uri) {
        Thread {
            try {
                val name = queryDisplayName(uri) ?: "plugin.js"
                val code = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                if (code.isEmpty()) {
                    requireActivity().runOnUiThread { Notify.show("插件文件读取失败") }
                    return@Thread
                }
                MusicRepository.get().importLocalFile(name, code).whenComplete { ok, _ ->
                    requireActivity().runOnUiThread {
                        musicSourceVersion.value++
                        Notify.show(if (ok == true) "插件导入成功：" + name else "插件导入失败（无法解析）")
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread { Notify.show("插件读取失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
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
                        onOpenVideoSettings = { videoSettingVisible.value = true },
                    )
                }
            }
        }
    }

    @Composable
    private fun SharedSettingContent(
        onOpenVideoSettings: () -> Unit,
    ) {
        val ts = ThemeStore.get()
        var theme by remember { mutableStateOf(ts.theme) }
        Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text("设置", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp))
                Text("只放对所有板块的设置；板块专属设置在各自板块内", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                // ---- 板块入口卡片：源配置管理收敛到这里，板块内仅保留切换 ----
                Text("板块设置", fontSize = 13.sp, color = Color(0xFF999999),
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                SectionRow("视频设置", "点播源 · 壁纸 · 无痕 · 尺寸 · 播放器 · 弹幕 · 预加载 · 解码", onOpenVideoSettings)
                SectionRow("网络设置", "DNS(DoH) · 播放器 UA（三板块共用）") { netSettingVisible.value = true }
                SectionRow("音乐音源", "切换 · 插件导入 · 本地 JS（设置内完成，板块里只留切换）") { musicSourceVisible.value = true }
                SectionRow("书源管理", "切换 · 导入 · 测试 · 删除（设置内完成，板块里只留切换）") { readSourceVisible.value = true }
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 16.dp))
                // ---- 通用：主题/皮肤（全局，跟随系统为默认） ----
                Text("主题 / 皮肤", fontSize = 14.sp, color = Color(0xFFDDDDDD))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    for ((key, label) in listOf("system" to "跟随系统", "dark" to "深色", "sepia" to "护眼", "night" to "夜间")) {
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
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Text("默认跟随系统深浅；护眼（暖色）用于小说正文；视频/音乐播放页恒定深色", fontSize = 11.sp,
                    color = Color(0xFF777777), modifier = Modifier.padding(top = 4.dp))
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 16.dp))
                // ---- 数据：备份 / 恢复（全局） ----
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
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 16.dp))
                // ---- 通用：缓存 / 版本（全局） ----
                Text("通用", fontSize = 13.sp, color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 4.dp))
                var cacheSize by remember { mutableStateOf("…") }
                LaunchedEffect(Unit) {
                    FileUtil.getCacheSize(object : Callback() {
                        override fun success(result: String) { cacheSize = result }
                    })
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        FileUtil.clearCache(object : Callback() {
                            override fun success() {
                                Notify.show("缓存已清除")
                                FileUtil.getCacheSize(object : Callback() {
                                    override fun success(result: String) { cacheSize = result }
                                })
                            }
                        })
                    }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("清除缓存", fontSize = 15.sp, color = Color(0xFFE0E0E0))
                    Spacer(Modifier.weight(1f))
                    Text(cacheSize, fontSize = 12.sp, color = Color(0xFF888888))
                    Spacer(Modifier.width(10.dp))
                    Text("清除", fontSize = 13.sp, color = Color(0xFF4FC3F7))
                }
                HorizontalDivider(color = Color(0x16FFFFFF))
                Row(
                    Modifier.fillMaxWidth().clickable { Updater.create().force().start(requireActivity()) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("版本 / 更新", fontSize = 15.sp, color = Color(0xFFE0E0E0))
                    Spacer(Modifier.weight(1f))
                    Text(BuildConfig.VERSION_NAME, fontSize = 12.sp, color = Color(0xFF888888))
                    Spacer(Modifier.width(10.dp))
                    Text("检查", fontSize = 13.sp, color = Color(0xFF4FC3F7))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (musicSourceVisible.value) {
            MusicSourceDialog(
                version = musicSourceVersion.value,
                onChanged = { musicSourceVersion.value++ },
                onClose = { musicSourceVisible.value = false },
                onPickLocal = { localPluginPicker.launch(arrayOf("*/*")) },
            )
        }
        if (readSourceVisible.value) {
            ReadSourceDialog(
                version = readSourceVersion.value,
                onChanged = { readSourceVersion.value++ },
                onClose = { readSourceVisible.value = false },
            )
        }
        if (videoSettingVisible.value) {
            VideoSettingsDialog(
                onClose = { videoSettingVisible.value = false },
                onOpenPage = { pos -> (requireActivity() as? HomeActivity)?.change(pos) },
            )
        }
        if (netSettingVisible.value) {
            NetSettingsDialog(onClose = { netSettingVisible.value = false })
        }
    }

    /** 音乐音源管理弹窗（视频板块式紧凑弹窗，驻留设置 tab，关闭即回到设置）。 */
    @Composable
    private fun MusicSourceDialog(
        version: Int,
        onChanged: () -> Unit,
        onClose: () -> Unit,
        onPickLocal: () -> Unit,
    ) {
        var url by remember { mutableStateOf("") }
        val handler = remember { Handler(Looper.getMainLooper()) }
        val repo = remember { MusicRepository.get() }
        val plugins = remember(version) { repo.plugins() }
        val current = remember(version) { repo.platform() }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
                    .widthIn(max = 440.dp).heightIn(max = 540.dp)
                    .padding(vertical = 18.dp),
            ) {
                Text("音乐音源", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text("点选切换音源；下方粘贴链接或选择本地 JS 导入", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                Column(Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)) {
                    if (plugins.isEmpty()) {
                        Text(if (repo.loadErrors().isNotEmpty()) "源加载失败：" + repo.loadErrors().first().take(60)
                            else "插件源加载中或为空，可先粘贴链接导入", fontSize = 12.sp,
                            color = Color(0xFF777777), modifier = Modifier.padding(vertical = 18.dp))
                    } else {
                        plugins.forEach { info ->
                            val isCur = info.platform == current
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val ok = repo.switchTo(info.platform)
                                    onChanged()
                                    Notify.show(if (ok) "已切换到 " + info.label else "切换失败")
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (isCur) "●  " else "○  ", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text((if (info.builtin) "内置 · " else "外部 · ") + info.label + (if (isCur) "（使用中）" else ""),
                                        fontSize = 14.sp,
                                        color = if (isCur) Color.White else Color(0xFFCCCCCC))
                                    Text("${info.platform}  v${info.version}", fontSize = 10.sp, color = Color(0xFF666666))
                                }
                                Text("测试", fontSize = 12.sp, color = Color(0xFF81C784),
                                    modifier = Modifier.clickable {
                                        repo.testPlatform(info.platform).whenComplete { ok, _ ->
                                            handler.post { Notify.show(if (ok == true) "测试通过" else "测试失败：无结果或源不可达") }
                                        }
                                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            HorizontalDivider(color = Color(0x16FFFFFF))
                        }
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("粘贴插件 JS 下载链接", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (url.isBlank()) return@Button
                        repo.importPlugin(url.trim()).whenComplete { ok, _ ->
                            handler.post {
                                url = ""
                                onChanged()
                                Notify.show(if (ok == true) "插件导入成功" else "插件导入失败")
                            }
                        }
                    }) { Text("导入", fontSize = 13.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("导入本地 JS 文件", fontSize = 12.sp, color = Color(0xFF999999),
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onPickLocal) { Text("选择文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 书源管理弹窗（紧凑弹窗，驻留设置 tab）：启用/停用 + 测试 + 删除 + JSON/URL 导入。 */
    @Composable
    private fun ReadSourceDialog(
        version: Int,
        onChanged: () -> Unit,
        onClose: () -> Unit,
    ) {
        var url by remember { mutableStateOf("") }
        val handler = remember { Handler(Looper.getMainLooper()) }
        val sources = remember(version) { ReaderRepository.get().sources() }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
                    .widthIn(max = 460.dp).heightIn(max = 540.dp)
                    .padding(vertical = 18.dp),
            ) {
                Text("书源管理", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text("点按名称启用/停用；支持粘贴 legado bookSource JSON 或下载链接导入", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                Column(Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)) {
                    if (sources.isEmpty()) {
                        Text("暂无书源，可粘贴 JSON 或链接导入", fontSize = 12.sp,
                            color = Color(0xFF777777), modifier = Modifier.padding(vertical = 18.dp))
                    } else {
                        sources.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable {
                                ReaderRepository.get().toggleSource(s.url)
                                onChanged()
                            }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (s.enabled) "●  " else "○  ", fontSize = 14.sp,
                                    color = if (s.enabled) MaterialTheme.colorScheme.primary else Color(0xFF555555))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontSize = 14.sp,
                                        color = if (s.enabled) Color(0xFFDDDDDD) else Color(0xFF777777), maxLines = 1)
                                    Text(s.url, fontSize = 10.sp, color = Color(0xFF666666), maxLines = 1)
                                }
                                Text("测试", fontSize = 12.sp, color = Color(0xFF81C784),
                                    modifier = Modifier.clickable {
                                        ReaderRepository.get().testSource(s.url).whenComplete { ok, _ ->
                                            handler.post { Notify.show(if (ok == true) "测试通过" else "测试失败：无结果或源不可达") }
                                        }
                                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                                Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80),
                                    modifier = Modifier.clickable {
                                        ReaderRepository.get().removeSource(s.url)
                                        onChanged()
                                        Notify.show("已删除该书源")
                                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            HorizontalDivider(color = Color(0x16FFFFFF))
                        }
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("粘贴书源 JSON 或链接", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (url.isBlank()) return@Button
                        ReaderRepository.get().importSource(url.trim()).whenComplete { n, _ ->
                            handler.post {
                                url = ""
                                onChanged()
                                Notify.show(if (n != null && n > 0) "已导入 $n 个书源" else "导入失败：不是合法的书源 JSON/URL")
                            }
                        }
                    }) { Text("导入", fontSize = 13.sp) }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 网络设置弹窗（三板块共用）：DNS(DoH) + 播放器 UA，作用于全局 OkHttp。 */
    @Composable
    private fun NetSettingsDialog(onClose: () -> Unit) {
        var ua by remember { mutableStateOf(Setting.getUa()) }
        var dnsTick by remember { mutableStateOf(0) }
        val context = LocalContext.current
        val dohList = remember { Doh.get(context).ifEmpty { VodConfig.get().getDoh() } }
        val dohNames = remember(dnsTick) { dohList.map { it.getName() } }
        val dohIndex = remember(dnsTick) { dohList.indexOf(Doh.objectFrom(Setting.getDoh())).coerceAtLeast(0) }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
                    .widthIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 18.dp),
            ) {
                Text("网络设置", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text("DNS(DoH) · 播放器 UA（音乐 / 小说 / 视频共用，全局生效）", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                Text("DNS（DoH）", fontSize = 13.sp, color = Color(0xFFDDDDDD),
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    dohNames.forEachIndexed { i, name ->
                        val sel = i == dohIndex
                        Text(
                            name,
                            fontSize = 12.sp,
                            color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                .clickable {
                                    dohList.getOrNull(i)?.let {
                                        OkHttp.dns().setDoh(it)
                                        Setting.putDoh(it.toString())
                                        dnsTick++
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 12.dp))
                Text("播放器 UA", fontSize = 13.sp, color = Color(0xFFDDDDDD),
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ua,
                        onValueChange = { ua = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("自定义 User-Agent，可留空", color = Color(0xFF666666), fontSize = 13.sp) },
                        maxLines = 1,
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        Setting.putUa(ua.trim())
                        Notify.show("UA 已保存")
                    }) { Text("保存", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    /** 视频设置弹窗（视频板块式紧凑弹窗，驻留设置 tab）：点播源 / 壁纸 / 无痕 / 尺寸 / 子页入口。 */
    @Composable
    private fun VideoSettingsDialog(
        onClose: () -> Unit,
        onOpenPage: (Int) -> Unit,
    ) {
        var url by remember { mutableStateOf(VodConfig.getUrl()) }
        var wallTick by remember { mutableStateOf(0) }
        val context = LocalContext.current
        val handler = remember { Handler(Looper.getMainLooper()) }
        val sizeList = remember { context.resources.getStringArray(R.array.select_size).toList() }
        val wall = remember(wallTick) { Setting.getWall() }
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(14.dp))
                    .widthIn(max = 460.dp).heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 18.dp),
            ) {
                Text("视频设置", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text("点播源 · 壁纸 · 无痕 · 尺寸（DNS / UA 在「网络设置」共用）", fontSize = 11.sp,
                    color = Color(0xFF888888), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                // ---- 点播源配置 URL ----
                Text("点播源", fontSize = 13.sp, color = Color(0xFFDDDDDD),
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    placeholder = { Text("点播源订阅地址 / 本地文件", color = Color(0xFF666666), fontSize = 13.sp) },
                    maxLines = 1,
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(VodConfig.getDesc(), fontSize = 12.sp, color = Color(0xFF777777),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (url.isBlank()) return@TextButton
                        VodConfig.load(Config.find(url.trim(), 0), callback())
                    }) { Text("保存", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("本站点：", fontSize = 12.sp, color = Color(0xFF999999))
                    Text(VodConfig.get().getHome().getName().ifEmpty { "未设置" }, fontSize = 12.sp,
                        color = Color(0xFFDDDDDD), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("测试", fontSize = 12.sp, color = Color(0xFF81C784),
                        modifier = Modifier.clickable {
                            val site = VodConfig.get().getHome()
                            if (site.getKey().isEmpty()) {
                                Notify.show("请先配置点播源")
                                return@clickable
                            }
                            Thread {
                                val ok = try {
                                    val api = site.getApi()
                                    if (api.startsWith("http")) {
                                        !com.github.catvod.net.OkHttp.string(api).isNullOrBlank()
                                    } else {
                                        !com.fongmi.android.tv.api.SiteApi.homeContent(site).getList().isEmpty()
                                    }
                                } catch (e: Exception) {
                                    false
                                }
                                handler.post { Notify.show(if (ok) "测试通过" else "测试失败：源不可达") }
                            }.start()
                        }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 10.dp))
                // ---- 无痕 / 尺寸 / 壁纸 ----
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("无痕模式", fontSize = 13.sp, color = Color(0xFFDDDDDD), modifier = Modifier.weight(1f))
                    val inc = remember { mutableStateOf(Setting.isIncognito()) }
                    Row(Modifier.clickable {
                        inc.value = !inc.value
                        Setting.putIncognito(inc.value)
                        Notify.show(if (inc.value) "无痕已开启" else "无痕已关闭")
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (inc.value) "● " else "○ ", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Text(if (inc.value) "开" else "关", fontSize = 13.sp, color = Color(0xFFCCCCCC))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("视频尺寸", fontSize = 13.sp, color = Color(0xFFDDDDDD),
                    modifier = Modifier.padding(start = 20.dp, bottom = 2.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    sizeList.forEachIndexed { i, name ->
                        val sel = i == PlayerSetting.getSize()
                        Text(
                            name,
                            fontSize = 12.sp,
                            color = if (sel) Color(0xFF141414) else Color(0xFFCCCCCC),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF4FC3F7) else Color(0x26FFFFFF))
                                .clickable {
                                    PlayerSetting.putSize(i)
                                    RefreshEvent.size()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("壁纸", fontSize = 13.sp, color = Color(0xFFDDDDDD), modifier = Modifier.weight(1f))
                    Text(if (wall in 1..4) "内置 $wall/4" else WallConfig.getDesc(), fontSize = 12.sp,
                        color = Color(0xFF999999), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        Setting.putWall(if (Setting.getWall() == 4) 1 else Setting.getWall() + 1)
                        Setting.putWallType(0)
                        ConfigEvent.wall()
                        wallTick++
                    }) { Text("切换", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = {
                        WallConfig.get().load(object : Callback() {
                            override fun success() {
                                handler.post { wallTick++; Notify.show("壁纸已刷新") }
                            }
                        })
                    }) { Text("刷新", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                }
                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 10.dp))
                // ---- 子页入口 ----
                Text("播放器设置 ›", fontSize = 14.sp, color = Color(0xFF4FC3F7),
                    modifier = Modifier.fillMaxWidth().clickable { onClose(); onOpenPage(5) }
                        .padding(horizontal = 20.dp, vertical = 8.dp))
                Text("弹幕设置 ›", fontSize = 14.sp, color = Color(0xFF4FC3F7),
                    modifier = Modifier.fillMaxWidth().clickable { onClose(); onOpenPage(6) }
                        .padding(horizontal = 20.dp, vertical = 8.dp))
                Text("预加载设置 ›", fontSize = 14.sp, color = Color(0xFF4FC3F7),
                    modifier = Modifier.fillMaxWidth().clickable { onClose(); onOpenPage(7) }
                        .padding(horizontal = 20.dp, vertical = 8.dp))
                Text("解码设置 ›", fontSize = 14.sp, color = Color(0xFF4FC3F7),
                    modifier = Modifier.fillMaxWidth().clickable { onClose(); onOpenPage(8) }
                        .padding(horizontal = 20.dp, vertical = 8.dp))
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("完成", fontSize = 13.sp, color = Color(0xFF888888))
                }
            }
        }
    }

    private fun callback(): Callback = object : Callback() {
        override fun success() {
            requireActivity().runOnUiThread { Notify.show("点播源配置成功") }
        }

        override fun error(msg: String) {
            requireActivity().runOnUiThread { Notify.show(msg) }
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