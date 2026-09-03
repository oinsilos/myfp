import React from "react";
import { Pressable, StyleSheet } from "react-native";
import rpx from "@/utils/rpx";
import ThemeText from "./themeText";
import useColors from "@/hooks/useColors";
import IconButton from "./iconButton";
export default function Chip(props) {
    const { containerStyle, children, onPress, onClose } = props;
    const colors = useColors();
    return (<Pressable onPress={onPress} style={[
            styles.container,
            {
                backgroundColor: colors.placeholder,
            },
            containerStyle,
        ]}>
            {typeof children === "string" ? (<ThemeText fontSize="subTitle" numberOfLines={1}>
                    {children}
                </ThemeText>) : (children)}
            <IconButton onPress={onClose} name="x-mark" sizeType="small" style={styles.icon}/>
        </Pressable>);
}
const styles = StyleSheet.create({
    container: {
        height: rpx(56),
        paddingHorizontal: rpx(18),
        borderRadius: rpx(28),
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "center",
    },
    icon: {
        marginLeft: rpx(8),
    },
});
