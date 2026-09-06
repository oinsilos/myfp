// DrPY 宿主全局解析函数 pdfh/pdfa/pd（TVBox/OK影视 标准全局，融合时缺失）。
// 依赖 __require('cheerio')（lib/cheerio.min.js，UMD 经 Require 的 module=__module 包装加载）。
// ES5 书写（Rhino 解释模式）。
(function () {
    var g = (typeof globalThis !== 'undefined' && globalThis) ? globalThis : this;
    if (typeof g.pdfh === 'function') return;
    var cheerioMod = null;
    try {
        cheerioMod = __require('cheerio');
    } catch (e) { }
    if (!cheerioMod && typeof g.cheerio === 'function') cheerioMod = g.cheerio;

    function loadHtml(html) {
        if (html == null) html = '';
        if (typeof html !== 'string') html = String(html);
        if (cheerioMod) return cheerioMod.load(html);
        return null;
    }

    function getAttr($, el, attr) {
        if (attr === 'text') return $(el).text();
        var a = attr;
        if (a.charAt(0) === '@') a = a.substring(1);
        if (a === 'html' || a === 'innerHTML') return $(el).html();
        var v = $(el).attr(a);
        return v == null ? '' : v;
    }

    function normParse(parse) {
        parse = parse == null ? '' : String(parse);
        if (parse.indexOf('@css:') === 0) parse = parse.substring(5);
        return parse;
    }

    function splitSeg(parse) {
        // 多级规则取第一级（含 && 链接）；; 分隔多级
        var seg = parse.split(';')[0];
        var parts = seg.split('&&');
        var css = parts[0];
        var attr = parts.length > 1 ? parts.slice(1).join('&&') : 'text';
        return { css: css, attr: attr };
    }

    function parseAll(html, parse) {
        parse = normParse(parse);
        if (parse === '') return [];
        if (parse.indexOf('json:') === 0 || parse.indexOf('js:') === 0) return [];
        var seg = splitSeg(parse);
        if (seg.css === '' || seg.css === 'body' || seg.css === 'html') {
            return [seg.attr === 'text' ? (html == null ? '' : String(html)) : ''];
        }
        try {
            var $ = loadHtml(html);
            if (!$) return [];
            var out = [];
            $(seg.css).each(function (i, el) {
                out.push(getAttr($, el, seg.attr));
            });
            return out;
        } catch (e) {
            return [];
        }
    }

    g.pdfh = function (html, parse) {
        var arr = parseAll(html, parse);
        return arr.length > 0 ? arr[0] : '';
    };
    g.pdfa = function (html, parse) {
        return parseAll(html, parse);
    };
    g.pd = function (html, rule, parse) {
        var out = [];
        if (!rule) return out;
        try {
            var $ = loadHtml(html);
            if (!$) return out;
            $(rule).each(function (i, el) {
                var s = $.html(el);
                out.push(g.pdfh(s, parse));
            });
        } catch (e) { }
        return out;
    };
})();
