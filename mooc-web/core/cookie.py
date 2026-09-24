"""cookie 文件读写 —— 按用户隔离(cookie_{username}.json)。"""

import json

from requests.utils import cookiejar_from_dict, dict_from_cookiejar


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


def save_cookie(sess, file_path):
    """将 session 中的 cookie 持久化到指定 JSON 文件。"""
    cookie_dict = dict_from_cookiejar(sess.cookies)
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(cookie_dict, f, ensure_ascii=False, indent=2)
    print(f"cookie已保存:{file_path}")