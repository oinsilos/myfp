import { Dimensions } from "react-native";
const windowWidth = Dimensions.get("window").width;
const windowHeight = Dimensions.get("window").height;
const minWindowEdge = Math.min(windowHeight, windowWidth);
const maxWindowEdge = Math.max(windowHeight, windowWidth);
export default function (rpx) {
    return (rpx / 750) * minWindowEdge;
}
export function vh(pct) {
    return (pct / 100) * Dimensions.get("window").height;
}
export function vw(pct) {
    return (pct / 100) * Dimensions.get("window").width;
}
export function vmin(pct) {
    return (pct / 100) * minWindowEdge;
}
export function vmax(pct) {
    return (pct / 100) * maxWindowEdge;
}
export function sh(pct) {
    return (pct / 100) * Dimensions.get("screen").height;
}
export function sw(pct) {
    return (pct / 100) * Dimensions.get("screen").width;
}
