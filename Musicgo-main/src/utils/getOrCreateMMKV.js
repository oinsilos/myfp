import pathConst from "@/constants/pathConst";
import { MMKV } from "react-native-mmkv";
const _mmkvCache = {};
// @ts-ignore;
global.mmkv = _mmkvCache;
// Internal Method
const getOrCreateMMKV = (dbName, cachePath = false) => {
    if (_mmkvCache[dbName]) {
        return _mmkvCache[dbName];
    }
    const newStore = new MMKV({
        id: dbName,
        path: cachePath ? pathConst.mmkvCachePath : pathConst.mmkvPath,
    });
    _mmkvCache[dbName] = newStore;
    return newStore;
};
export default getOrCreateMMKV;
