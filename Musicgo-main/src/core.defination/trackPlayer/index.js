export var TrackPlayerEvents;
(function (TrackPlayerEvents) {
    // 一首歌曲播放结束
    TrackPlayerEvents["PlayEnd"] = "play-end";
    // 更换正在播放的歌曲
    TrackPlayerEvents["CurrentMusicChanged"] = "current-music-changed";
    // 进度更新
    TrackPlayerEvents["ProgressChanged"] = "progress-changed";
})(TrackPlayerEvents || (TrackPlayerEvents = {}));
