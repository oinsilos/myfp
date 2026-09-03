import React from "react";
import { StyleSheet, View } from "react-native";
import ThemeText from "../base/themeText";
import Tag from "../base/tag";
export default function TitleAndTag(props) {
    const { title, tag, titleFontColor } = props;
    return (<View style={styles.container}>
            <ThemeText fontColor={titleFontColor} numberOfLines={1} style={styles.title}>
                {title}
            </ThemeText>
            {tag ? <Tag tagName={tag}/> : null}
        </View>);
}
const styles = StyleSheet.create({
    container: {
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "space-between",
    },
    title: {
        flex: 1,
    },
});
