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
        return new g.Promise(function (resolve, reject) {
            var res;
            try {
                res = g.req ? g.req(url, options) : g.http(url, options);
            } catch (e) {
                reject(makeError(String(e && e.message || e), config, null));
                return;
            }
            if (!res || res.content === undefined) {
                reject(makeError('network error: ' + url, config, null));
                return;
            }
            var code = (res.code === undefined || res.code === '') ? 200 : Number(res.code);
            var response = {
                data: parseData(res.content, requestHeaders),
                status: code,
                statusText: res.statusText,
                headers: res.headers || {},
                config: config,
                request: null
            };
            if (code >= 200 && code < 300) {
                resolve(response);
            } else {
                var err = makeError('Request failed with status code ' + code, config, response);
                reject(err);
            }
        });
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
        return inst;
    }

    g.__mf_lib_axios = create({});
})();