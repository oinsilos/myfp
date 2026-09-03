import { StorageKeys, internalSerializeKey, supportLocalMediaType, } from "@/constants/commonConst";
import mp3Util from "@/native/mp3Util";
import { addFileScheme, getFileName } from "@/utils/fileUtils.ts";
import { getLocalPath, isSameMediaItem, } from "@/utils/mediaUtils";
import StateMapper from "@/utils/stateMapper";
import { getStorage, setStorage } from "@/utils/storage";
import CryptoJs from "crypto-js";
import { nanoid } from "nanoid";
import { useEffect, useState } from "react";
import { exists, readDir, unlink } from "react-native-fs";
let localSheet = [];
const localSheetStateMapper = new StateMapper(() => localSheet);
export async function setup() {
    const sheet = await getStorage(StorageKeys.LocalMusicSheet);
    if (sheet) {
        let validSheet = [];
        for (let musicItem of sheet) {
            const localPath = getLocalPath(musicItem);
            if (localPath && (await exists(localPath))) {
                validSheet.push(musicItem);
            }
        }
        if (validSheet.length !== sheet.length) {
            await setStorage(StorageKeys.LocalMusicSheet, validSheet);
        }
        localSheet = validSheet;
    }
    else {
        await setStorage(StorageKeys.LocalMusicSheet, []);
    }
    localSheetStateMapper.notify();
}
export async function addMusic(musicItem) {
    if (!Array.isArray(musicItem)) {
        musicItem = [musicItem];
    }
    let newSheet = [...localSheet];
    musicItem.forEach(mi => {
        if (localSheet.findIndex(_ => isSameMediaItem(mi, _)) === -1) {
            newSheet.push(mi);
        }
    });
    await setStorage(StorageKeys.LocalMusicSheet, newSheet);
    localSheet = newSheet;
    localSheetStateMapper.notify();
}
function addMusicDraft(musicItem) {
    if (!Array.isArray(musicItem)) {
        musicItem = [musicItem];
    }
    let newSheet = [...localSheet];
    musicItem.forEach(mi => {
        if (localSheet.findIndex(_ => isSameMediaItem(mi, _)) === -1) {
            newSheet.push(mi);
        }
    });
    localSheet = newSheet;
    localSheetStateMapper.notify();
}
async function saveLocalSheet() {
    await setStorage(StorageKeys.LocalMusicSheet, localSheet);
}
export async function removeMusic(musicItem, deleteOriginalFile = false) {
    const idx = localSheet.findIndex(_ => isSameMediaItem(_, musicItem));
    let newSheet = [...localSheet];
    if (idx !== -1) {
        const localMusicItem = localSheet[idx];
        newSheet.splice(idx, 1);
        const localPath = musicItem[internalSerializeKey]?.localPath ??
            localMusicItem[internalSerializeKey]?.localPath;
        if (deleteOriginalFile && localPath) {
            try {
                await unlink(localPath);
            }
            catch (e) {
                if (e.message !== "File does not exist") {
                    throw e;
                }
            }
        }
    }
    localSheet = newSheet;
    localSheetStateMapper.notify();
    saveLocalSheet();
}
function parseFilename(fn) {
    const data = fn.slice(0, fn.lastIndexOf(".")).split("@");
    const [platform, id, title, artist] = data;
    if (!platform || !id) {
        return null;
    }
    return {
        id,
        platform: platform,
        title: title ?? "",
        artist: artist ?? "",
    };
}
function localMediaFilter(filename) {
    return supportLocalMediaType.some(ext => filename.toLowerCase().endsWith(ext));
}
let importToken = null;
// 获取本地的文件列表
async function getMusicStats(folderPaths) {
    const _importToken = nanoid();
    importToken = _importToken;
    const musicList = [];
    let peek;
    let dirFiles = [];
    while (folderPaths.length !== 0) {
        if (importToken !== _importToken) {
            throw new Error("Import Broken");
        }
        peek = folderPaths.shift();
        try {
            dirFiles = await readDir(peek);
        }
        catch {
            dirFiles = [];
        }
        dirFiles.forEach(item => {
            if (item.isDirectory() && !folderPaths.includes(item.path)) {
                folderPaths.push(item.path);
            }
            else if (localMediaFilter(item.path)) {
                musicList.push(item.path);
            }
        });
    }
    return { musicList, token: _importToken };
}
function cancelImportLocal() {
    importToken = null;
}
// 导入本地音乐
const groupNum = 25;
async function importLocal(_folderPaths) {
    const folderPaths = [..._folderPaths.map(it => addFileScheme(it))];
    const { musicList, token } = await getMusicStats(folderPaths);
    if (token !== importToken) {
        throw new Error("Import Broken");
    }
    // 分组请求，不然序列化可能出问题
    let metas = [];
    const groups = Math.ceil(musicList.length / groupNum);
    for (let i = 0; i < groups; ++i) {
        metas = metas.concat(await mp3Util.getMediaMeta(musicList.slice(i * groupNum, (i + 1) * groupNum)));
    }
    if (token !== importToken) {
        throw new Error("Import Broken");
    }
    const musicItems = await Promise.all(musicList.map(async (musicPath, index) => {
        let { platform, id, title, artist } = parseFilename(getFileName(musicPath, true)) ?? {};
        const meta = metas[index];
        if (!platform || !id) {
            platform = "本地";
            id = CryptoJs.MD5(musicPath).toString(CryptoJs.enc.Hex);
        }
        return {
            id,
            platform,
            title: title ?? meta?.title ?? getFileName(musicPath),
            artist: artist ?? meta?.artist ?? "未知歌手",
            duration: parseInt(meta?.duration ?? "0", 10) / 1000,
            album: meta?.album ?? "未知专辑",
            artwork: "",
            [internalSerializeKey]: {
                localPath: musicPath,
            },
        };
    }));
    if (token !== importToken) {
        throw new Error("Import Broken");
    }
    addMusic(musicItems);
}
/** 是否为本地音乐 */
function isLocalMusic(musicItem) {
    return musicItem
        ? localSheet.find(_ => isSameMediaItem(_, musicItem))
        : undefined;
}
/** 状态-是否为本地音乐 */
function useIsLocal(musicItem) {
    const localMusicState = localSheetStateMapper.useMappedState();
    const [isLocal, setIsLocal] = useState(!!isLocalMusic(musicItem));
    useEffect(() => {
        if (!musicItem) {
            setIsLocal(false);
        }
        else {
            setIsLocal(!!isLocalMusic(musicItem));
        }
    }, [localMusicState, musicItem]);
    return isLocal;
}
function getMusicList() {
    return localSheet;
}
async function updateMusicList(newSheet) {
    const _localSheet = [...newSheet];
    try {
        await setStorage(StorageKeys.LocalMusicSheet, _localSheet);
        localSheet = _localSheet;
        localSheetStateMapper.notify();
    }
    catch { }
}
const LocalMusicSheet = {
    setup,
    addMusic,
    removeMusic,
    addMusicDraft,
    saveLocalSheet,
    importLocal,
    cancelImportLocal,
    isLocalMusic,
    useIsLocal,
    getMusicList,
    useMusicList: localSheetStateMapper.useMappedState,
    updateMusicList,
};
export default LocalMusicSheet;
