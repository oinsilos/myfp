"""中国大学MOOC(icourse163)核心功能模块 —— Web 多用户适配版。

与原 CLI 版的区别:
- 所有网络函数显式接收 requests.Session,便于按用户隔离会话与 cookie;
- 增加统一超时、可中断(停止事件)与结构化进度接口,供 Web 前端展示。
"""

import requests

# 原 CLI 版扫码登录使用的浏览器请求头,所有新会话统一使用
BROWSER_HEADERS = {
    "Accept": "*/*",
    "Accept-Encoding": "gzip, deflate, br, zstd",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
    "Dnt": "1",
    "Origin": "https://www.icourse163.org",
    "Priority": "u=1, i",
    "Referer": "https://mooclog.youdao.com/",
    "Sec-Ch-Ua": '"Microsoft Edge";v="153", "Not_A Brand";v="8", "Chromium";v="153"',
    "Sec-Ch-Ua-Mobile": "?0",
    "Sec-Ch-Ua-Platform": "Windows",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "cross-site",
    "Sec-Gpc": "1",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
}

DEFAULT_TIMEOUT = 20


def new_session():
    """创建带浏览器请求头的新会话。"""
    s = requests.Session()
    s.headers.update(BROWSER_HEADERS)
    return s