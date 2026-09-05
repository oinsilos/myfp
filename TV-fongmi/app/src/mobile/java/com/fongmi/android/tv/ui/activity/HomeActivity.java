package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.music.ui.MusicFragment;
import com.fongmi.android.tv.reader.ui.ReaderFragment;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.receiver.ShortcutReceiver;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingDecodeFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.SettingPreloadFragment;
import com.fongmi.android.tv.ui.fragment.SharedSettingFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.android.material.navigation.NavigationBarView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener {

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private int orientation;
    /** 等待交给阅读板块导入的外部书籍文件（分享/打开 txt|epub）。 */
    private Uri pendingBookUri = null;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
        handleTabIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        orientation = getResources().getConfiguration().orientation;
        mBinding.navigation.setOnItemSelectedListener(this);
        PermissionUtil.requestNotify(this);
        initFragment(savedInstanceState);
        Updater.create().start(this);
        initConfig();
        if (savedInstanceState == null) handleTabIntent(getIntent()); // 通知栏/外部拉起直接跳对应板块
    }

    @Override
    protected void initEvent() {
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        String path = UrlUtil.path(intent.getData());
        if ("text/plain".equals(intent.getType()) || path.endsWith(".m3u")) {
            FileChooser.getUri(intent, uri -> loadLive(UrlUtil.toLocalUrl(uri)));
        } else if (path.endsWith(".txt") || path.endsWith(".epub")) {
            // txt/epub 书籍文件 → 切到小说板块导入
            pendingBookUri = intent.getData();
            change(3);
            deliverPendingBook();
        } else {
            FileChooser.getUri(intent, uri -> VideoActivity.file(this, uri));
        }
    }

    /** 等小说板块 Fragment 创建完后把外部书籍文件交给它导入。 */
    private void deliverPendingBook() {
        if (pendingBookUri == null) return;
        Uri u = pendingBookUri;
        pendingBookUri = null;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Fragment f = mManager.getFragment(3);
            if (f instanceof ReaderFragment) ((ReaderFragment) f).importExternal(u);
        }, 500);
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> {
            // 0 视频点播 / 1 统一设置（三板块共用）/ 2 音乐板块 / 3 阅读板块 / 4 及以下 视频专属设置（fongmi 原设置迁移）
            return switch (position) {
                case 0 -> VodFragment.newInstance();
                case 1 -> SharedSettingFragment.newInstance();
                case 2 -> new MusicFragment();
                case 3 -> new ReaderFragment();
                case 4 -> SettingFragment.newInstance();
                case 5 -> SettingPlayerFragment.newInstance();
                case 6 -> SettingDanmakuFragment.newInstance();
                case 7 -> SettingPreloadFragment.newInstance();
                case 8 -> SettingDecodeFragment.newInstance();
                default -> null;
            };
        });
        if (savedInstanceState == null) change(0);
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        if (isFinishing() || isDestroyed()) return;
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private void setNavigation() {
        mBinding.navigation.getMenu().findItem(R.id.vod).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.setting).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.music).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.read).setVisible(true);
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    public void change(int position) {
        // 底部导航四项：0 视频 / 1 设置 / 2 音乐 / 3 小说；4+ 为视频专属设置的子页（无导航高亮）
        if (position == 0 || position == 1 || position == 2 || position == 3) {
            int id = switch (position) {
                case 0 -> R.id.vod;
                case 1 -> R.id.setting;
                case 2 -> R.id.music;
                default -> R.id.read;
            };
            mBinding.navigation.setSelectedItemId(id);
        } else {
            mManager.change(position);
        }
    }

    /** 通知栏/外部拉起跳转：extra "tab" = music|read 时切到对应板块。 */
    private void handleTabIntent(Intent intent) {
        if (intent == null) return;
        String tab = intent.getStringExtra("tab");
        if ("music".equals(tab)) change(2);
        else if ("read".equals(tab)) change(3);
    }

    /** 统一设置页「音乐音源」卡片：切入音乐板块并弹出插件源管理。 */
    public void openMusicSources() {
        change(2);
        Fragment f = mManager.getFragment(2);
        if (f instanceof MusicFragment) ((MusicFragment) f).openSourceDialog();
    }

    /** 统一设置页「书源管理」卡片：切入小说板块并弹出书源管理。 */
    public void openReadSources() {
        change(3);
        Fragment f = mManager.getFragment(3);
        if (f instanceof ReaderFragment) ((ReaderFragment) f).openSourceDialog();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                break;
            // BOOT（开机直接进直播）已随直播板块入口一并移除
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.vod) return mManager.change(0);
        if (item.getItemId() == R.id.setting) return mManager.change(1);
        if (item.getItemId() == R.id.music) return mManager.change(2);
        if (item.getItemId() == R.id.read) return mManager.change(3);
        return false;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        App.post(() -> checkOrientation(newConfig), 100);
    }

    private void checkOrientation(Configuration newConfig) {
        if (orientation != newConfig.orientation) {
            orientation = newConfig.orientation;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (mManager.isVisible(8) || mManager.isVisible(7)) { // 解码/预加载 → 播放器设置
            change(5);
        } else if (mManager.isVisible(6) || mManager.isVisible(5)) { // 弹幕/播放器 → 视频专属设置
            change(4);
        } else if (mManager.isVisible(4)) { // 视频专属设置 → 统一设置
            change(1);
        } else if (mManager.isVisible(1)) { // 统一设置 → 首页
            change(0);
        } else if (mManager.isVisible(3)) { // 小说板块：内部逐级退回，退回板块根则切回视频
            Fragment f = mManager.getFragment(3);
            if (!(f instanceof ReaderFragment) || !((ReaderFragment) f).processBack()) change(0);
        } else if (mManager.isVisible(2)) { // 音乐板块 → 首页
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        LiveConfig.get().clear();
        VodConfig.get().clear();
        BackupManager.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
