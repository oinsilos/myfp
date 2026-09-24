"""扫码登录与 cookie 校验(Web 版:显式传 session,轮询可中断)。"""

import time

from core import DEFAULT_TIMEOUT

CHECK_URL = "https://www.icourse163.org/web/j/memberBean.getMocMemberPersonalDtoById.rpc"


def check_session(sess):
    """校验 session 中 cookie 是否有效,有效返回 True。"""
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    test_url = f"{CHECK_URL}?csrfKey={csrfkey}"
    try:
        response = sess.post(test_url, data={}, timeout=DEFAULT_TIMEOUT).json()
        if response.get("code") == 0:
            print("cookie有效")
            return True
        print("cookie已过期")
        return False
    except Exception as e:
        print(f"验证请求异常:{e}")
        return False


def get_qrcode(sess):
    """拉取扫码登录二维码,返回 (pollKey, 图片字节);失败返回 (None, None)。"""
    url = "https://www.icourse163.org/logonByQRCode/code.do?width=182&height=182"
    try:
        response = sess.get(url, timeout=DEFAULT_TIMEOUT).json()
    except Exception as e:
        print(f"二维码接口拉取失败:{e}")
        return None, None
    if response and response.get("result"):
        pollkey = response["result"]["pollKey"]
        code_url = response["result"]["codeUrl"]
        img = sess.get(code_url, timeout=DEFAULT_TIMEOUT).content
        return pollkey, img
    print("扫码登录拉取失败")
    return None, None


def poll_login(pollkey, sess, on_status=None, stop_event=None, max_retries=150):
    """轮询扫码结果。

    on_status(status, message):状态变化回调,status 为 waiting/scanned/success/expired。
    返回值:True 登录成功;False 二维码过期或失败;None 被外部取消。
    """
    url = f"https://www.icourse163.org/logonByQRCode/poll.do?pollKey={pollkey}"
    for _ in range(max_retries):
        if stop_event is not None and stop_event.is_set():
            return None
        try:
            response = sess.get(url, timeout=DEFAULT_TIMEOUT).json()
            status = response["result"]["codeStatus"]
        except Exception:
            time.sleep(2)
            continue
        if status == 1 and on_status:
            on_status("scanned", "已扫码,请在手机上确认...")
        if status == 2:
            if on_status:
                on_status("success", "登录成功!")
            if get_cookies(response["result"]["token"], sess):
                return True
            return False
        if status == 3:
            if on_status:
                on_status("expired", "二维码已过期,请重新获取")
            return False
        if stop_event is not None:
            stop_event.wait(2)
        else:
            time.sleep(2)
    return False


def get_cookies(token, sess):
    returnUrl = "YUhSMGNITTZMeTkzZDNjdWFXTnZkWEp6WlRFMk15NXZjbWN2YldWdFltVnlMMnh2WjJsdUxtaDBiVDl5WlhSMWNuNVZjbXc5WVVoU01HTklUVFpNZVRrelpETmpkV0ZYVG5aa1dFcDZXbFJGTWsxNU5YWmpiV04yWVZjMWExcFlaM1ZoU0ZKMEl5OTNaV0pNYjJkcGJrbHVaR1Y0"
    url = f"https://www.icourse163.org/passport/logingate/mocMobChangeCookie.htm?token={token}&returnUrl={returnUrl}"
    headers = {"Referer": "https://www.icourse163.org/member/login.htm?"}
    try:
        response = sess.get(url, headers=headers, timeout=DEFAULT_TIMEOUT)
        if response.status_code == 200:
            print("获取cookie成功")
            return True
    except Exception as e:
        print(f"获取cookie异常:{e}")
    return False


def get_userid(sess):
    """从 STUDY_INFO cookie 中解析用户 id,失败返回 None。"""
    studyinfo = sess.cookies.get("STUDY_INFO")
    if studyinfo:
        parts = studyinfo.split("|")
        if len(parts) >= 3:
            userid = parts[2]
            if userid.isdigit():
                return userid
    return None