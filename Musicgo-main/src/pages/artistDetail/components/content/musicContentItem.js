import React from "react";
import MusicItem from "@/components/mediaItem/musicItem";
export default function MusicContentItem(props) {
    const { item } = props;
    return <MusicItem musicItem={item}/>;
}
