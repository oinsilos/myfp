import React from "react";
import { SafeAreaView } from "react-native-safe-area-context";
export default function VerticalSafeAreaView(props) {
    const { children, style, mode } = props;
    return (<SafeAreaView style={style} mode={mode} edges={["top", "bottom"]}>
            {children}
        </SafeAreaView>);
}
