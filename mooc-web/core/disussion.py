"""课程讨论回复(Web 版:显式传 session)。"""

import html
import json as _json
import random
import re
import string
import time
import urllib.parse
from bs4 import BeautifulSoup

from core import DEFAULT_TIMEOUT
from core.ai_client import get_reply


def get_discuss(sess, csrfkey, task, referer):
    url = "https://www.icourse163.org/dwr/call/plaincall/CourseBean.getLessonUnitLearnVo.dwr"
    rand_str = ''.join(random.choices(string.ascii_letters + string.digits, k=12))
    data = (
        f"callCount=1\n"
        f"scriptSessionId={rand_str}190\n"
        f"httpSessionId={csrfkey}\n"
        f"c0-scriptName=CourseBean\n"
        f"c0-methodName=getLessonUnitLearnVo\n"
        f"c0-id=0\n"
        f"c0-param0=number:{task['contentId']}\n"
        f"c0-param1=number:6\n"
        f"c0-param2=string:0\n"
        f"c0-param3=number:{task['unitId']}\n"
        f"batchId={int(time.time() * 1000)}\n"
    )
    headers = {
        "Referer": referer,
        "Content-Type": "text/plain"
    }
    try:
        response = sess.post(url, data=data, headers=headers, timeout=DEFAULT_TIMEOUT)
        if response.status_code == 200:
            print(f"主题帖信息{task['contentId']}已找到")
        else:
            print(f"主题帖信息获取失败:{response.text}")
            return "", ""
        return clean_content(response.text)
    except Exception as e:
        print(f"主题帖信息获取异常:{e}")
        return "", ""


def clean_content(content_str):
    """从 DWR JS 响应中提取讨论帖 title/content。

    DWR 输出为 JS 字符串字面量(含 \\" 转义引号与 \\uXXXX 转义),
    须按「转义感知」方式匹配并用 json.loads 还原,再剥离 HTML 标签。
    """

    def extract(key):
        m = re.search(rf'\.{key}\s*=\s*"((?:[^"\\]|\\.)*)"', content_str)
        if not m:
            return ""
        try:
            return _json.loads(f'"{m.group(1)}"')
        except Exception:
            return m.group(1)

    title = extract("title")
    raw_html = extract("content")
    content = ""
    if raw_html:
        soup = BeautifulSoup(raw_html, "html.parser")
        for br in soup.find_all("br"):
            br.replace_with("\n")
        content = soup.get_text(" ").strip()
    return title, content


def submit_reply_manual(sess, csrfkey, task, referer, content_text):
    """以用户手动输入的文本回复课程讨论(不走 AI)。

    task 需含 contentId(帖子id)与 unitId。
    返回 (ok, message)。
    """
    url = "https://www.icourse163.org/dwr/call/plaincall/MocForumBean.addReply.dwr"
    rand_str = ''.join(random.choices(string.ascii_letters + string.digits, k=12))
    content_text = (content_text or "").strip()
    if not content_text:
        return False, "回复内容不能为空"
    html_content = f"<p>{html.escape(content_text)}</p>"
    content = urllib.parse.quote(html_content)
    data = (
        f"callCount=1\n"
        f"scriptSessionId={rand_str}190\n"
        f"httpSessionId={csrfkey}\n"
        f"c0-scriptName=MocForumBean\n"
        f"c0-methodName=addReply\n"
        f"c0-id=0\n"
        f"c0-e1=number:{task['contentId']}\n"
        f"c0-e2=string:{content}\n"
        f"c0-e3=number:{1}\n"
        f"c0-param0=Object_Object:{{postId:reference:c0-e1,content:reference:c0-e2,anonymous:reference:c0-e3}}\n"
        f"c0-param1=Array:[]\n"
        f"batchId={int(time.time() * 1000)}\n"
    )
    headers = {
        "Referer": referer,
        "Content-Type": "text/plain"
    }
    try:
        response = sess.post(url, data=data, headers=headers, timeout=DEFAULT_TIMEOUT)
    except Exception as e:
        return False, f"回复提交异常:{e}"
    if "dwr.engine._remoteHandleCallback" in response.text and "postId" in response.text:
        print(f"手动回复成功")
        return True, "回复成功"
    print(f"回复失败:{response.text[:200]}")
    return False, "回复失败(可能已参与过该讨论)"


def savelearn_discuss(sess, csrfkey, task, referer, provider):
    url = "https://www.icourse163.org/dwr/call/plaincall/MocForumBean.addReply.dwr"
    rand_str = ''.join(random.choices(string.ascii_letters + string.digits, k=12))
    title, content = get_discuss(sess, csrfkey, task, referer)
    if not content:
        print("未获取到讨论内容")
        return False
    content_text = get_reply(title, content, provider)
    if content_text is None:
        print("获取回复内容失败")
        return False
    html_content = f'<p>{content_text}</p>'
    content = urllib.parse.quote(html_content)
    data = (
        f"callCount=1\n"
        f"scriptSessionId={rand_str}190\n"
        f"httpSessionId={csrfkey}\n"
        f"c0-scriptName=MocForumBean\n"
        f"c0-methodName=addReply\n"
        f"c0-id=0\n"
        f"c0-e1=number:{task['contentId']}\n"
        f"c0-e2=string:{content}\n"
        f"c0-e3=number:{1}\n"
        f"c0-param0=Object_Object:{{postId:reference:c0-e1,content:reference:c0-e2,anonymous:reference:c0-e3}}\n"
        f"c0-param1=Array:[]\n"
        f"batchId={int(time.time() * 1000)}\n"
    )
    headers = {
        "Referer": referer,
        "Content-Type": "text/plain"
    }
    response = sess.post(url, data=data, headers=headers, timeout=DEFAULT_TIMEOUT)
    if "dwr.engine._remoteHandleCallback" in response.text and "postId" in response.text:
        print(f"回复成功:{content_text}")
        return True
    print(f"回复失败:{response.text}")
    return False