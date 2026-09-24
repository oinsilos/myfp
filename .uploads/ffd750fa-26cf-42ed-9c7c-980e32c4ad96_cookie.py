import json
from requests.utils import cookiejar_from_dict, dict_from_cookiejar

COOKIE_FILE = '../data/cookies.json'


def load_cookie(session):
    """从本地 cookies.json 加载 cookie 到 session,成功返回 True。"""
    try:
        with open(COOKIE_FILE, 'r', encoding='utf-8') as f:
            cookie_dict = json.load(f)
        session.cookies = cookiejar_from_dict(cookie_dict)
        return True
    except Exception as e:
        print(f"加载本地cookie出错:{e}")
        return False


def save_cookie(session):
    """将 session 中的 cookie 持久化到本地 cookies.json。"""
    cookie_dict = dict_from_cookiejar(session.cookies)
    with open(COOKIE_FILE, 'w', encoding='utf-8') as f:
        json.dump(cookie_dict, f, ensure_ascii=False, indent=2)
    print('cookie储存成功')