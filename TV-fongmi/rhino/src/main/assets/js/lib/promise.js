// Rhino 极简 Promise 补丁 + __async(生成器驱动)。ES5 书写，体积优先。
(function () {
    var g = (typeof globalThis !== 'undefined' && globalThis) ? globalThis : this;
    if (typeof g.Promise === 'function' && typeof g.Promise.resolve === 'function') { return; }
    var arrayQ = [];
    var pending = false;
    function nextTick(fn) {
        if (typeof g.__tick === 'function') { g.__tick(fn); return; }
        if (typeof g.setTimeout === 'function') { g.setTimeout(fn, 0); return; }
        fn();
    }
    function flush() {
        pending = false;
        var q = arrayQ;
        arrayQ = [];
        for (var i = 0; i < q.length; i++) {
            try { q[i](); } catch (e) { if (g.console && g.console.error) g.console.error(String(e)); }
        }
    }
    function schedule(fn) {
        arrayQ.push(fn);
        if (!pending) { pending = true; nextTick(flush); }
    }
    function Promise(executor) {
        var self = this;
        if (!(self instanceof Promise)) throw new TypeError('Promise must be constructed via new');
        self._s = 0; self._v = undefined; self._q = [];
        function resolve(v) {
            if (self._s !== 0) return;
            if (v === self) { reject(new TypeError('chaining cycle')); return; }
            if (v && (typeof v === 'object' || typeof v === 'function')) {
                var then;
                try { then = v.then; } catch (e) { reject(e); return; }
                if (typeof then === 'function') { settle(then, v, resolve, reject); return; }
            }
            self._s = 1; self._v = v; fire();
        }
        function reject(e) { if (self._s !== 0) return; self._s = 2; self._v = e; fire(); }
        function fire() {
            schedule(function () {
                var q = self._q;
                self._q = [];
                for (var i = 0; i < q.length; i++) q[i]();
            });
        }
        try { executor(resolve, reject); } catch (e) { reject(e); }
    }
    function settle(then, v, resolve, reject) {
        var called = false;
        try {
            then.call(v,
                function (x) { if (!called) { called = true; resolve(x); } },
                function (e) { if (!called) { called = true; reject(e); } });
        } catch (e) { if (!called) { called = true; reject(e); } }
    }
    Promise.prototype.then = function (onF, onR) {
        var self = this;
        onF = typeof onF === 'function' ? onF : null;
        onR = typeof onR === 'function' ? onR : null;
        return new Promise(function (resolve, reject) {
            var done = false;
            function handler() {
                if (done) return;
                done = true;
                var cb = self._s === 1 ? onF : onR;
                var val = self._v;
                if (!cb) { if (self._s === 1) resolve(val); else reject(val); return; }
                var out;
                try { out = cb(val); } catch (e) { reject(e); return; }
                resolve(out);
            }
            if (self._s === 0) self._q.push(handler); else schedule(handler);
        });
    };
    Promise.prototype['catch'] = function (onR) { return this.then(null, onR); };
    Promise.prototype['finally'] = function (cb) {
        return this.then(
            function (v) { var r; try { r = cb(); } catch (e) { return Promise.reject(e); } return r && r.then ? r.then(function () { return v; }) : v; },
            function (e) { var r; try { r = cb(); } catch (e2) { return Promise.reject(e2); } return r && r.then ? r.then(function () { throw e; }) : Promise.reject(e); });
    };
    Promise.resolve = function (v) {
        if (v instanceof Promise) return v;
        if (v && (typeof v === 'object' || typeof v === 'function') && typeof v.then === 'function') return new Promise(function (res, rej) { v.then(res, rej); });
        return new Promise(function (res) { res(v); });
    };
    Promise.reject = function (e) { return new Promise(function (res, rej) { rej(e); }); };
    Promise.all = function (arr) {
        return new Promise(function (res, rej) {
            var n = arr.length, out = new Array(n), left = n, i;
            if (!n) { res(out); return; }
            function done(k, v) {
                out[k] = v;
                if (--left === 0) res(out);
            }
            for (i = 0; i < n; i++) (function (i) { Promise.resolve(arr[i]).then(function (v) { done(i, v); }, rej); })(i);
        });
    };
    Promise.race = function (arr) {
        return new Promise(function (res, rej) {
            for (var i = 0; i < arr.length; i++) Promise.resolve(arr[i]).then(res, rej);
        });
    };
    g.Promise = Promise;
    // 生成器驱动的 async 支持（配合 Transpile 把 async/await 转为 generator + __async）
    g.__async = function (genFn, ctx) {
        var generator = genFn();
        return new Promise(function (resolve, reject) {
            function step(k, arg) {
                var r;
                try { r = generator[k](arg); } catch (e) { reject(e); return; }
                if (r.done) { resolve(r.value); return; }
                Promise.resolve(r.value).then(function (v) { step('next', v); }, function (e) { step('throw', e); });
            }
            step('next');
        });
    };
    g.global = g.window = g.self = g;
})();