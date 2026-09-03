// 源装载后的 __JS_SPIDER__ 解析步骤（ES5，Rhino 兼容）。
(function () {
    var g = (typeof globalThis !== 'undefined' && globalThis) ? globalThis : this;
    if (typeof g.__JS_SPIDER__ !== 'undefined') return;
    var mod = (typeof g.__module !== 'undefined' && g.__module) ? g.__module.exports : {};
    var jsRew = mod.__jsEvalReturn;
    if (typeof jsRew === 'function') {
        g.req = g.http;
        g.__JS_SPIDER__ = jsRew();
    } else if (typeof mod.default === 'function') {
        g.__JS_SPIDER__ = mod.default();
    } else if (mod.default !== undefined) {
        g.__JS_SPIDER__ = mod.default;
    } else if (jsRew !== undefined) {
        g.__JS_SPIDER__ = jsRew;
    }
})();