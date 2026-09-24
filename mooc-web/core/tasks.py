"""课程任务列表获取(Web 版:显式传 session,带超时)。"""

from core import DEFAULT_TIMEOUT


def gettaskid(sess, csrfkey, shortName, courseId, termId):
    url = f"https://www.icourse163.org/web/j/courseBean.getLastLearnedMocTermDto.rpc?csrfKey={csrfkey}"
    data = {
        "termId": termId
    }
    referer = f"https://www.icourse163.org/learn/{shortName}-{courseId}?tid={termId}"
    headers = {"Referer": referer}
    tasks = []
    try:
        response = sess.post(url, data=data, headers=headers, timeout=DEFAULT_TIMEOUT).json()
    except Exception as e:
        print(f"获取任务列表异常:{e}")
        return []
    result = response.get("result") or {}
    chapters = result.get("mocTermDto", {}).get("chapters", []) or []
    for chapter in chapters:
        chapterId = chapter.get("id")
        chapterName = chapter.get("name")
        lessons = chapter.get("lessons") or []
        for lesson in lessons:
            lessonId = lesson.get("id")
            lessonName = lesson.get("name")
            units = lesson.get("units") or []
            for unit in units:
                if unit["contentType"] in [1, 3, 6]:
                    tasks.append({
                        "type": unit["contentType"],   # 类型
                        "chapterName": chapterName,
                        "lessonName": lessonName,
                        "courseId": courseId,          # 课程ID
                        "termId": termId,              # 学期ID
                        "chapterId": chapterId,        # 章节ID
                        "lessonId": lessonId,          # 课时ID
                        "unitId": unit.get("id"),      # 单元ID
                        "contentId": unit.get("contentId"),     # 内容ID
                        "duration": unit.get("durationInSeconds") or 0,  # 总时长
                        "starttime": unit.get("learntime") or 0,
                        "completePercent": unit.get("completePercent") or 0  # 完成度
                    })
        for hw in chapter.get("homeworks") or []:
            hwName = hw.get("name")
            test_info = hw.get("test") or {}
            tasks.append({
                "type": 11,          # 作业
                "hwName": hwName,
                "termId": termId,
                "chapterId": chapterId,
                "contentId": hw.get("contentId"),
                "deadline": test_info.get("deadline"),
                "usedTryCount": test_info.get("usedTryCount"),
                "userScore": test_info.get("userScore"),
                "totalScore": test_info.get("totalScore")
            })
        for quiz in chapter.get("quizs") or []:
            quizName = quiz.get("name")
            test_info = quiz.get("test") or {}
            tasks.append({
                "type": 12,          # 测验
                "quizName": quizName,
                "termId": termId,
                "chapterId": chapterId,
                "contentId": quiz.get("contentId"),
                "deadline": test_info.get("deadline"),
                "usedTryCount": test_info.get("usedTryCount"),
                "userScore": test_info.get("userScore"),
                "totalScore": test_info.get("totalScore")
            })
    return tasks