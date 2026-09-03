import { useEffect, useRef } from "react";
import { BackHandler } from "react-native";
export default function (onHardwareBackPress, deps = []) {
    const backHandlerRef = useRef();
    useEffect(() => {
        if (backHandlerRef.current) {
            backHandlerRef.current.remove();
            backHandlerRef.current = undefined;
        }
        backHandlerRef.current = BackHandler.addEventListener("hardwareBackPress", onHardwareBackPress);
        return () => {
            if (backHandlerRef.current) {
                backHandlerRef.current.remove();
                backHandlerRef.current = undefined;
            }
        };
    }, deps);
}
