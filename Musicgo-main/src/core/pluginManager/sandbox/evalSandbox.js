/**
 * RN 端沙箱：以 Function 求值插件源码（当前 JS 引擎为 Hermes/JSC）。
 * 注入参数与原实现保持一致：
 * require, __musicfree_require, module, exports, console, env, URL, process。
 * 迁移 Rhino 时按 IPluginSandbox 契约另写沙箱实现，上层 Plugin 类无需改动。
 */
export class EvalSandbox {
    load(code, host) {
        const module = { exports: {} };
        // eslint-disable-next-line no-new-func
        const factory = Function(`
            'use strict';
            return function(require, __musicfree_require, module, exports, console, env, URL, process) {
                ${code}
            }
        `)();
        factory(host.require, host.__musicfreeRequire, module, module.exports, host.console, host.env, host.URL, host.process);
        if (module.exports.default) {
            return module.exports.default;
        }
        return module.exports;
    }
}
/** 默认单例：RN 端插件加载唯一入口（Rhino 迁移时替换注入点） */
export const evalSandbox = new EvalSandbox();
