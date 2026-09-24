"""用户存储:注册表(users.json) + 按用户隔离的 cookie 文件(cookie_{username}.json)。

所有 cookie 持久化与读取都在这里完成,保证多用户互不干扰。
每个请求都构建独立 requests.Session,天然线程安全。
"""

import json
import re
import threading
import time
from pathlib import Path

from core import new_session
from core.cookie import load_cookie, save_cookie
from core.login import check_session

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
COOKIE_DIR = DATA_DIR / "cookies"
USERS_FILE = DATA_DIR / "users.json"

# cookie 有效性缓存时长(秒):避免每次页面操作都打真实校验请求
VALIDITY_TTL = 300

USERNAME_RE = re.compile(r"^[\w\u4e00-\u9fa5\-]{1,32}$")


def validate_username(name: str) -> str:
    name = (name or "").strip()
    if not USERNAME_RE.fullmatch(name):
        raise ValueError("用户名仅支持中英文、数字、下划线、横线,长度 1-32")
    return name


class Store:
    def __init__(self):
        DATA_DIR.mkdir(exist_ok=True)
        COOKIE_DIR.mkdir(exist_ok=True)
        self._lock = threading.RLock()
        self._users = {}
        self._validity = {}  # username -> {"valid": bool, "ts": float}
        self._load()

    # ---------- 用户注册表 ----------
    def _load(self):
        users = {}
        if USERS_FILE.exists():
            try:
                data = json.loads(USERS_FILE.read_text(encoding="utf-8"))
                users = data.get("users", {})
            except Exception as e:
                print(f"读取用户注册表出错:{e}")
        # 自动登记磁盘上已有 cookie 文件的用户名
        for p in COOKIE_DIR.glob("cookie_*.json"):
            name = p.stem[len("cookie_"):]
            users.setdefault(name, {"created_at": int(p.stat().st_mtime), "note": ""})
        self._users = users

    def _save(self):
        with self._lock:
            USERS_FILE.write_text(
                json.dumps({"users": self._users}, ensure_ascii=False, indent=2),
                encoding="utf-8")

    def list_users(self):
        with self._lock:
            items = sorted(self._users.items())
        return [
            {
                "username": name,
                "created_at": info.get("created_at"),
                "note": info.get("note", ""),
            }
            for name, info in items
        ]

    def add_user(self, username: str) -> bool:
        with self._lock:
            if username in self._users:
                return False
            self._users[username] = {"created_at": int(time.time()), "note": ""}
        self._save()
        return True

    def remove_user(self, username: str) -> bool:
        with self._lock:
            existed = username in self._users
            self._users.pop(username, None)
            self._validity.pop(username, None)
        if existed:
            self._save()
        cookie_path = self.cookie_path(username)
        if cookie_path.exists():
            cookie_path.unlink()
        return existed

    # ---------- cookie 文件 ----------
    def cookie_path(self, username: str) -> Path:
        return COOKIE_DIR / f"cookie_{username}.json"

    def has_cookie(self, username: str) -> bool:
        return self.cookie_path(username).exists()

    def build_session(self, username: str):
        """为指定用户名构建一个加载了其 cookie 的新会话,无 cookie 返回 None。"""
        path = self.cookie_path(username)
        if not path.exists():
            return None
        sess = new_session()
        if not load_cookie(sess, path):
            return None
        return sess

    def save_cookie_session(self, username: str, sess) -> None:
        """持久化会话 cookie 并登记用户(扫脸登录成功后调用)。"""
        save_cookie(sess, self.cookie_path(username))
        with self._lock:
            self._users.setdefault(username, {"created_at": int(time.time()), "note": ""})
        self._save()

    # ---------- cookie 有效性 ----------
    def cached_validity(self, username: str):
        """返回缓存的有效性(bool)或 None(未缓存/已过期)。"""
        with self._lock:
            v = self._validity.get(username)
            if v and time.time() - v["ts"] < VALIDITY_TTL:
                return v["valid"]
        return None

    def mark_valid(self, username: str, valid: bool):
        with self._lock:
            self._validity[username] = {"valid": valid, "ts": time.time()}

    def check_validity(self, username: str, force: bool = False) -> bool:
        """校验用户 cookie 是否有效(默认走缓存,force 时强制真实校验)。"""
        if not force:
            cached = self.cached_validity(username)
            if cached is not None:
                return cached
        sess = self.build_session(username)
        valid = bool(sess and check_session(sess))
        self.mark_valid(username, valid)
        return valid

    def require_session(self, username: str):
        """构建有效会话;失败返回 None。"""
        sess = self.build_session(username)
        if sess is None:
            return None
        valid = self.cached_validity(username)
        if valid is not None:
            return sess if valid else None
        if check_session(sess):
            self.mark_valid(username, True)
            return sess
        self.mark_valid(username, False)
        return None