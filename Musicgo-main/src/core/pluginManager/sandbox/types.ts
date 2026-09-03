/**
 * MusicFree 插件运行时规范（引擎无关）。
 *
 * P2·JS 引擎收敛：把「宿主能力注入」与「源码求值」抽象为两个接口，
 * 插件定义（IPluginDefine）无论由 RN/Hermes（Function 求值）还是后续
 * Rhino 引擎加载，宿主桥（IPluginHost）与沙箱（IPluginSandbox）都按同一份
 * 契约实现。上层领域逻辑（Plugin / PluginMethodsWrapper）只依赖本契约，
 * 引擎迁移时无需改动业务代码。
 */

/** 注入给插件的 env 对象：userVariables 懒求值，插件加载完成后仍可读到用户配置 */
export interface IPluginHostEnv {
    /** 实时读取当前插件的用户自定义变量 */
    getUserVariables(): Record<string, string>;
    /** 便捷读法 env.userVariables（懒求值） */
    readonly userVariables: Record<string, string>;
    appVersion: string;
    /** 运行平台固定为 android */
    os: string;
    lang: string;
}

/** 宿主注入的控制台能力 */
export interface IPluginHostConsole {
    log(...args: any[]): void;
    warn(...args: any[]): void;
    info(...args: any[]): void;
    error(...args: any[]): void;
}

/** 注入给插件的 process 对象（精简子集，含 env） */
export interface IPluginHostProcess {
    platform: string;
    version: string;
    env: IPluginHostEnv;
}

/**
 * 宿主为插件提供的能力集合，与具体 JS 引擎（Hermes / JSC / Rhino）无关。
 * 注：URL 为全局构造器，类型上直接复用 ES global 的 typeof URL。
 */
export interface IPluginHost {
    /** CommonJS require：解析宿主内置库（axios/cheerio/crypto-js/dayjs/big-integer/qs/he/webdav 等） */
    require(packageName: string): any;
    /** 兼容别名：部分旧插件以 __musicfree_require 引用宿主库 */
    __musicfreeRequire(packageName: string): any;
    console: IPluginHostConsole;
    env: IPluginHostEnv;
    URL: typeof URL;
    process: IPluginHostProcess;
}

/**
 * 插件沙箱：求值插件源码，返回插件定义。
 * 当前实现为 RN 端 Function 求值（EvalSandbox）；
 * 迁移 Rhino 时按本接口实现 RhinoSandbox.load 即可，上层零改动。
 */
export interface IPluginSandbox {
    load(code: string, host: IPluginHost): IPlugin.IPluginDefine;
}