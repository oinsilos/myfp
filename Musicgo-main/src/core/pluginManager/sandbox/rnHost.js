import axios from "axios";
import bigInt from "big-integer";
import * as cheerio from "cheerio";
import CryptoJs from "crypto-js";
import dayjs from "dayjs";
import he from "he";
import qs from "qs";
import { default as DeviceInfo } from "react-native-device-info";
import { URL } from "react-native-url-polyfill";
import * as webdav from "webdav";
import { devLog } from "@/utils/log";
import notImplementedFunction from "@/utils/notImplementedFunction.ts";
// 宿主网络栈一次性引导（模块级副作用，与原插件加载路径一致）
axios.defaults.timeout = 2000;
axios.interceptors.response.use((response) => {
    // 统一 set-cookie 格式：nodejs 环境是数组，移动端环境都放在第一个元素
    const setCookie = response.headers["set-cookie"];
    if (setCookie && setCookie.length === 1) {
        const splitedCookie = setCookie[0].split(",");
        response.headers["set-cookie"] = splitedCookie;
        response.headers["x-set-cookie"] = setCookie;
    }
    return response;
});
const appVersion = DeviceInfo.getVersion();
const deprecatedCookieManager = {
    get: notImplementedFunction,
    set: notImplementedFunction,
    flush: notImplementedFunction,
};
/**
 * 插件内置依赖表：RN 宿主在 bundle 中解析这些库；
 * 迁移 Rhino 后按同一键表替换为 Rhino 可用的实现（详见 P2 JS 引擎收敛计划）。
 */
const packages = {
    cheerio,
    "crypto-js": CryptoJs,
    axios,
    dayjs,
    "big-integer": bigInt,
    qs,
    he,
    "@react-native-cookies/cookies": deprecatedCookieManager,
    webdav,
};
/**
 * 构建 RN 宿主插件运行环境。
 * 返回对象只含纯 JS 能力，可被任意 JS 引擎消费；
 * 迁移 Rhino 时只需另写 createRhinoPluginHost，插件侧契约不变。
 */
export function createRnPluginHost(options) {
    const requireFn = (packageName) => {
        const pkg = packages[packageName];
        pkg.default = pkg;
        return pkg;
    };
    const consoleBind = (method, ...args) => {
        const fn = console[method];
        if (fn) {
            fn(...args);
            devLog(method, ...args);
        }
    };
    const env = {
        getUserVariables: options.getUserVariables,
        get userVariables() {
            return this.getUserVariables() ?? {};
        },
        appVersion,
        os: "android",
        lang: "zh-CN",
    };
    const consoleObj = {
        log: consoleBind.bind(null, "log"),
        warn: consoleBind.bind(null, "warn"),
        info: consoleBind.bind(null, "info"),
        error: consoleBind.bind(null, "error"),
    };
    const processObj = {
        platform: "android",
        version: appVersion,
        env,
    };
    return {
        require: requireFn,
        __musicfreeRequire: requireFn,
        console: consoleObj,
        env,
        URL,
        process: processObj,
    };
}
