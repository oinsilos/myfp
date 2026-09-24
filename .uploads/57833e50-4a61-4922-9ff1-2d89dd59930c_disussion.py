import html
import json as _json
import random
import re
import string
import time
import urllib.parse
from bs4 import BeautifulSoup

from core import session
from core.ai_client import get_reply


def get_discuss(csrfkey, task, referer):
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
    response = session.post(url, data=data, headers=headers)
    if response.status_code == 200:
        print(f"主题帖信息{task['contentId']}已找到")
    else:
        print(f"主题帖信息获取失败:{response.text}")
    return clean_content(response.text)


def clean_content(content_str):
    title_match = re.search(r'\.title\s*=\s*"([^"]+)";', content_str)
    content_match = re.search(r'\.content\s*=\s*"([^"]+)";', content_str)
    title, content = "", ""
    if title_match:
        title = _json.loads(f'"{title_match.group(1)}"')
    if content_match:
        html_content = _json.loads(f'"{content_match.group(1)}"')
        soup = BeautifulSoup(html_content, "html.parser")
        for br in soup.find_all("br"):
            br.replace_with("\n")
        content = soup.get_text(strip=True)
    return title, content


def savelearn_discuss(csrfkey, task, referer, name):
    url = "https://www.icourse163.org/dwr/call/plaincall/MocForumBean.addReply.dwr"
    rand_str = ''.join(random.choices(string.ascii_letters + string.digits, k=12))
    title, content = get_discuss(csrfkey, task, referer)
    content_text = get_reply(title, content, name)
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
    response = session.post(url, data=data, headers=headers)
    if "dwr.engine._remoteHandleCallback" in response.text and "postId" in response.text:
        print(f"回复成功:{content_text}")
        return True
    else:
        print(f"回复失败:{response.text}")
        return False