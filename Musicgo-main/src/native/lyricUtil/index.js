import Config from "@/core/appConfig";
import Toast from "@/utils/toast";
import { NativeModules } from "react-native";
import { errorLog } from "@/utils/log.ts";
export var NativeTextAlignment;
(function (NativeTextAlignment) {
    // 左对齐
    NativeTextAlignment[NativeTextAlignment["LEFT"] = 3] = "LEFT";
    // 右对齐
    NativeTextAlignment[NativeTextAlignment["RIGHT"] = 5] = "RIGHT";
    // 居中
    NativeTextAlignment[NativeTextAlignment["CENTER"] = 17] = "CENTER";
})(NativeTextAlignment || (NativeTextAlignment = {}));
const LyricUtil = NativeModules.LyricUtil;
const originalShowStatusBarLyric = LyricUtil.showStatusBarLyric;
const showStatusBarLyric = async (initLyric, config) => {
    try {
        await originalShowStatusBarLyric(initLyric, config);
    }
    catch (e) {
        errorLog("状态栏歌词开启失败", e);
        Toast.warn("状态栏歌词开启失败，请到手机系统设置打开悬浮窗权限");
        Config.setConfig("lyric.showStatusBarLyric", false);
    }
};
LyricUtil.showStatusBarLyric = showStatusBarLyric;
export default LyricUtil;
