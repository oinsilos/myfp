/**
 * MusicFree 插件运行时规范（引擎无关）。
 *
 * P2·JS 引擎收敛：把「宿主能力注入」与「源码求值」抽象为两个接口，
 * 插件定义（IPluginDefine）无论由 RN/Hermes（Function 求值）还是后续
 * Rhino 引擎加载，宿主桥（IPluginHost）与沙箱（IPluginSandbox）都按同一份
 * 契约实现。上层领域逻辑（Plugin / PluginMethodsWrapper）只依赖本契约，
 * 引擎迁移时无需改动业务代码。
 */
export {};
