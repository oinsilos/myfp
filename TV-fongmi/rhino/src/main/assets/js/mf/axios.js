// MusicFree 插件 axios 兼容层：基于 Rhino 桥的全局 req()/http() 实现，ES5 书写。
// 覆盖插件常用子集：get/post/put(降级 post)/delete(降级 get)/create/params/headers/json 自动解析。
(function () {
    var g = (typeof globalThis !== 'undefined' && globalThis) ? globalThis : this;

    function each(obj, fn) {
        if (!obj) return;
        var ks = [];
        for (var k in obj) if (Object.prototype.hasOwnProperty.call(obj, k)) ks.push(k);
        for (var i = 0; i < ks.length; i++) fn(obj[ks[i]], ks[i]);
    }

    function merge() {
        var t = {};
        for (var i = 0; i < arguments.length; i++) each(arguments[i], function (v, k) { t[k] = v; });
        return t;
    }

    function appendParams(url, params) {
        if (!params) return url;
        var parts = [];
        each(params, function (v, k) {
            if (v === undefined || v === null) return;
            if (Array.isArray(v)) {
                for (var i = 0; i < v.length; i++) parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v[i]));
            } else {
                parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
            }
        });
        if (!parts.length) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
    }

    function parseData(text, headers) {
        if (text === undefined || text === null || text === '') return text;
        if (typeof text === 'string') {
            var ct = (headers && (headers['Content-Type'] || headers['content-type'])) || '';
            var head = text.charAt(0);
            if (ct.indexOf('json') >= 0 || head === '{' || head === '[') {
                try { return JSON.parse(text); } catch (e) { /* 保留原文 */ }
            }
        }
        return text;
    }

    function makeError(message, config, response) {
        var e = new Error(message);
        e.isAxiosError = true;
        e.config = config;
        if (response) e.response = response;
        return e;
    }

    function request(config) {
        config = merge({ method: 'get', timeout: 10000 }, config);
        var url = appendParams(config.url, config.params);
        var headers = merge(config.headers || {});
        var options = { method: config.method, timeout: config.timeout };
        if (config.data !== undefined && config.data !== null) {
            if (typeof config.data === 'string') {
                options.body = config.data;
                if (!(headers['Content-Type'] || headers['content-type'])) headers['Content-Type'] = 'application/x-www-form-urlencoded';
            } else {
                options.data = config.data;
                options.postType = 'json';
            }
        }
        var requestHeaders = headers;
        options.header = headers;
        // 宿主桥 Req 模型只认复数 headers（单数 header 会被忽略），双写保证自定义头（UA/Referer）生效
        options.headers = headers;
        // 统一走异步 http 桥（OkHttp enqueue，回调再泵回沙箱线程）——并发改造核心：
        // 原来这里用同步 req()，整个 HTTP 往返期间沙箱单线程被 execute() 阻塞，
        // 歌单详情等大响应（数百 KB）期间，搜索/播放取 URL/歌词等所有调用只能排队，
        // 表现就是「点个歌单，之后点什么都转圈/卡住」。
        // 异步化后：网络 I/O 由 OkHttp 连接池并行执行，沙箱线程只在「发请求」与「收响应回调」
        // 两个瞬时被占用（非阻塞），多个请求交错推进，单沙箱也能并发跑。
        // 注意：req()（同步）仅留给必须同步的旧代码，插件契约一律走 http()。
        return new g.Promise(function (resolve, reject) {
            var call = g.http(url, options);
            if (call && typeof call.then === 'function') {
                call.then(
                    function (res) { settle(res, resolve, reject); },
                    function (err) {
                        // http 桥对网络失败会兜底返回 {ok:false,status:500}，按响应处理走 settled；
                        // 真正的异常（如调用栈错误）才直接 reject。保证不丢错误信息。
                        if (err && err.ok === false) settle(err, resolve, reject);
                        else reject(err);
                    });
            } else {
                settle(call, resolve, reject);
            }
        });
    }

    function settle(res, resolve, reject) {
        if (!res || res.content === undefined) {
            reject(makeError('network error: ' + (res && res.url || ''), null, null));
            return;
        }
        var code = (res.code === undefined || res.code === '') ? 200 : Number(res.code);
        var response = {
            data: parseData(res.content, res.headers || {}),
            status: code,
            statusText: res.statusText,
            headers: res.headers || {},
            config: null,
            request: null
        };
        if (code >= 200 && code < 300) {
            resolve(response);
        } else {
            reject(makeError('Request failed with status code ' + code, null, response));
        }
    }

    function create(defaults) {
        defaults = defaults || {};
        var inst = function (config) { return request(merge(defaults, config)); };
        inst.request = inst;
        inst.get = function (url, cfg) { return request(merge(defaults, { method: 'get', url: url }, cfg)); };
        // Rhino 桥仅支持 get/post/header，put/delete 做语义降级
        inst.post = function (url, data, cfg) { return request(merge(defaults, { method: 'post', url: url, data: data }, cfg)); };
        inst.put = function (url, data, cfg) { return request(merge(defaults, { method: 'post', url: url, data: data }, cfg)); };
        inst['delete'] = function (url, cfg) { return request(merge(defaults, { method: 'get', url: url }, cfg)); };
        inst.head = function (url, cfg) { return request(merge(defaults, { method: 'header', url: url }, cfg)); };
        inst.create = function (cfg) { return create(merge(defaults, cfg)); };
        inst.defaults = { method: 'get', timeout: 10000 };
        // 对齐真实 axios CJS 形态：TS 编译产物（import axios from 'axios' → axios_1.default.get）
        // 与部分插件 (0, axios_1.default)({...}) 调用风格依赖 .default 属性
        inst.default = inst;
        inst.axios = inst;
        return inst;
    }

    g.__mf_lib_axios = create({});
})();