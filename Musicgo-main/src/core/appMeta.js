import getOrCreateMMKV from "@/utils/getOrCreateMMKV";
class AppMeta {
    getAppMeta(key) {
        const metaMMKV = getOrCreateMMKV("App.meta");
        return metaMMKV.getString(key);
    }
    setAppMeta(key, value) {
        const metaMMKV = getOrCreateMMKV("App.meta");
        return metaMMKV.set(key, value);
    }
    /// 歌单的版本号
    get musicSheetVersion() {
        const version = this.getAppMeta("MusicSheetVersion");
        if (version?.length) {
            return +version;
        }
        return 0;
    }
    setMusicSheetVersion(version) {
        this.setAppMeta("MusicSheetVersion", "" + version);
    }
    get historySheetVersion() {
        const version = this.getAppMeta("HistorySheetVersion");
        if (version?.length) {
            return +version;
        }
        return 0;
    }
    setHistorySheetVersion(version) {
        this.setAppMeta("HistorySheetVersion", "" + version);
    }
}
const appMeta = new AppMeta();
export default appMeta;
