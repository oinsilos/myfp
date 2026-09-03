import React from "react";
import SheetItem from "@/components/mediaItem/sheetItem";
export default function MusicSheetResultItem(props) {
    const { item, pluginHash } = props;
    return <SheetItem sheetInfo={item} pluginHash={pluginHash}/>;
}
