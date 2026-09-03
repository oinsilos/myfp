import { errorLog } from "@/utils/log";
import AsyncStorage from "@react-native-async-storage/async-storage";
export async function setStorage(key, value) {
    try {
        await AsyncStorage.setItem(key, JSON.stringify(value, null, ""));
    }
    catch (e) {
        errorLog(`存储失败${key}`, e?.message);
    }
}
export async function getStorage(key) {
    try {
        const result = await AsyncStorage.getItem(key);
        if (result) {
            return JSON.parse(result);
        }
    }
    catch { }
    return null;
}
export async function getMultiStorage(keys) {
    if (keys.length === 0) {
        return [];
    }
    const result = await AsyncStorage.multiGet(keys);
    return result.map(_ => {
        try {
            if (_[1]) {
                return JSON.parse(_[1]);
            }
            return null;
        }
        catch {
            return null;
        }
    });
}
export async function removeStorage(key) {
    return AsyncStorage.removeItem(key);
}
