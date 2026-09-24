"""扫码登录流程管理:后台线程轮询扫码结果,前端轮询状态接口。

每个用户名同时最多一个登录流程;重复发起会取消旧流程。
登录成功后自动持久化 cookie 并绑定该用户。

注意:登录流程全程使用**纯净会话**(无任何 cookie),
不会加载该用户已存凭证,避免旧 cookie 参与握手造成串号或污染新凭证。
"""

import base64
import threading
import time
import uuid

from core import new_clean_session
from core.login import get_qrcode, poll_login

# 单个登录流程最长存活时间(秒)
FLOW_MAX_AGE = 20 * 60


class LoginFlow:
    """一次扫码登录流程的状态机。"""

    def __init__(self, username):
        self.username = username
        self.flow_id = uuid.uuid4().hex[:12]
        # 纯净会话:显式清空 cookiejar,保证扫码/轮询/换取 cookie 全程无历史 cookie
        self.session = new_clean_session()
        self.status = "preparing"   # preparing/waiting/scanned/success/expired/error/canceled
        self.message = "正在获取二维码..."
        self.image = None           # 二维码 PNG 字节
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self.created_at = time.time()

    def set_status(self, status, message):
        with self._lock:
            self.status = status
            self.message = message

    def set_image(self, image):
        with self._lock:
            self.image = image

    def snapshot(self):
        with self._lock:
            img = self.image
            status = self.status
            message = self.message
        return {
            "flow_id": self.flow_id,
            "username": self.username,
            "status": status,
            "message": message,
            "image_b64": None if img is None else
            "data:image/png;base64," + base64.b64encode(img).decode(),
        }


class LoginFlowManager:
    def __init__(self, store):
        self.store = store
        self._flows = {}
        self._lock = threading.Lock()

    def start(self, username) -> LoginFlow:
        """发起(或重置)用户名对应的扫码登录流程。"""
        self.cancel(username)
        flow = LoginFlow(username)
        with self._lock:
            self._flows[username] = flow
        t = threading.Thread(target=self._run, args=(flow,), daemon=True)
        t.start()
        return flow

    def get(self, username) -> LoginFlow | None:
        with self._lock:
            flow = self._flows.get(username)
        if flow is not None and time.time() - flow.created_at > FLOW_MAX_AGE:
            self.cancel(username)
            return None
        return flow

    def iter_flows(self):
        """遍历当前所有活跃登录流程。"""
        with self._lock:
            flows = list(self._flows.values())
        return flows

    def cancel(self, username):
        with self._lock:
            flow = self._flows.pop(username, None)
        if flow is not None:
            flow._stop.set()

    def _run(self, flow: LoginFlow):
        try:
            pollkey, img = get_qrcode(flow.session)
            if not pollkey or not img:
                flow.set_image(img)
                flow.set_status("error", "二维码获取失败,请稍后重试")
                return
            flow.set_image(img)
            flow.set_status("waiting", "请使用中国大学MOOC App 扫码")

            def on_status(status, message):
                flow.set_status(status, message)

            result = poll_login(
                pollkey, flow.session,
                on_status=on_status, stop_event=flow._stop, max_retries=150)
            if result is True:
                # 登录成功:持久化 cookie 并绑定用户
                self.store.save_cookie_session(flow.username, flow.session)
                self.store.mark_valid(flow.username, True)
                flow.set_status("success", "登录成功!")
            elif result is None:
                flow.set_status("canceled", "已取消")
            else:
                # False:二维码过期或登录失败
                if flow.status not in ("success", "expired"):
                    flow.set_status("expired", "二维码已过期,请重新获取")
        except Exception as e:
            flow.set_status("error", f"登录流程异常:{e}")