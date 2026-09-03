import getOrCreateMMKV from "@/utils/getOrCreateMMKV.ts";
import { InteractionManager } from "react-native";
import { safeParse, safeStringify } from "@/utils/jsonUtil";
function getStorageData(key) {
    const mmkv = getOrCreateMMKV(`LocalSheet.${key}`);
    return safeParse(mmkv.getString("data"));
}
async function setStorageData(key, value) {
    return InteractionManager.runAfterInteractions(() => {
        const mmkv = getOrCreateMMKV(`LocalSheet.${key}`);
        mmkv.set("data", safeStringify(value));
    });
}
function removeStorageData(key) {
    const mmkv = getOrCreateMMKV(`LocalSheet.${key}`);
    mmkv.clearAll();
}
/**
 * 存储歌单的基本信息
 * @param sheets 歌单数据
 */
async function setSheets(sheets) {
    return await setStorageData("music-sheets", sheets);
}
/**
 * 获取歌单的基本信息
 */
function getSheets() {
    return getStorageData("music-sheets");
}
/**
 * 存储歌单的基本信息
 * @param sheets 歌单数据
 */
async function setStarredSheets(sheets) {
    return await setStorageData("starred-sheets", sheets);
}
/**
 * 获取歌单的基本信息
 */
function getStarredSheets() {
    return getStorageData("starred-sheets");
}
/**
 * 存储歌单内的歌曲
 * @param sheetId 歌单id
 * @param musicList 歌曲列表
 */
async function setMusicList(sheetId, musicList) {
    return await setStorageData(sheetId, musicList);
}
/**
 * 获取歌单内的歌曲
 * @param sheetId 歌单id
 * @returns 歌曲列表
 */
function getMusicList(sheetId) {
    return getStorageData(sheetId);
}
/**
 * 清空歌单内的歌曲/其他信息
 * @param sheetId
 */
function removeMusicList(sheetId) {
    return removeStorageData(sheetId);
}
function setSheetMeta(sheetId, key, value) {
    const mmkv = getOrCreateMMKV(`LocalSheet.${sheetId}`);
    mmkv.set("meta." + key, value);
}
function getSheetMeta(sheetId, key) {
    const mmkv = getOrCreateMMKV(`LocalSheet.${sheetId}`);
    return mmkv.getString("meta." + key) || null;
}
const storage = {
    setSheets,
    getSheets,
    setMusicList,
    getMusicList,
    removeMusicList,
    setSheetMeta,
    getSheetMeta,
    setStarredSheets,
    getStarredSheets,
};
export default storage;
