import os
import time
from io import BytesIO
from PIL import Image
from core import session
from core.cookie import COOKIE_FILE, load_cookie, save_cookie


def init(session):
    """初始化会话:优先加载本地 cookie,无效则扫码登录。"""
    if not os.path.exists(COOKIE_FILE):
        return login(session)
    if load_cookie(session):
        if check_session(session) is False:
            return login(session)
        return True
    return False


def login(session):
    session.headers.update({
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
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
    })
    pollkey = get_qrcode()
    if pollkey and poll_login(pollkey, session):
        save_cookie(session)
        return True
    return False


def check_session(session):
    csrfkey = session.cookies.get("NTESSTUDYSI")
    test_url = f"https://www.icourse163.org/web/j/memberBean.getMocMemberPersonalDtoById.rpc?csrfKey={csrfkey}"
    try:
        response = session.post(test_url, data={}).json()
        if response.get('code') == 0:
            print("cookie有效")
            return True
        else:
            print("cookie已过期")
            return False
    except Exception as e:
        print(f'验证请求异常:{e}')
        return False


def get_qrcode():
    url = "https://www.icourse163.org/logonByQRCode/code.do?width=182&height=182"
    response = session.get(url).json()
    if response:
        pollkey = response['result']['pollKey']
        code_url = response['result']['codeUrl']
        img = Image.open(BytesIO(session.get(code_url).content))
        img.show()
        return pollkey
    else:
        print('扫码登录拉取失败')
        return None


def poll_login(pollkey, session, max_retries=20):
    url = f"https://www.icourse163.org/logonByQRCode/poll.do?pollKey={pollkey}"
    for i in range(max_retries):
        response = session.get(url).json()
        status = response['result']['codeStatus']
        if status == 0:
            print("等待扫码...")
        if status == 1:
            print("已扫码,请在手机上确认...")
        if status == 2:
            print("登陆成功!")
            print(response)
            if get_cookies(response['result']['token'], session):
                return True
        if status == 3:
            print("二维码过期,请重新获取")
            return False
        time.sleep(2)


def get_cookies(token, session):
    returnUrl = "YUhSMGNITTZMeTkzZDNjdWFXTnZkWEp6WlRFMk15NXZjbWN2YldWdFltVnlMMnh2WjJsdUxtaDBiVDl5WlhSMWNtNVZjbXc5WVVoU01HTklUVFpNZVRrelpETmpkV0ZYVG5aa1dFcDZXbFJGTWsxNU5YWmpiV04yWVZjMWExcFlaM1ZoU0ZKMEl5OTNaV0pNYjJkcGJrbHVaR1Y0"
    url = f'https://www.icourse163.org/passport/logingate/mocMobChangeCookie.htm?token={token}&returnUrl={returnUrl}'
    headers = {
        "Referer": "https://www.icourse163.org/member/login.htm?"
    }
    response = session.get(url, headers=headers)
    if response.status_code == 200:
        print("获取cookie成功")
        return True
    return False


def get_userid():
    studyinfo = session.cookies.get("STUDY_INFO")
    if studyinfo:
        parts = studyinfo.split("|")
        if len(parts) >= 3:
            userid = parts[2]
            if userid.isdigit():
                print(f"userid获取成功:{userid}")
                return userid
    print(f"userid获取失败:{studyinfo}")
    return None