"""视频刷课时(Web 版:显式传 session;支持停止事件与进度回调)。"""

import math
import random
import re
import threading
import time

import requests

from core import DEFAULT_TIMEOUT
from core.auth import auth_signature

VOD_HEARTBEAT = "https://vod.study.163.com/submit/heartBeat"


def get_netinfo():
    url = "https://nstool.netease.com/info.js"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    try:
        response = requests.get(url, headers=headers, timeout=10)
        if response.status_code != 200:
            return {}
        js_text = response.text
    except Exception as e:
        print(f"网络信息获取失败:{e}")
        return {}

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


def heartbeat(sess, contentId, userId, stop, data1):
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
            response = sess.post(url=VOD_HEARTBEAT, json=data, headers=headers, timeout=DEFAULT_TIMEOUT)
            if response.status_code == 200:
                print("心跳成功")
        except Exception as e:
            print(f"心跳失败:{e}")
        stop.wait(random.uniform(15, 30))


def savelearn_video(sess, csrfkey, task, speed, userId, referer,
                    current_time=0, stop_event=None, progress_cb=None):
    """刷视频学习进度,返回最终 current_time(秒)。

    - stop_event: threading.Event,置位后当前上传间隙安全中断;
    - progress_cb(current, total): 每完成一次进度上传后回调。
    """
    url = f"https://www.icourse163.org/web/j/courseRpcBean.saveMocContentLearn.rpc?csrfKey={csrfkey}"
    hb_stop = threading.Event()
    data1 = get_netinfo()
    heartbeat_thread = threading.Thread(
        target=heartbeat, args=(sess, task['contentId'], userId, hb_stop, data1), daemon=True)
    heartbeat_thread.start()
    total_duration = task['duration']
    try:
        while current_time < total_duration:
            if stop_event is not None and stop_event.is_set():
                print("收到停止信号,中止视频刷课")
                break
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
            try:
                response = sess.post(url, data=body_str, headers=headers, timeout=DEFAULT_TIMEOUT).json()
                if response.get("result") is True:
                    print(f'进度上传成功:{current_time}/{total_duration}')
                else:
                    print(f"进度上传失败{response}")
            except Exception as e:
                print(f"进度上传异常:{e}")
            if progress_cb is not None:
                progress_cb(current_time, total_duration)
            if current_time < total_duration:
                if stop_event is not None:
                    stop_event.wait(random.uniform(1, 5))
                else:
                    time.sleep(random.uniform(1, 5))
    finally:
        hb_stop.set()
        print(f"视频{task['contentId']}已刷完")
    return current_time


def learnprogress(sess, csrfkey, referer, termId):
    """获取课程学习进度(结构化)。"""
    url = f"https://www.icourse163.org/mm-course/web/j/mocMemberLearnBean.getTermLearn.rpc?csrfKey={csrfkey}"
    data = {"termId": termId}
    headers = {"Referer": referer}
    try:
        response = sess.post(url, data=data, headers=headers, timeout=DEFAULT_TIMEOUT).json()
        if response.get("code") == 0:
            result = response["result"]
            learntime = result["learnedTimeCount"]
            learnedCount = result["learnedCount"] or 0
            lessonCount = result["lessonCount"]
            learnedVideo = result["learnedVideoCount"]
            return {
                "learntime_sec": learntime,
                "learntime_text": f"{learntime // 3600}时{math.ceil((learntime % 3600) / 60)}分({learntime}秒)",
                "completion_text": f"已学{learnedCount}/{lessonCount}",
                "learnedCount": learnedCount,
                "lessonCount": lessonCount,
                "learnedVideo": learnedVideo
            }
        print(f"获取学习进度失败:{response}")
    except Exception as e:
        print(f"获取学习进度异常:{e}")
    return None