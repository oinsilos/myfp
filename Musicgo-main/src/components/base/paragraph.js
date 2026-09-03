import React from "react";
import { StyleSheet } from "react-native";
import ThemeText from "./themeText";
import { fontSizeConst } from "@/constants/uiConst";
export default function Paragraph(props) {
    return <ThemeText style={styles.container} {...props}/>;
}
const styles = StyleSheet.create({
    container: {
        fontSize: fontSizeConst.content,
        lineHeight: fontSizeConst.content * 1.8,
        marginVertical: 2,
        letterSpacing: 0.25,
    },
});
