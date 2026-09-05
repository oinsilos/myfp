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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.fongmi.android.tv.music.core.MusicLibrary
import com.fongmi.android.tv.reader.ReaderRepository
import com.fongmi.android.tv.reader.ReaderStore
import com.fongmi.android.tv.reader.RssRepository
import com.fongmi.android.tv.ui.activity.HomeActivity
import com.fongmi.android.tv.ui.common.UnifiedBackup
import com.fongmi.android.tv.ui.common.UnifiedSettingsPage
import com.fongmi.android.tv.utils.Notify
import java.nio.charset.StandardCharsets

/**
 * 底部「设置」tab：视频/音乐/小说 三板块共用的统一设置页。
 * - 主题/皮肤 + 阅读字号/行距 + 备份/恢复（单一 JSON 覆盖三板块本地数据）
 * - 源连通性测试不在这里，留在各板块内（书源/订阅源/音乐插件源）
 * - 「视频设置」入口跳回 fongmi 原始视频设置（点播/直播/播放器分类设置）
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
                    SharedSettingContent(onOpenVideoSettings = { (activity as? HomeActivity)?.change(4) })
                }
            }
        }
    }

    @Composable
    private fun SharedSettingContent(onOpenVideoSettings: () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                // 顶行：标题 + 视频专属设置入口（fongmi 原设置迁移入口）
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", fontSize = 15.sp, color = Color(0xFFE0E0E0))
                    Spacer(Modifier.weight(1f))
                    Text("视频专属设置 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onOpenVideoSettings).padding(horizontal = 6.dp, vertical = 6.dp))
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                UnifiedSettingsPage(
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
                    onExport = { backupExportPicker.launch("tv_fongmi_backup.json") },
                    onImport = { backupImportPicker.launch(arrayOf("*/*")) },
                    onClose = { onOpenVideoSettings() }, // 页面内「关闭」只作占位，不关闭
                )
            }
        }
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