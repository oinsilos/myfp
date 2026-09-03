import React from "react";
import { colorMap, iconSizeConst } from "@/constants/uiConst";
import { TapGestureHandler } from "react-native-gesture-handler";
import { StyleSheet, View } from "react-native";
import useColors from "@/hooks/useColors";
import Icon from "@/components/base/icon.tsx";
export function IconButtonWithGesture(props) {
    const { name, sizeType: size = "normal", fontColor = "normal", onPress, style, accessibilityLabel, } = props;
    const colors = useColors();
    const textSize = iconSizeConst[size];
    const color = colors[colorMap[fontColor]];
    return (<TapGestureHandler onActivated={onPress}>
            <View>
                <Icon accessible accessibilityLabel={accessibilityLabel} name={name} color={color} style={[{ minWidth: textSize }, styles.textCenter, style]} size={textSize}/>
            </View>
        </TapGestureHandler>);
}
export default function IconButton(props) {
    const { sizeType = "normal", fontColor = "normal", style, color } = props;
    const colors = useColors();
    const size = iconSizeConst[sizeType];
    return (<Icon {...props} color={color ?? colors[colorMap[fontColor]]} style={[{ minWidth: size }, styles.textCenter, style]} size={size}/>);
}
const styles = StyleSheet.create({
    textCenter: {
        height: "100%",
        textAlignVertical: "center",
    },
});
