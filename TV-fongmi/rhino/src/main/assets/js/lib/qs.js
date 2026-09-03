// qs 兼容层（ES5 子集实现）：parse / stringify，覆盖插件常用子集（体积优先）。
// - stringify：对象/数组嵌入、arrayFormat: indices(默认 a[0]=x) / brackets(a[]=x) / repeat(a=x)
// - parse：支持 a=1、a[]=1、a[0]=1、a[b]=c、a[b][0]=d 嵌套；'+' 依空格解码
// 未实现：depth 限制以外的高级选项（后续按需补齐）。
(function (global) {
    'use strict';

    var hasOwn = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };

    function defaultEncoder(v) {
        // 与 qs 默认一致：encodeURIComponent + 若干保留字符还原 + 空格转 +
        return encodeURIComponent(String(v))
            .replace(/%40/g, '@')
            .replace(/%3A/gi, ':')
            .replace(/%24/g, '$')
            .replace(/%2C/gi, ',')
            .replace(/%3B/gi, ';')
            .replace(/%20/g, '+');
    }

    function defaultDecoder(v) {
        var s = String(v).replace(/\+/g, ' ');
        try { return decodeURIComponent(s); } catch (e) { return s; }
    }

    // 把 key（可为 a / a[] / a[0] / a[b] / a[b][c]）写入 target
    function put(target, key, value, isObjectLike) {
        var parts = [];
        var cur = key;
        for (;;) {
            var idx = cur.indexOf('[');
            if (idx < 0) { parts.push(cur); break; }
            parts.push(cur.substring(0, idx));
            var rest = cur.substring(idx + 1);
            var close = rest.indexOf(']');
            if (close < 0) { parts.push(rest); break; }
            parts.push(rest.substring(0, close));
            if (close === rest.length - 1) break;
            cur = rest.substring(close + 1);
        }
        var node = target;
        for (var i = 0; i < parts.length - 1; i++) {
            var p = parts[i];
            var next = parts[i + 1];
            var nextArr = next === '' || /^\d+$/.test(next);
            if (!hasOwn(node, p) || (typeof node[p] !== 'object')) {
                node[p] = nextArr ? [] : {};
            }
            node = node[p];
        }
        var last = parts[parts.length - 1];
        if (last === '') {
            if (!(node instanceof Array)) node = [];
            node.push(value);
        } else if (/^\d+$/.test(last)) {
            node[parseInt(last, 10)] = value;
        } else {
            node[last] = value;
        }
    }

    function parse(str, options) {
        options = options || {};
        var decoder = options.decoder === false ? function (s) { return String(s); } : (options.decoder || defaultDecoder);
        var out = {};
        if (str === null || str === undefined) return out;
        var segs = String(str).split(/[&;]/);
        for (var i = 0; i < segs.length; i++) {
            var seg = segs[i];
            if (!seg) continue;
            var eq = seg.indexOf('=');
            var rawK = eq >= 0 ? seg.substring(0, eq) : seg;
            var rawV = eq >= 0 ? seg.substring(eq + 1) : '';
            put(out, decoder(rawK), decoder(rawV));
        }
        return out;
    }

    function encodeKey(key) {
        // 仅编码 [] 之间的部分，保留结构括号
        var out = [];
        var buf = '';
        var i;
        for (i = 0; i < key.length; i++) {
            var c = key.charAt(i);
            if (c === '[') {
                if (buf) { out.push(defaultEncoder(buf)); buf = ''; }
                out.push('[');
            } else if (c === ']') {
                out.push(defaultEncoder(buf));
                buf = '';
                out.push(']');
            } else {
                buf += c;
            }
        }
        if (buf) out.push(defaultEncoder(buf));
        return out.join('');
    }

    function stringify(arr, options) {
        options = options || {};
        var parts = [];
        var arrayFormat = options.arrayFormat || 'indices';
        var encoder = options.encoder === false ? function (s) { return String(s); } : (options.encoder || defaultEncoder);
        var encode = options.encode !== false;

        function walk(obj, prefix) {
            if (obj === null || obj === undefined) return;
            if (Array.isArray(obj)) {
                for (var i = 0; i < obj.length; i++) {
                    var v = obj[i];
                    if (v === null || v === undefined) continue;
                    if (typeof v === 'object') {
                        var pk = (arrayFormat === 'indices' && prefix) ? prefix + '[' + i + ']' : prefix + '[]';
                        walk(v, pk);
                    } else {
                        var k;
                        if (arrayFormat === 'indices') k = prefix + '[' + i + ']';
                        else if (arrayFormat === 'brackets') k = prefix + '[]';
                        else if (arrayFormat === 'comma') k = prefix; // 降级 repeat
                        else k = prefix;
                        if (encode) k = encodeKey(k);
                        parts.push(k + '=' + encoder(v));
                    }
                }
            } else if (typeof obj === 'object') {
                var keys = [];
                for (var kk in obj) if (hasOwn(obj, kk)) keys.push(kk);
                for (var j = 0; j < keys.length; j++) {
                    var key = keys[j];
                    var val = obj[key];
                    if (val === null || val === undefined) continue;
                    if (typeof val === 'object') {
                        walk(val, prefix ? prefix + '[' + key + ']' : key);
                    } else {
                        var outKey = prefix ? prefix + '[' + key + ']' : key;
                        if (encode) outKey = encodeKey(outKey);
                        parts.push(outKey + '=' + encoder(val));
                    }
                }
            } else {
                var ok = prefix === undefined || prefix === null || prefix === '' ? '' : prefix;
                if (encode) ok = encodeKey(ok);
                parts.push(ok + '=' + encoder(obj));
            }
        }

        walk(arr, '');
        return parts.join(options.delimiter === undefined ? '&' : options.delimiter);
    }

    var qs = { parse: parse, stringify: stringify };
    global.__mf_lib_qs = qs;
    if (typeof module !== 'undefined' && module.exports) { module.exports = qs; }
})(typeof globalThis !== 'undefined' && globalThis ? globalThis : this);