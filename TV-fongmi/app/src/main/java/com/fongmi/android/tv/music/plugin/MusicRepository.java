package com.fongmi.android.tv.music.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.fongmi.android.tv.music.model.MusicMedia;
import com.fongmi.android.tv.music.model.MusicSheet;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;

import org.json.JSONArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    /** 本地文件导入的插件（文件名清单，文件缓存在 filesDir/plugins/，重启无网络也能加载）。 */
    private static final String KEY_LOCAL = "local_plugins";
    /** 内置插件清单（assets/music/ 下），新增内置源时在此追加文件名。 */
    private static final String[] BUILTIN = {"netease.js"};
    /** 单源搜索超时：某源卡死/无响应按空组处理，聚合结果不被拖死。 */
    private static final long SOURCE_TIMEOUT_MS = 12_000L;
    /** 超时护栏专用守护线程池（仅负责 complete，不执行请求）。 */
    private static final ExecutorService timeoutPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "music-source-timeout");
        t.setDaemon(true);
        return t;
    });

    /** 聚合搜索结果分组：一个插件（音源）的搜索结果集合。 */
    public static final class Aggregated {
        public final String label;
        public final String platform;
        public final List<MusicMedia> items;

        Aggregated(String label, String platform, List<MusicMedia> items) {
            this.label = label;
            this.platform = platform;
            this.items = items == null ? Collections.emptyList() : items;
        }
    }

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
                loadLocalPlugins();
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

    /**
     * 导入本地 JS 插件文件（文件选择器来源）：代码写缓存目录 + 登记，重启自动加载。
     * 成功返回 true 并自动设为当前源。
     */
    public CompletableFuture<Boolean> importLocalFile(String label, String code) {
        if (code == null || code.trim().isEmpty()) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            try {
                String name = Math.abs(code.hashCode()) + "_" + System.currentTimeMillis();
                File file = new File(context.getFilesDir(), "plugins/" + name + ".js");
                File dir = file.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) Log.w(TAG, "mkdir failed " + dir);
                Files.write(file.toPath(), code.getBytes(StandardCharsets.UTF_8));
                rememberLocal(name);
                return addLoaded(label, code, false);
            } catch (Throwable e) {
                Log.w(TAG, "import local plugin failed", e);
                return false;
            }
        }, io);
    }

    /** 重启补载本地文件导入的插件（无需网络）。 */
    private void loadLocalPlugins() {
        for (String name : localNames()) {
            File f = new File(context.getFilesDir(), "plugins/" + name + ".js");
            if (!f.exists()) continue;
            io.submit(() -> {
                try {
                    String code = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    addLoaded("本地:" + name, code, false);
                } catch (Throwable e) {
                    Log.w(TAG, "local plugin load failed " + name, e);
                }
            });
        }
    }

    private List<String> localNames() {
        String raw = prefs().getString(KEY_LOCAL, "");
        List<String> list = new ArrayList<>();
        if (raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return list;
    }

    private synchronized void rememberLocal(String name) {
        List<String> names = localNames();
        if (!names.contains(name)) {
            names.add(name);
            prefs().edit().putString(KEY_LOCAL, new JSONArray(names).toString()).apply();
        }
    }

    /** 连通性测试：对指定 platform 插件搜索一次固定关键词，能拿到非空响应即视为可用。 */
    public CompletableFuture<Boolean> testPlatform(String platform) {
        if (platform == null || platform.isEmpty()) return CompletableFuture.completedFuture(false);
        synchronized (this) {
            for (Plugin p : plugins) {
                if (platform.equals(p.source.platform())) {
                    CompletableFuture<Boolean> f = p.source.search("测试", 1, 1)
                            .thenApply(items -> items != null && !items.isEmpty());
                    return withTimeout(f, SOURCE_TIMEOUT_MS, false);
                }
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    /**
     * 聚合搜索：全部已加载插件并行搜索（每源带超时护栏，空结果/卡死源剔除），
     * 按插件分组返回，满足「像 fongmi 点播那样一搜多源汇总」的需求。
     */
    public CompletableFuture<List<Aggregated>> searchAll(String keyword) {
        List<Plugin> copy;
        synchronized (this) {
            copy = new ArrayList<>(plugins);
        }
        if (copy.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyList());
        String kw = keyword == null ? "" : keyword;
        List<CompletableFuture<Aggregated>> futures = new ArrayList<>();
        for (Plugin p : copy) {
            CompletableFuture<Aggregated> f = p.source.search(kw, 1, 1).thenApply(items ->
                    (items == null || items.isEmpty()) ? null : new Aggregated(p.label, p.source.platform(), items));
            futures.add(withTimeout(f, SOURCE_TIMEOUT_MS, null));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Aggregated> out = new ArrayList<>();
                    for (CompletableFuture<Aggregated> f : futures) {
                        try {
                            Aggregated a = f.get();
                            if (a != null) out.add(a);
                        } catch (Exception ignored) {
                        }
                    }
                    return out;
                });
    }

    /**
     * 批量关键词搜索并合并（文本歌单导入）：逐词串行搜全部音源，
     * 结果按平台合并到一个分组（同一源的多首歌曲归入一组），去空组。
     */
    public CompletableFuture<List<Aggregated>> searchMany(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyList());
        return CompletableFuture.supplyAsync(() -> {
            List<Aggregated> out = new ArrayList<>();
            Map<String, Integer> indexByPlatform = new HashMap<>();
            for (String kw : keywords) {
                try {
                    List<Aggregated> groups = searchAll(kw).get(SOURCE_TIMEOUT_MS + 5000L, TimeUnit.MILLISECONDS);
                    for (Aggregated g : groups) {
                        if (g.items.isEmpty()) continue;
                        Integer idx = indexByPlatform.get(g.platform);
                        if (idx == null) {
                            indexByPlatform.put(g.platform, out.size());
                            out.add(new Aggregated(g.label, g.platform, new ArrayList<>(g.items)));
                        } else {
                            out.get(idx).items.addAll(g.items);
                        }
                    }
                } catch (Exception ignored) {
                    // 单个关键词超时/失败不影响其余
                }
            }
            return out;
        }, io);
    }

    /** 到点强制 complete（底层任务继续跑，OkHttp 30s 超时兜底回收线程），聚合绝不无限等待。 */
    private static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, long ms, T fallback) {
        timeoutPool.execute(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                return;
            }
            future.complete(fallback);
        });
        return future;
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

    // ------------------------------------------------------------ 歌单 / 榜单 / 歌手 / 导入

    /** 榜单分组。 */
    public CompletableFuture<List<MusicSource.SheetGroup>> topLists() {
        Plugin p = current;
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.topLists();
    }

    /** 推荐歌单分类标签。 */
    public CompletableFuture<List<String>> recommendTags() {
        Plugin p = current;
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.recommendTags();
    }

    /** 某分类下的推荐歌单（分页）。 */
    public CompletableFuture<List<MusicSheet>> sheetsByTag(String tag, int page) {
        Plugin p = current;
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.sheetsByTag(tag, page);
    }

    /** 歌单详情（按 sheet.source 路由，缺省当前源）。 */
    public CompletableFuture<List<MusicMedia>> sheetDetail(MusicSheet sheet, int page) {
        Plugin p = sourceSheetOf(sheet);
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.sheetDetail(sheet, page);
    }

    /** 榜单详情（按 item.source 路由）。 */
    public CompletableFuture<List<MusicMedia>> topListDetail(MusicSheet item, int page) {
        Plugin p = sourceSheetOf(item);
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.topListDetail(item, page);
    }

    /** 歌手热门歌曲（按 artist.source 路由）。 */
    public CompletableFuture<List<MusicMedia>> artistSongs(MusicSheet artist, int page) {
        Plugin p = sourceSheetOf(artist);
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.artistSongs(artist, page);
    }

    /** 导入歌单（URL → 歌曲列表）。 */
    public CompletableFuture<List<MusicMedia>> importSheet(String urlLike) {
        Plugin p = current;
        if (p == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return p.source.importSheet(urlLike);
    }

    /** 所有已加载插件是否暗示了歌单相关能力（UI 决定展示歌单 Tab 前置条件）。 */
    public boolean anyHasSheetAbility() {
        synchronized (this) {
            for (Plugin p : plugins) {
                if (p.source.hasMethod("getTopLists") || p.source.hasMethod("getMusicSheetInfo")) return true;
            }
        }
        return false;
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

    /** 按 sheet.source 路由插件（歌单/榜单/歌手）；空/未命中回退当前源。 */
    private Plugin sourceSheetOf(MusicSheet sheet) {
        String s = sheet == null ? "" : sheet.source;
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