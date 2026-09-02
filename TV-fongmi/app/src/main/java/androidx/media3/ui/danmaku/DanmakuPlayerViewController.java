/*
 * Real danmaku controller facade, wires androidx.media3's danmaku rendering
 * pipeline (DanmakuController + DanmakuView) to a PlayerView. Kept in the app
 * source tree because neither our public ui_danmaku module nor the official
 * media3 exposes this FongMi-specific entry point.
 */
package androidx.media3.ui.danmaku;

import android.net.Uri;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import okhttp3.OkHttpClient;

/** Coordinates danmaku rendering with a {@link PlayerView}. */
@MainThread
@UnstableApi
public final class DanmakuPlayerViewController {

  private final DanmakuController controller;
  @Nullable private DanmakuView danmakuView;
  @Nullable private PlayerView playerView;
  private boolean playerBound;

  /** Creates a controller with the default loading window and retry timings. */
  public DanmakuPlayerViewController() {
    controller = new DanmakuController();
  }

  /** The target frame layout holding the danmaku overlay. */
  @Nullable
  protected FrameLayout getOverlayFrameLayout(@NonNull PlayerView playerView) {
    return playerView.getOverlayFrameLayout();
  }

  /** Attaches the controller to {@code playerView}, adding the danmaku overlay if needed. */
  public void bind(@NonNull PlayerView playerView) {
    this.playerView = playerView;
    Player player = playerView.getPlayer();
    FrameLayout overlay = getOverlayFrameLayout(playerView);
    if (danmakuView == null) {
      DanmakuView view = new DanmakuView(playerView.getContext());
      FrameLayout.LayoutParams params =
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      if (overlay != null) {
        overlay.addView(view, params);
      } else {
        playerView.addView(view, params);
      }
      danmakuView = view;
    }
    controller.setView(danmakuView);
    bindPlayer(player);
  }

  private void bindPlayer(@Nullable Player player) {
    if (playerBound) {
      return;
    }
    playerBound = true;
    controller.setPlayer(player);
  }

  /** Sets the HTTP client used for network danmaku sources, or clears it if {@code null}. */
  public void setOkHttpClient(@Nullable OkHttpClient client) {
    controller.setOkHttpClient(client);
  }

  /** Sets whether danmaku rendering is enabled. */
  public void setEnabled(boolean enabled) {
    controller.setEnabled(enabled);
  }

  /** Sets the rendering configuration. */
  public void setConfig(@NonNull DanmakuConfig config) {
    controller.setConfig(config);
  }

  /** Sets the danmaku source URI, or clears the current source if {@code uri} is {@code null}. */
  public void setDataSource(@Nullable Uri uri) {
    PlayerView view = playerView;
    if (!playerBound && view != null) {
      bindPlayer(view.getPlayer());
    }
    controller.setDataSource(uri);
  }

  /** Sends text at the current playback position. */
  public void sendNow(@NonNull String text) {
    controller.sendNow(text);
  }

  /** Releases loading resources and detaches the controller from the view. */
  public void close() {
    controller.setPlayer(null);
    controller.setView(null);
    controller.release();
    playerView = null;
    danmakuView = null;
    playerBound = false;
  }
}