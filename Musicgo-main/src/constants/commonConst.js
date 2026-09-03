import { Easing } from "react-native-reanimated";
export const internalSymbolKey = Symbol.for("$");
// 加入播放列表的时间；app内使用，无法被序列化
export const timeStampSymbol = Symbol.for("time-stamp");
// 加入播放列表的辅助顺序
export const sortIndexSymbol = Symbol.for("sort-index");
export const internalSerializeKey = "$";
export const localMusicSheetId = "local-music-sheet";
export const musicHistorySheetId = "history-music-sheet";
export const localPluginPlatform = "本地";
export const localPluginHash = "local-plugin-hash";
export const internalFakeSoundKey = "fake-key";
const emptyFunction = () => { };
Object.freeze(emptyFunction);
export { emptyFunction };
export var RequestStateCode;
(function (RequestStateCode) {
    /** 空闲 */
    RequestStateCode[RequestStateCode["IDLE"] = 0] = "IDLE";
    RequestStateCode[RequestStateCode["PENDING_FIRST_PAGE"] = 2] = "PENDING_FIRST_PAGE";
    RequestStateCode[RequestStateCode["LOADING"] = 2] = "LOADING";
    /** 检索中 */
    RequestStateCode[RequestStateCode["PENDING_REST_PAGE"] = 3] = "PENDING_REST_PAGE";
    /** 部分结束 */
    RequestStateCode[RequestStateCode["PARTLY_DONE"] = 4] = "PARTLY_DONE";
    /** 全部结束 */
    RequestStateCode[RequestStateCode["FINISHED"] = 8] = "FINISHED";
    /** 出错了 */
    RequestStateCode[RequestStateCode["ERROR"] = 128] = "ERROR";
})(RequestStateCode || (RequestStateCode = {}));
export const StorageKeys = {
    /** @deprecated */
    MediaMetaKeys: "media-meta-keys",
    PluginMetaKey: "plugin-meta",
    MediaCache: "media-cache",
    LocalMusicSheet: "local-music-sheet",
};
export const CacheControl = {
    Cache: "cache",
    NoCache: "no-cache",
    NoStore: "no-store",
};
export const supportLocalMediaType = [
    ".mp3",
    ".flac",
    ".wma",
    ".wav",
    ".m4a",
    ".ogg",
    ".acc",
    ".aac",
    ".ape",
    ".opus",
];
const ANIMATION_EASING = Easing.out(Easing.exp);
const ANIMATION_DURATION = 150;
const animationFast = {
    duration: ANIMATION_DURATION,
    easing: ANIMATION_EASING,
};
const animationNormal = {
    duration: 250,
    easing: ANIMATION_EASING,
};
const animationSlow = {
    duration: 500,
    easing: ANIMATION_EASING,
};
export const timingConfig = {
    animationFast,
    animationNormal,
    animationSlow,
};
