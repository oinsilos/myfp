"""cookie 文件读写 —— 按用户隔离(cookie_{username}.json)。"""

import json

from requests.utils import cookiejar_from_dict, dict_from_cookiejar

# 允许落盘的认证域(后缀匹配):icourse163 本体 + 网易 163 共享 SSO 域
AUTH_COOKIE_DOMAINS = ("icourse163.org", "163.com")


def _is_auth_domain(domain):
    """判断 cookie 域是否属于认证域(按域名后缀匹配,避免误伤/误放)。"""
    d = (domain or "").lstrip(".").lower()
    if not d:
        return False
    return any(d == root or d.endswith("." + root) for root in AUTH_COOKIE_DOMAINS)


def load_cookie(sess, file_path):
    """从指定 JSON 文件加载 cookie 到 session,成功返回 True。"""
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            cookie_dict = json.load(f)
        sess.cookies = cookiejar_from_dict(cookie_dict)
        return True
    except Exception as e:
        print(f"加载本地cookie出错:{e}")
        return False


def save_cookie(sess, file_path, only_auth_domain=False):
    """将 session 中的 cookie 持久化到指定 JSON 文件。

    only_auth_domain=True 时仅保存认证域(AUTH_COOKIE_DOMAINS)下的 cookie,
    剔除登录过程中第三方域(图片 CDN、统计等)写入的 cookie,保证凭证文件干净。
    注意:域为空的 cookie 视为非认证域,不会被保存。
    """
    if only_auth_domain:
        cookie_dict = {
            c.name: c.value
            for c in sess.cookies
            if _is_auth_domain(c.domain)
        }
    else:
        cookie_dict = dict_from_cookiejar(sess.cookies)
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(cookie_dict, f, ensure_ascii=False, indent=2)
    print(f"cookie已保存:{file_path}({len(cookie_dict)} 项)")