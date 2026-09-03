import React from "react";
import NavBar from "./components/navBar";
import MusicBar from "@/components/musicBar";
import SheetMusicList from "./components/sheetMusicList";
import StatusBar from "@/components/base/statusBar";
import globalStyle from "@/constants/globalStyle";
import VerticalSafeAreaView from "../base/verticalSafeAreaView";
export default function MusicSheetPage(props) {
    const { navTitle, sheetInfo, musicList, canStar, onLoadMore, onRetry, state } = props;
    return (<VerticalSafeAreaView style={globalStyle.fwflex1}>
            <StatusBar />
            <NavBar musicList={musicList ?? sheetInfo?.musicList ?? []} navTitle={navTitle}/>
            <SheetMusicList canStar={canStar} sheetInfo={sheetInfo} musicList={musicList ?? sheetInfo?.musicList} state={state} onRetry={onRetry} onLoadMore={onLoadMore}/>
            <MusicBar />
        </VerticalSafeAreaView>);
}
