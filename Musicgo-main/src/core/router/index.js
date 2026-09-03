import { useNavigation, useRoute } from "@react-navigation/native";
import { useCallback } from "react";
import { LogBox } from "react-native";
LogBox.ignoreLogs([
    "Non-serializable values were found in the navigation state",
]);
/** 路由key */
export const ROUTE_PATH = {
    /** 主页 */
    HOME: "home",
    /** 音乐播放页 */
    MUSIC_DETAIL: "music-detail",
    /** 搜索页 */
    SEARCH_PAGE: "search-page",
    /** 本地歌单页 */
    LOCAL_SHEET_DETAIL: "local-sheet-detail",
    /** 专辑页 */
    ALBUM_DETAIL: "album-detail",
    /** 歌手页 */
    ARTIST_DETAIL: "artist-detail",
    /** 榜单页 */
    TOP_LIST: "top-list",
    /** 榜单详情页 */
    TOP_LIST_DETAIL: "top-list-detail",
    /** 设置页 */
    SETTING: "setting",
    /** 本地音乐 */
    LOCAL: "local",
    /** 正在下载 */
    DOWNLOADING: "downloading",
    /** 从歌曲列表中搜索 */
    SEARCH_MUSIC_LIST: "search-music-list",
    /** 批量编辑 */
    MUSIC_LIST_EDITOR: "music-list-editor",
    /** 选择文件夹 */
    FILE_SELECTOR: "file-selector",
    /** 推荐歌单 */
    RECOMMEND_SHEETS: "recommend-sheets",
    /** 歌单详情 */
    PLUGIN_SHEET_DETAIL: "plugin-sheet-detail",
    /** 历史记录 */
    HISTORY: "history",
    /** 自定义主题 */
    SET_CUSTOM_THEME: "set-custom-theme",
    /** 权限管理 */
    PERMISSIONS: "permissions",
};
/** 路由参数Hook */
export function useParams() {
    const route = useRoute();
    const routeParams = route?.params;
    return routeParams;
}
/** 导航 */
export function useNavigate() {
    const navigation = useNavigation();
    const navigate = useCallback(function (route, params) {
        navigation.navigate(route, params);
    }, []);
    return navigate;
}
