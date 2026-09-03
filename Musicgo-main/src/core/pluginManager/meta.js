import getOrCreateMMKV from "@/utils/getOrCreateMMKV";
import { safeParse, safeStringify } from "@/utils/jsonUtil";
import { errorLog } from "@/utils/log";
import { getStorage, removeStorage } from "@/utils/storage";
const storage = getOrCreateMMKV("plugin-meta");
class PluginMeta {
    cachedDisabledPlugins = null;
    getMetaStorage(key) {
        return safeParse(storage.getString(key));
    }
    setMetaStorage(key, value) {
        const storageValue = safeStringify(value);
        storage.set(key, storageValue);
    }
    async migratePluginMeta() {
        const metaVersion = storage.getNumber("$version") ?? -1;
        if (metaVersion < 0) {
            // 从async storage迁移到mmkv
            try {
                const rawMeta = await getStorage("plugin-meta");
                const order = {};
                const disabledPlugins = new Set();
                if (rawMeta !== null) {
                    for (let platformName in rawMeta) {
                        const metaVal = rawMeta[platformName];
                        if (!metaVal) {
                            continue;
                        }
                        if (metaVal?.order !== undefined && metaVal.order !== null) {
                            order[platformName] = metaVal.order;
                        }
                        if (metaVal?.enabled !== undefined && metaVal.enabled === false) {
                            disabledPlugins.add(platformName);
                        }
                        if (metaVal?.userVariables !== undefined && metaVal.userVariables !== null) {
                            storage.set(platformName + ".userVariables", safeStringify(metaVal.userVariables));
                        }
                    }
                }
                // 将 order 和 disabledPlugins 存储到 mmkv
                storage.set("order", safeStringify(order));
                storage.set("disabledPlugins", safeStringify(Array.from(disabledPlugins)));
                // 移除
                await removeStorage("plugin-meta");
            }
            catch (e) {
                errorLog("迁移 plugin meta 失败", e);
            }
            storage.set("$version", 1);
        }
    }
    getPluginOrder() {
        return this.getMetaStorage("order") ?? {};
    }
    setPluginOrder(orderMap) {
        this.setMetaStorage("order", orderMap);
    }
    get disabledPlugins() {
        if (this.cachedDisabledPlugins) {
            return this.cachedDisabledPlugins;
        }
        const disabledPlugins = this.getMetaStorage("disabledPlugins") ?? [];
        this.cachedDisabledPlugins = new Set(disabledPlugins);
        return this.cachedDisabledPlugins;
    }
    isPluginEnabled(pluginPlatform) {
        const disabledPluginsSet = this.disabledPlugins;
        return !disabledPluginsSet.has(pluginPlatform);
    }
    setPluginEnabled(pluginPlatform, enabled) {
        const disabledPluginsSet = this.disabledPlugins;
        if (enabled) {
            disabledPluginsSet.delete(pluginPlatform);
        }
        else {
            disabledPluginsSet.add(pluginPlatform);
        }
        this.setMetaStorage("disabledPlugins", Array.from(disabledPluginsSet));
        this.cachedDisabledPlugins = disabledPluginsSet;
    }
    getUserVariables(pluginPlatform) {
        const userVariables = this.getMetaStorage(`${pluginPlatform}.userVariables`) ?? {};
        return userVariables;
    }
    setUserVariables(pluginPlatform, userVariables) {
        this.setMetaStorage(`${pluginPlatform}.userVariables`, userVariables);
    }
    setAlternativePlugin(pluginPlatform, alternativePluginPlatform) {
        this.setMetaStorage(`${pluginPlatform}.alternativePlugin`, alternativePluginPlatform);
    }
    getAlternativePlugin(pluginPlatform) {
        const alternativePlugin = this.getMetaStorage(`${pluginPlatform}.alternativePlugin`);
        if (alternativePlugin) {
            return alternativePlugin;
        }
        return null;
    }
}
const _internalPluginMeta = new PluginMeta();
export default _internalPluginMeta;
