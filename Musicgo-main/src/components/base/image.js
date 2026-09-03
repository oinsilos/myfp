import React from "react";
import { Image } from "react-native";
export default function (props) {
    const { uri, emptySrc } = props;
    const source = typeof uri === "string"
        ? {
            uri,
        }
        : emptySrc;
    return <Image {...props} source={source}/>;
}
