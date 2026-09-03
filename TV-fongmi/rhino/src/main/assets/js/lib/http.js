// http 桥：同步 req() + Promise http()，ES5 书写（Rhino 兼容）。
(function () {
    var g = (typeof globalThis !== 'undefined' && globalThis) ? globalThis : this;
    function assign(target) {
        for (var i = 1; i < arguments.length; i++) {
            var s = arguments[i];
            if (!s) continue;
            for (var k in s) if (Object.prototype.hasOwnProperty.call(s, k)) target[k] = s[k];
        }
        return target;
    }
    g.http = function (url, options) {
        options = options || {};
        if (options.async === false) return g._http(url, options);
        return new g.Promise(function (resolve) {
            g._http(url, assign({ complete: function (res) { resolve(res); } }, options));
        })['catch'](function (err) {
            if (g.console && g.console.error) g.console.error(err && err.name, err && err.message, err && err.stack);
            return { ok: false, status: 500, url: url };
        });
    };
    g.req = function (url, options) {
        return g.http(url, assign({ async: false }, options));
    };
    var names = ['global', 'window', 'self'];
    for (var i = 0; i < names.length; i++) {
        if (names[i] in g) continue;
        try {
            Object.defineProperty(g, names[i], {
                enumerable: true,
                configurable: true,
                get: function () { return g; },
                set: function () { }
            });
        } catch (e) {
            g[names[i]] = g;
        }
    }
})();