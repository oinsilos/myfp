import random
import time
from core import session
from core.login import init, get_userid
from core.courses import getcourselist
from core.tasks import gettaskid
from core.homework import save_hw
from core.video import savelearn_video, learnprogress
from core.ppt import savelearn_ppt
from core.disussion import savelearn_discuss

if __name__ == "__main__":
    if init(session) is False:
        print("cookie初始化失败")
        exit()
    csrfkey = session.cookies.get("NTESSTUDYSI")
    userid = get_userid()
    if userid is None:
        exit()
    coureslist = getcourselist(csrfkey, userid)
    if coureslist:
        print("课程列表获取成功")
        target = next((item for item in coureslist if item["name"] == "信息隐藏技术"), None)
        courseId = target['courseId']
        name = target['name']
        termId = target['termId']
        lessonsCount = target['lessonsCount']
        learnedCount = target['learnedCount']
        shortName = target['shortName']
        is_finished = target['is_finished']
        referer = f"https://www.icourse163.org/learn/{shortName}-{courseId}?tid={termId}"
        tasks = gettaskid(csrfkey, shortName, courseId, termId)
        name = 'modelscope'
        module = True
        speed = 600
        print(f'初始进度:{learnprogress(csrfkey, referer, termId)}')
        for task in tasks:
            if module:
                if task["type"] == 1:
                    print(f"刷视频{task['contentId']}")
                    savelearn_video(csrfkey, task, speed, userid, referer, task['starttime'])
                if task["type"] == 3 and task['completePercent'] < 1.0:
                    print(f"阅读ppt{task['contentId']}")
                    savelearn_ppt(csrfkey, task, speed, referer)
                if task["type"] == 6:
                    savelearn_discuss(csrfkey, task, referer, name)
                if task["type"] == 11 and task['deadline'] > time.time():
                    save_hw(csrfkey, task, referer, name)
                if task["type"] == 12:
                    pass
            else:
                savelearn_video(csrfkey, task, speed, userid, referer)
            time.sleep(random.uniform(1, 5))
        print(f'最终进度:{learnprogress(csrfkey, referer, termId)}')