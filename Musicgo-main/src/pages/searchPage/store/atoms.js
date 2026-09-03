import { atom } from "jotai";
/** 初始值 */
export const initSearchResults = {
    music: {},
    album: {},
    artist: {},
    sheet: {},
    lyric: {},
};
/** key: pluginhash value: searchResult */
const searchResultsAtom = atom(initSearchResults);
export var PageStatus;
(function (PageStatus) {
    /** 编辑中 */
    PageStatus["EDITING"] = "EDITING";
    /** 搜索中 */
    PageStatus["SEARCHING"] = "SEARCHING";
    /** 有结果 */
    PageStatus["RESULT"] = "RESULT";
    /** 没有安装插件 */
    PageStatus["NO_PLUGIN"] = "NO_PLUGIN";
})(PageStatus || (PageStatus = {}));
/** 当前正在搜索的 */
const pageStatusAtom = atom(PageStatus.EDITING);
const queryAtom = atom("");
export { pageStatusAtom, searchResultsAtom, queryAtom };
