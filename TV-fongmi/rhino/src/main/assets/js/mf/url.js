// MusicFree 插件 URL 兼容层：JS 类包装 Java URI 助手（__mf_url_parse / __mf_url_resolve），ES5 书写。
(function () {
    var g = (typeof globalThis !== 'undefined' && globalThis) ? globalThis : this;

    function parse(u, base) {
        var href = u;
        if (base && g.__mf_url_resolve) {
            try { href = g.__mf_url_resolve(base, u); } catch (e) { }
        }
        var parts = {};
        if (g.__mf_url_parse && href) {
            try { parts = g.__mf_url_parse(href); } catch (e) { }
        }
        var o = {};
        o.href = parts.href ? parts.href : (u === undefined ? '' : String(u));
        o.protocol = parts.schema ? parts.schema + ':' : '';
        o.host = parts.hostname + (parts.port ? ':' + parts.port : '');
        o.hostname = parts.hostname || '';
        o.port = parts.port || '';
        o.pathname = parts.path || '';
        o.search = parts.query ? '?' + parts.query : '';
        o.hash = parts.fragment ? '#' + parts.fragment : '';
        o.origin = (parts.schema && parts.hostname) ? (parts.schema + '://' + o.host) : '';
        o._query = parts.query || '';
        return o;
    }

    function SearchParams(query) {
        this._pairs = {};
        this._keys = [];
        if (typeof query === 'string' && query) {
            var items = query.split('&');
            for (var i = 0; i < items.length; i++) {
                if (!items[i]) continue;
                var kv = items[i].split('=');
                var k = decodeURIComponent((kv[0] || '').replace(/\+/g, ' '));
                var v = decodeURIComponent((kv[1] || '').replace(/\+/g, ' '));
                if (!Object.prototype.hasOwnProperty.call(this._pairs, k)) this._keys.push(k);
                this._pairs[k] = this._pairs[k] || [];
                this._pairs[k].push(v);
            }
        }
    }
    SearchParams.prototype.get = function (k) {
        var arr = this._pairs[k];
        return (arr && arr.length) ? arr[0] : null;
    };
    SearchParams.prototype.getAll = function (k) { return this._pairs[k] || []; };
    SearchParams.prototype.has = function (k) { return !!this._pairs[k]; };
    SearchParams.prototype.append = function (k, v) {
        if (!Object.prototype.hasOwnProperty.call(this._pairs, k)) this._keys.push(k);
        this._pairs[k] = this._pairs[k] || [];
        this._pairs[k].push(String(v));
    };
    SearchParams.prototype.toString = function () {
        var out = [];
        for (var i = 0; i < this._keys.length; i++) {
            var k = this._keys[i];
            var arr = this._pairs[k];
            for (var j = 0; j < arr.length; j++) out.push(encodeURIComponent(k) + '=' + encodeURIComponent(arr[j]));
        }
        return out.join('&');
    };

    function URL(input, base) {
        if (!(this instanceof URL)) return new URL(input, base);
        var p = parse(input === undefined || input === null ? '' : String(input), base === undefined || base === null ? null : String(base));
        this.href = p.href;
        this.protocol = p.protocol;
        this.host = p.host;
        this.hostname = p.hostname;
        this.port = p.port;
        this.pathname = p.pathname;
        this.search = p.search;
        this.hash = p.hash;
        this.origin = p.origin;
        this.searchParams = new SearchParams(p._query);
    }
    URL.prototype.toString = function () {
        var href = this.href || '';
        var i = href.indexOf('?');
        if (i >= 0) href = href.substring(0, i);
        i = href.indexOf('#');
        if (i >= 0) href = href.substring(0, i);
        var q = this.searchParams ? this.searchParams.toString() : '';
        return href + (q ? '?' + q : '') + (this.hash || '');
    };
    URL.prototype.toJSON = function () { return this.href; };
    URL.searchParams = SearchParams;

    g.__mf_lib_URL = URL;
})();