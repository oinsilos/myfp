import React from "react";
import { SafeAreaView } from "react-native-safe-area-context";
export default function HorizontalSafeAreaView(props) {
    const { children, style, mode } = props;
    return (<SafeAreaView style={style} mode={mode} edges={["right", "left"]}>
            {children}
        </SafeAreaView>);
}
