import random
import time

from core import session
from core.auth import auth_signature


def savelearn_ppt(csrfkey, task, speed, referer):
    url = f"https://www.icourse163.org/web/j/courseRpcBean.saveMocContentLearn.rpc?csrfKey={csrfkey}"
    data = {
        "dto": {
            "unitId": task['unitId'],
            "finished": True,
            "contentType": 3,
            "index": 1,
            "pageNum": 50,
            "courseId": task['courseId'],
            "lessonId": task['lessonId'],
            "termId": task['termId'],
            "lastLearnTime": int(time.time() * 1000),
            "learnedVideoTimeCount": speed
        }
    }
    headers, body_str = auth_signature(data)
    headers['Referer'] = referer
    response = session.post(url, data=body_str, headers=headers).json()
    if response.get("result") == True:
        print('ppt进度上传成功')
    else:
        print(f"ppt进度上传失败{response}")
    time.sleep(random.uniform(1, 3))
    print(f"PPT{task['contentId']}已刷完")