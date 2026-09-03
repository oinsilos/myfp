import React from "react";
import { StyleSheet } from "react-native";
import rpx from "@/utils/rpx";
import ThemeText from "./themeText";
import { iconSizeConst } from "@/constants/uiConst";
import useColors from "@/hooks/useColors";
import { TouchableOpacity } from "react-native-gesture-handler";
import Icon from "@/components/base/icon.tsx";
export default function (props) {
    const { icon, children, onPress, containerStyle } = props;
    const colors = useColors();
    return (<TouchableOpacity activeOpacity={0.7} style={[style.container, containerStyle]} onPress={onPress}>
            <Icon name={icon} size={iconSizeConst.light} color={colors.text}/>
            <ThemeText style={style.text} fontSize={"content"}>
                {children}
            </ThemeText>
        </TouchableOpacity>);
}
const style = StyleSheet.create({
    container: {
        flexDirection: "row",
        alignItems: "center",
        paddingHorizontal: rpx(16),
        paddingVertical: rpx(8),
    },
    text: {
        marginLeft: rpx(8),
    },
});
