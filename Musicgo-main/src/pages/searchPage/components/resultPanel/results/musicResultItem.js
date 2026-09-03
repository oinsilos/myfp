import React from "react";
import MusicItem from "@/components/mediaItem/musicItem";
import Config from "@/core/appConfig";
import TrackPlayer from "@/core/trackPlayer";
export default function MusicResultItem(props) {
    const { item: musicItem, pluginSearchResultRef } = props;
    return (<MusicItem musicItem={musicItem} onItemPress={() => {
            const clickBehavior = Config.getConfig("basic.clickMusicInSearch");
            if (clickBehavior === "playMusicAndReplace") {
                TrackPlayer.playWithReplacePlayList(musicItem, (pluginSearchResultRef?.current?.data ?? [
                    musicItem,
                ]));
            }
            else {
                TrackPlayer.play(musicItem);
            }
        }}/>);
}
