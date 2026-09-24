import math
import random
import re
import threading
import time

import requests

from core import session
from core.auth import auth_signature


def get_netinfo():
    url = "https://nstool.netease.com/info.js"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    try:
        response= requests.get(url, headers=headers, timeout=10)
        if response.status_code == 200:
            print("网络信息获取成功")
    except Exception as e:
        print(f"网络信息获取失败:{e}")
    js_text = response.text
    def get_var(name: str):
        pattern = rf"var\s+{name}\s*=\s*['\"](.*?)['\"];"
        m = re.search(pattern, js_text)
        if m:
            return m.group(1)
        return None
    return {
        "ip": get_var("ip"),
        "dns": get_var("dns"),
        "ipProvince": get_var("ip_province"),
        "ipCity": get_var("ip_city"),
        "ipIsp": get_var("ip_isp"),
        "dnsProvince": get_var("dns_province"),
        "dnsCity": get_var("dns_city"),
        "dnsIsp": get_var("dns_isp"),
        "dnsRes": get_var("res"),
        "dnsMsg": get_var("msg"),
    }


def heartbeat(contentId, userId, stop,data1):
    url = "https://vod.study.163.com/submit/heartBeat"
    headers = {"referer": "https://vod.study.163.com/"}
    while not stop.is_set():
        data = {
            "currentKbps": random.randint(15, 20),
            "deviceName": "Chrome/153",
            "errorCode": None,
            "errorMsg": None,
            "preLoadTime": 0,
            "rate": "1",
            "source": "web",
            "time": int(time.time() * 1000),
            "userId": userId,
            "videoId": int(contentId)
        }
        data.update(data1)
        try:
            response = session.post(url, json=data, headers=headers)
            if response.status_code == 200:
                print("心跳成功")
        except Exception as e:
            print(f"心跳失败:{e}")
        time.sleep(random.uniform(15, 30))


def savelearn_video(csrfkey, task, speed, userId, referer, current_time=0):
    url = f"https://www.icourse163.org/web/j/courseRpcBean.saveMocContentLearn.rpc?csrfKey={csrfkey}"
    stop = threading.Event()
    data1=get_netinfo()
    heartbeat_thread = threading.Thread(target=heartbeat, args=(task['contentId'], userId, stop,data1), daemon=True)
    heartbeat_thread.start()
    total_duration = task['duration']
    try:
        while current_time < total_duration:
            remaining = total_duration - current_time
            time1 = min(speed, remaining)
            current_time += time1
            data = {
                "dto": {
                    "unitId": int(task['unitId']),
                    "finished": current_time >= total_duration,
                    "index": math.ceil(current_time / speed),
                    "duration": time1 * 1000,
                    "courseId": int(task['courseId']),
                    "lessonId": int(task['lessonId']),
                    "videoId": int(task['contentId']),
                    "termId": int(task['termId']),
                    "userId": userId,
                    "contentType": 1,
                    "action": "LEARN_TIME_COUNT",
                    "videoTime": current_time,
                    "learnedVideoTimeCount": time1
                }
            }
            headers, body_str = auth_signature(data)
            headers['Referer'] = referer
            response = session.post(url, data=body_str, headers=headers).json()
            if response.get("result") == True:
                print(f'进度上传成功:{current_time}/{task["duration"]}')
            else:
                print(f"进度上传失败{response}")
            if current_time < total_duration:
                time.sleep(random.uniform(1, 5))
    finally:
        stop.set()
        print(f"视频{task['contentId']}已刷完")
        print(f"当前进度:{learnprogress(csrfkey, referer, task['termId'])}")


def learnprogress(csrfkey, referer, termId):
    url = f"https://www.icourse163.org/mm-course/web/j/mocMemberLearnBean.getTermLearn.rpc?csrfKey={csrfkey}"
    data = {"termId": termId}
    headers = {"Referer": referer}
    response = session.post(url, data=data, headers=headers).json()
    learninfo = []
    if response["code"] == 0:
        learntime = response["result"]["learnedTimeCount"]
        learnedCount = response["result"]["learnedCount"] or 0
        lessonCount = response["result"]["lessonCount"]
        learnedVideo = response["result"]["learnedVideoCount"]
        learninfo.append({
            "learntime": f'{learntime // 3600}时{math.ceil((learntime % 3600) / 60)}分({learntime}秒)',
            "completion": f'已学{learnedCount}/{lessonCount}',
            "learnedVideo": learnedVideo
        })
    else:
        print("获取学习进度失败")
    return learninfo