#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量测试影视源：判断可访问性、内容类型（直播/点播/多仓/其他），并返回关键数据。"""
import json
import ssl
import sys
import urllib.request
import urllib.parse
import urllib.error
import re
import time

TIMEOUT = 12
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

def build_url(raw):
    raw = raw.strip().strip('`')
    if not raw:
        return None
    if not raw.startswith(('http://', 'https://')):
        raw = 'http://' + raw
    # 分离协议、域名(host)与路径，域名部分做 IDN/punycode 编码，路径做百分号编码
    m = re.match(r'^(https?://)([^/?#]+)(.*)$', raw, re.I)
    if not m:
        return raw
    scheme, host, rest = m.group(1), m.group(2), m.group(3)
    try:
        host = host.encode('idna').decode('ascii')
    except Exception:
        pass
    rest = urllib.parse.quote(rest, safe=':/?&=#%.+-+_,@[]!$\'()*')
    return scheme + host + rest

def fetch(url):
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': '*/*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
    })
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    proxy = urllib.request.getproxies()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxy))
    with opener.open(req, timeout=TIMEOUT) as resp:
        data = resp.read()
        final_url = resp.geturl()
        ctype = resp.headers.get('Content-Type', '')
        return final_url, ctype, data

def sniff(ctype, data):
    head = data[:4096]
    txt = head.decode('utf-8', 'ignore')
    stripped = txt.lstrip('\ufeff \t\r\n')
    jsobj = None
    if stripped.startswith('{') or stripped.startswith('['):
        try:
            jsobj = json.loads(data.decode('utf-8', 'ignore'))
        except Exception:
            try:
                jsobj = json.loads(re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', '', data.decode('utf-8', 'ignore')))
            except Exception:
                jsobj = None
    size = len(data)

    if jsobj is not None:
        is_list = isinstance(jsobj, list)
        keys = [k.lower() for k in (jsobj.keys() if isinstance(jsobj, dict) else [])]
        kset = set(keys)
        has_lives = 'lives' in kset or 'live' in kset
        has_vod = 'vod' in kset or 'vods' in kset or 'videos' in kset
        has_sites = 'sites' in kset or 'site' in kset
        has_spider = 'spider' in kset or 'spiders' in kset
        has_urls = 'urls' in kset or 'url' in kset
        kind = 'unknown-json'
        detail = []
        if is_list:
            if jsobj and isinstance(jsobj[0], dict) and ('url' in jsobj[0] or 'name' in jsobj[0]):
                kind = 'json-array/multi-repo'
            else:
                kind = 'json-array'
        else:
            if has_sites and has_lives and has_vod:
                kind = 'full-config(live+vod)'
            elif has_sites and has_urls and not has_vod and not has_lives:
                kind = 'site-relay'
            elif has_spider:
                kind = 'vod-config(spider)'
            elif has_vod:
                kind = 'vod-only'
            elif has_lives:
                kind = 'live-only'
            elif has_sites:
                kind = 'sites-only'
            elif has_urls:
                kind = 'multi-repo'
            elif 'parse' in kset:
                kind = 'parse-only'
        if isinstance(jsobj, dict):
            for k in ('lives', 'vod', 'sites', 'urls', 'spider'):
                v = jsobj.get(k)
                if isinstance(v, list):
                    detail.append(f"{k}={len(v)}")
                elif isinstance(v, dict):
                    detail.append(f"{k}=dict")
                elif v is not None:
                    detail.append(f"{k}={str(v)[:20]}")
        d = ' | '.join(detail)
        return f"json/{kind}", d, size

    if data[:7] in (b'#EXTM3U',) or stripped.startswith('#EXTM3U'):
        channels = len(re.findall(rb'#EXTINF', data))
        return 'm3u/live', f'channels={channels}', size

    lines = data.decode('utf-8', 'ignore').splitlines()
    nonempty = [l for l in lines if l.strip() and not l.strip().startswith('#') and not l.startswith('\ufeff#')]
    if nonempty:
        url_like = sum(1 for l in nonempty if re.match(r'^https?://', l.strip()))
        if url_like == len(nonempty) and url_like > 0:
            return 'text/all-urls', f'lines={len(nonempty)}', size
        if 0 < url_like < len(nonempty):
            return 'text/mixed', f'lines={len(nonempty)} urls={url_like}', size
        if not url_like:
            return f'text/other({nonempty[0][:20]})', f'lines={len(nonempty)}', size
    if stripped:
        return 'text/other', f'first={stripped[:30]!r}', size
    return f'binary/{ctype}', f'size={size}', size

def main():
    group = sys.argv[1] if len(sys.argv) > 1 else 'all'
    sources = json.load(open('/workspace/.tmp_src_test/sources.json', 'r', encoding='utf-8'))
    cats = sources if group == 'all' else {group: sources[group]}
    for cat, urls in cats.items():
        print(f"\n===== {cat} ({len(urls)}) =====")
        for raw in urls:
            url = build_url(raw)
            t0 = time.time()
            try:
                final_url, ctype, data = fetch(url)
                kind, detail, size = sniff(ctype, data)
                ms = (time.time() - t0) * 1000
                print(f"[OK ] {raw}")
                print(f"     type={kind} size={size} time={ms:.0f}ms {detail}")
                if final_url != url:
                    print(f"     -> {final_url[:150]}")
            except urllib.error.HTTPError as e:
                print(f"[ERR] {raw}  HTTP {e.code}")
            except Exception as e:
                print(f"[ERR] {raw}  {type(e).__name__}: {str(e)[:80]}")
            sys.stdout.flush()

if __name__ == '__main__':
    main()