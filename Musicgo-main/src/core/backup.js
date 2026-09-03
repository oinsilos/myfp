/** 备份与恢复 */
/** 歌单、插件 */
import { compare } from "compare-versions";
import PluginManager from "./pluginManager";
import MusicSheet from "@/core/musicSheet";
function backup() {
    const musicSheets = MusicSheet.backupSheets();
    const plugins = PluginManager.getEnabledPlugins();
    const normalizedPlugins = plugins.map(_ => ({
        srcUrl: _.instance.srcUrl,
        version: _.instance.version,
    }));
    return JSON.stringify({
        musicSheets: musicSheets,
        plugins: normalizedPlugins,
    });
}
async function resume(raw, resumeMode = "append" /* ResumeMode.Append */) {
    let obj;
    if (typeof raw === "string") {
        obj = JSON.parse(raw);
    }
    else {
        obj = raw;
    }
    const { plugins, musicSheets } = obj ?? {};
    /** 恢复插件 */
    const validPlugins = PluginManager.getEnabledPlugins();
    const resumePlugins = plugins?.map(_ => {
        // 校验是否安装过: 同源且本地版本更高就忽略掉
        if (validPlugins.find(plugin => plugin.instance.srcUrl === _.srcUrl &&
            compare(plugin.instance.version ?? "0.0.0", _.version ?? "0.0.1", ">="))) {
            return;
        }
        return PluginManager.installPluginFromUrl(_.srcUrl);
    });
    /** 恢复歌单 */
    const resumeMusicSheets = MusicSheet.resumeSheets(musicSheets, resumeMode);
    return Promise.all([...(resumePlugins ?? []), resumeMusicSheets]);
}
const Backup = {
    backup,
    resume,
};
export default Backup;
