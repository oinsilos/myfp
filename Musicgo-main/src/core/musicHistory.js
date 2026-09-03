import { musicHistorySheetId } from "@/constants/commonConst";
import { isSameMediaItem } from "@/utils/mediaUtils";
import { getStorage } from "@/utils/storage";
import { atom, getDefaultStore, useAtomValue } from "jotai";
import appMeta from "./appMeta";
import getOrCreateMMKV from "@/utils/getOrCreateMMKV";
import { safeParse, safeStringify } from "@/utils/jsonUtil";
const musicHistoryAtom = atom([]);
const musicHistoryStore = getOrCreateMMKV("music.MusicHistory");
class MusicHistory {
    configService;
    injectDependencies(configService) {
        this.configService = configService;
    }
    get history() {
        return getDefaultStore().get(musicHistoryAtom);
    }
    async setup() {
        if (appMeta.historySheetVersion < 1) {
            await this.migrateToMMKV();
        }
        const history = safeParse(musicHistoryStore.getString("history") ?? "[]");
        getDefaultStore().set(musicHistoryAtom, history ?? []);
    }
    async addMusic(musicItem) {
        const newMusicHistory = [
            musicItem,
            ...this.history
                .filter(item => !isSameMediaItem(item, musicItem)),
        ].slice(0, this.configService.getConfig("basic.maxHistoryLen") ?? 50);
        musicHistoryStore.set("history", safeStringify(newMusicHistory));
        getDefaultStore().set(musicHistoryAtom, newMusicHistory);
    }
    async removeMusic(musicItem) {
        const newMusicHistory = this.history
            .filter(item => !isSameMediaItem(item, musicItem));
        musicHistoryStore.set("history", safeStringify(newMusicHistory));
        getDefaultStore().set(musicHistoryAtom, newMusicHistory);
    }
    async clearMusic() {
        musicHistoryStore.set("history", safeStringify([]));
        getDefaultStore().set(musicHistoryAtom, []);
    }
    async setHistory(newHistory) {
        musicHistoryStore.set("history", safeStringify(newHistory));
        getDefaultStore().set(musicHistoryAtom, newHistory);
    }
    async migrateToMMKV() {
        const history = await getStorage(musicHistorySheetId);
        if (history?.length) {
            musicHistoryStore.set("history", safeStringify(history));
        }
        appMeta.setHistorySheetVersion(1);
    }
}
export function useMusicHistory() {
    return useAtomValue(musicHistoryAtom);
}
const musicHistory = new MusicHistory();
export default musicHistory;
