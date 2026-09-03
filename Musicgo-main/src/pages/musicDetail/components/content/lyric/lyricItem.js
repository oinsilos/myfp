import React, { memo } from "react";
import { StyleSheet, Text } from "react-native";
import rpx from "@/utils/rpx";
import useColors from "@/hooks/useColors";
import { fontSizeConst } from "@/constants/uiConst";
function _LyricItemComponent(props) {
    const { light, highlight, text, onLayout, index, fontSize } = props;
    const colors = useColors();
    return (<Text onLayout={({ nativeEvent }) => {
            if (index !== undefined) {
                onLayout?.(index, nativeEvent.layout.height);
            }
        }} style={[
            lyricStyles.item,
            {
                fontSize: fontSize || fontSizeConst.content,
            },
            highlight
                ? [
                    lyricStyles.highlightItem,
                    {
                        color: colors.primary,
                    },
                ]
                : null,
            light ? lyricStyles.draggingItem : null,
        ]}>
            {text}
        </Text>);
}
// 歌词
const LyricItemComponent = memo(_LyricItemComponent, (prev, curr) => prev.light === curr.light &&
    prev.highlight === curr.highlight &&
    prev.text === curr.text &&
    prev.index === curr.index &&
    prev.fontSize === curr.fontSize);
export default LyricItemComponent;
const lyricStyles = StyleSheet.create({
    highlightItem: {
        opacity: 1,
    },
    item: {
        color: "white",
        opacity: 0.6,
        paddingHorizontal: rpx(64),
        paddingVertical: rpx(24),
        width: "100%",
        textAlign: "center",
        textAlignVertical: "center",
    },
    draggingItem: {
        opacity: 0.9,
        color: "white",
    },
});
