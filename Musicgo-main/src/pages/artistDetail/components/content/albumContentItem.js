import React from "react";
import AlbumItem from "@/components/mediaItem/albumItem";
export default function AlbumContentItem(props) {
    const { item } = props;
    return <AlbumItem albumItem={item}/>;
}
