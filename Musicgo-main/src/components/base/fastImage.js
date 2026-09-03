import React, { useEffect, useState } from "react";
import FastImage from "react-native-fast-image";
export default function (props) {
    const { style, placeholderSource, defaultSource, source } = props ?? {};
    const [isError, setIsError] = useState(false);
    let realSource;
    if (typeof source === "string") {
        realSource = { uri: source };
        if (source.length === 0) {
            realSource = placeholderSource;
        }
    }
    else if (source) {
        realSource = source;
    }
    else {
        realSource = placeholderSource;
    }
    useEffect(() => {
        setIsError(false);
    }, [source]);
    return (<FastImage style={style} source={isError ? placeholderSource : realSource} onError={() => {
            setIsError(true);
            console.error("Image load error:", realSource);
        }} defaultSource={defaultSource}/>);
}
