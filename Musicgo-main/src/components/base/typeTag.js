import React from "react";
import { StyleSheet, TouchableOpacity, View, } from "react-native";
import rpx from "@/utils/rpx";
import ThemeText from "@/components/base/themeText";
import useColors from "@/hooks/useColors";
export default function TypeTag(props) {
    const { title, onPress, selected = false, 
    // backgroundColor,
    style: _style, } = props;
    const colors = useColors();
    return (<TouchableOpacity onPress={onPress}>
            <View style={[
            style.wrapper,
            {
                backgroundColor: colors.card,
                borderColor: colors.divider,
            },
            _style,
        ]}>
                <ThemeText fontSize="subTitle" fontColor={selected ? "primary" : "text"}>
                    {title}
                </ThemeText>
            </View>
        </TouchableOpacity>);
}
const style = StyleSheet.create({
    wrapper: {
        flexGrow: 0,
        paddingHorizontal: rpx(18),
        paddingVertical: rpx(12),
        borderRadius: rpx(36),
        marginHorizontal: rpx(16),
        borderWidth: 1,
        borderStyle: "solid",
    },
});
