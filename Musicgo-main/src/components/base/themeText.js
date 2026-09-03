import React from "react";
import { Text } from "react-native";
import { fontSizeConst, fontWeightConst } from "@/constants/uiConst";
import useColors from "@/hooks/useColors";
export default function ThemeText(props) {
    const colors = useColors();
    const { style, color, children, fontSize = "content", fontColor = "text", fontWeight = "regular", opacity, } = props;
    const themeStyle = {
        color: color ?? colors[fontColor],
        fontSize: fontSizeConst[fontSize],
        fontWeight: fontWeightConst[fontWeight],
        includeFontPadding: false,
        opacity,
    };
    const _style = Array.isArray(style)
        ? [themeStyle, ...style]
        : [themeStyle, style];
    return (<Text {...props} style={_style} allowFontScaling={false}>
            {children}
        </Text>);
}
