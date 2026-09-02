package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.player.engine.PlayerEngine.SecondarySubtitleState;
import com.fongmi.android.tv.setting.SubtitleSetting;

final class ExoSubtitleController {

    @Nullable
    private PlayerView playerView;

    ExoSubtitleController(ExoPlayerSession session) {
    }

    void release() {
        playerView = null;
    }

    void bindPlayerView(PlayerView playerView) {
        this.playerView = playerView;
        applySubtitleStyle();
    }

    void applySubtitleStyle() {
        if (playerView != null) SubtitleSetting.applyStyle(playerView.getSubtitleView());
    }

    SecondarySubtitleState getSecondarySubtitleState() {
        return SecondarySubtitleState.EMPTY;
    }

    void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }

    private void applySecondarySubtitleStyle(SubtitleView subtitleView) {
        SubtitleSetting.applyStyle(subtitleView);
        subtitleView.setBottomPosition(0.0f);
    }
}