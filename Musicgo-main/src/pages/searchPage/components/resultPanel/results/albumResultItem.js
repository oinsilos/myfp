import React from "react";
import AlbumItem from "@/components/mediaItem/albumItem";
export default function AlbumResultItem(props) {
    const { item: albumItem } = props;
    return <AlbumItem albumItem={albumItem}/>;
}
