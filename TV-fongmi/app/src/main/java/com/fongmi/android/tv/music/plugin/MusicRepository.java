package com.fongmi.android.tv.music.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;

import org.json.JSONArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音乐数据仓库（多源）：管理内置 + 用户导入的 MusicFree 插件。
 * <ul>
 *   <li>每个插件一个独立 {@link MusicSource}（独立 Rhino 沙箱）</li>
 *   <li>搜索用当前源；播放换源 / 歌词按 {@code media.source} 路由回原插件</li>
 *   <li>用户可粘贴插件 URL 导入：下载到 {@code filesDir/plugins} 并记忆，重启自动加载</li>
 * </ul>
 */
public final class MusicRepository {

    private static final String TAG = "MusicRepository";
    private static final String PREFS = "music_plugins";
    private static final String KEY_IMPORT = "import_urls";
    /** 内置插件清单（assets/music/ 下），新增内置源时在此追加文件名。 */
    private static final String[] BUILTIN = {"netease.js", "qq.js"};

    private static volatile MusicRepository instance;

    /** UI 可见的插件信息。 */
    public static final class PluginInfo {
        public final String platform;
        public final String version;
        public final String label;
        public final boolean builtin;

        PluginInfo(String platform, String version, String label, boolean builtin) {
            this.platform = platform;
            this.version = version;
            this.label = label;
            this.builtin = builtin;
        }
    }

    private static final class Plugin {
        final MusicSource source;
        final String label;
        final boolean builtin;

        Plugin(MusicSource source, String label, boolean builtin) {
            this.source = source;
            this.label = label;
            this.builtin = builtin;
        }
    }

    private final List<Plugin> plugins = new ArrayList<>();
    /** 插件/初始化失败记录（供 UI 展示具体原因，不吞错误）。 */
    private final List<String> loadErrors = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Plugin current;
    private volatile boolean initialised;
    private volatile Context context;
    /** 内置插件加载完成信号（UI 等此 future 后再发起搜索，避免主线程阻塞引擎初始化）。 */
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    private MusicRepository() {
    }

    public static MusicRepository get() {
        if (instance == null) {
            synchronized (MusicRepository.class) {
                if (instance == null) instance = new MusicRepository();
            }
        }
        return instance;
    }

    /**
     * 绑定 Context 后首次调用触发插件加载（幂等）。
     * 全部在后台线程执行：Rhino 引擎初始化/插件求值可能较慢（低端机、模拟器尤甚），
     * 不能阻塞 UI 线程（会造成 ANR「点进去就卡死」）。就绪后 {@link #ready()} 完成。
     */
    public synchronized void init(Context context) {
        if (initialised) return;
        initialised = true;
        this.context = context.getApplicationContext();
        io.submit(() -> {
            try {
                for (String name : BUILTIN) {
                    String code = Asset.read("music/" + name);
                    if (code != null && !code.isEmpty()) {
                        if (addLoaded("内置:" + name, code, true)) Log.d(TAG, "loaded builtin " + name);
                    } else {
                        Log.w(TAG, "builtin missing assets/music/" + name);
                    }
                }
                loadImportsAsync();
                if (current == null && !plugins.isEmpty()) current = plugins.get(0);
            } catch (Throwable e) {
                Log.w(TAG, "init plugins failed", e);
            } finally {
                ready.complete(null);
            }
        });
    }

    /** 内置插件加载完成 future（导入插件为尽力而为，不影响该 future）。 */
    public CompletableFuture<Void> readyFuture() {
        return ready;
    }

    /** init 是否已启动（用于界面早期检查，不建议阻塞等待）。 */
    public boolean started() {
        return initialised;
    }

    /** 当前插件名（如 netease），用于界面标识当前音源。 */
    public String platform() {
        Plugin p = current;
        return p == null ? "" : p.source.platform();
    }

    /** 当前插件版本（如 0.2.1）。 */
    public String version() {
        Plugin p = current;
        return p == null ? "" : p.source.version();
    }

    /** 全部可用插件（只读快照，供 UI 切换列表）。 */
    public List<PluginInfo> plugins() {
        List<PluginInfo> list = new ArrayList<>();
        synchronized (this) {
            for (Plugin p : plugins) list.add(new PluginInfo(p.source.platform(), p.source.version(), p.label, p.builtin));
        }
        return list;
    }

    /** 切换到指定 platform 的插件。成功返回 true。 */
    public boolean switchTo(String platform) {
        if (platform == null || platform.isEmpty()) return false;
        synchronized (this) {
            for (Plugin p : plugins) {
                if (platform.equals(p.source.platform())) {
                    current = p;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 导入插件：下载 URL 的 JS → plugins 目录 → 加载并设为当前源。
     * 失败返回 false；重复 URL 命中缓存文件时直接加载。
     */
    public CompletableFuture<Boolean> importPlugin(String url) {
        if (url == null || !url.startsWith("http")) return CompletableFuture.completedFuture(false);
        File file = pluginFile(url);
        if (file.exists()) return CompletableFuture.supplyAsync(() -> loadImport(url, file), io);
        return CompletableFuture.supplyAsync(() -> {
            try {
                String code = OkHttp.string(url);
                if (code == null || code.isEmpty()) return false;
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) {
                    boolean created = dir.mkdirs();
                    if (!created) Log.w(TAG, "mkdir failed " + dir);
                }
                Files.write(file.toPath(), code.getBytes(StandardCharsets.UTF_8));
                return loadImport(url, file);
            } catch (Throwable e) {
                Log.w(TAG, "import failed " + url, e);
                return false;
            }
        }, io);
    }

    /** 搜索（第 1 页，类型=歌曲），走当前源。 */
    public CompletableFuture<List<MusicMedia>> search(String keyword) {
        Plugin p = current;
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.search(keyword == null ? "" : keyword, 1, 1);
    }

    /** 拉取播放 URL（按 media.source 路由，缺省当前源）。 */
    public CompletableFuture<String> getMediaUrl(MusicMedia media, String quality) {
        Plugin p = sourceOf(media);
        if (p == null) return CompletableFuture.completedFuture(null);
        return p.source.getMediaUrl(media, quality);
    }

    /** 拉取歌词（按 media.source 路由，缺省当前源）。 */
    public CompletableFuture<String> getLyric(MusicMedia media) {
        Plugin p = sourceOf(media);
        if (p == null) return CompletableFuture.completedFuture(null);
        return p.source.getLyric(media);
    }

    // ------------------------------------------------------------ 内部

    /** 加载一个导入插件（本地文件在缓存或刚下载），成功返回 true。 */
    private boolean loadImport(String url, File file) {
        try {
            String code = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (code.isEmpty()) return false;
            if (!addLoaded("导入:" + new File(url).getName(), code, false)) return false;
            rememberImport(url);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "load import failed " + url, e);
            return false;
        }
    }

    /** 尝试加载插件源码；成功时登记到列表（首个插件自动成为当前源）。幂等失败返回 false。 */
    private boolean addLoaded(String label, String code, boolean builtin) {
        try {
            MusicSource source = new MusicSource();
            source.load(code);
            synchronized (this) {
                // 避免重复：platform 相同则跳过（内置覆盖导入同名优先内置）
                for (Plugin p : plugins) {
                    if (builtin && !p.builtin && source.platform().equals(p.source.platform())) return false;
                }
                plugins.add(new Plugin(source, label, builtin));
                if (current == null) current = plugins.get(plugins.size() - 1);
            }
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "plugin load failed: " + label, e);
            recordLoadError(label + ": " + brief(e));
            return false;
        }
    }

    /** 全部插件的加载失败记录（供音源弹窗/状态栏显示排查原因）。 */
    public List<String> loadErrors() {
        synchronized (this) {
            return new ArrayList<>(loadErrors);
        }
    }

    private void recordLoadError(String text) {
        synchronized (this) {
            loadErrors.add(text);
        }
        // 持久化到外部日志（无 root 也可用文件管理器查看），便于用户回传排查
        try {
            Context ctx = context;
            File dir = ctx == null ? null : new File(ctx.getExternalFilesDir(null), "logs");
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                try (java.io.FileWriter w = new java.io.FileWriter(new File(dir, "plugin_load.txt"), true)) {
                    w.append(String.valueOf(System.currentTimeMillis())).append(' ').append(text).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.toString() : m;
    }

    /** 按 media.source 路由插件；空/未命中回退当前源。 */
    private Plugin sourceOf(MusicMedia media) {
        String s = media == null ? "" : media.source;
        if (!s.isEmpty()) {
            synchronized (this) {
                for (Plugin p : plugins) {
                    if (s.equals(p.source.platform())) return p;
                }
            }
        }
        return current;
    }

    /** 插件文件路径：filesDir/plugins/&lt;url hash&gt;.js（URL 与文件一一对应）。 */
    private File pluginFile(String url) {
        File dir = new File(context.getFilesDir(), "plugins");
        return new File(dir, Math.abs(url.hashCode()) + ".js");
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 重启后补载已导入的插件：有缓存文件直接加载，无则后台下载。 */
    private void loadImportsAsync() {
        for (String url : importedUrls()) {
            File file = pluginFile(url);
            if (file.exists()) {
                io.submit(() -> loadImport(url, file));
            } else {
                io.submit(() -> {
                    try {
                        String code = OkHttp.string(url);
                        if (code == null || code.isEmpty()) return;
                        File dir = file.getParentFile();
                        if (dir != null && !dir.exists()) dir.mkdirs();
                        Files.write(file.toPath(), code.getBytes(StandardCharsets.UTF_8));
                        loadImport(url, file);
                    } catch (Throwable e) {
                        Log.w(TAG, "re-download plugin failed " + url, e);
                    }
                });
            }
        }
    }

    private List<String> importedUrls() {
        String raw = prefs().getString(KEY_IMPORT, "");
        List<String> list = new ArrayList<>();
        if (raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return list;
    }

    private synchronized void rememberImport(String url) {
        List<String> urls = importedUrls();
        if (!urls.contains(url)) {
            urls.add(url);
            prefs().edit().putString(KEY_IMPORT, new JSONArray(urls).toString()).apply();
        }
    }
}