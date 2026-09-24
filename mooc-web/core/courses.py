"""课程列表获取(Web 版:显式传 session,带超时)。"""

from core import DEFAULT_TIMEOUT


def getcourselist(sess, csrfkey, userId):
    url = f"https://www.icourse163.org/web/j/learnerCourseRpcBean.getMyLearnedCoursePanelList.rpc?csrfKey={csrfkey}"
    all_courses = []
    page_size = 8
    page = 1
    data = {
        "type": 30,
        "p": page,
        "psize": page_size,
        "courseType": 1
    }
    referer = f"https://www.icourse163.org/home.htm?userId={userId}"
    headers = {"Referer": referer}
    while True:
        try:
            response = sess.post(url, data=data, headers=headers, timeout=DEFAULT_TIMEOUT)
            page_data = response.json()
            if page_data.get("code") != 0:
                print(f"第{page}页课程信息抓取失败:{page_data}")
                break
            result_list = page_data.get("result", {}).get("result") or []
            for item in result_list:
                courseId = item.get("id")
                name = item.get("name")
                imgUrl = item.get("imgUrl")
                termId = item.get("termPanel", {}).get("id")
                lessonsCount = item.get("termPanel", {}).get("lessonsCount") or 0
                learnedCount = item.get("learnedCount") or 0
                shortName = item.get("schoolPanel", {}).get("shortName")
                is_finished = learnedCount >= lessonsCount
                all_courses.append({
                    "courseId": courseId,            # 课程id
                    "name": name,                    # 课程名
                    "imgUrl": imgUrl,                # 图片url
                    "termId": termId,                # 学期id
                    "lessonsCount": lessonsCount,    # 总学时
                    "learnedCount": learnedCount,    # 已完成学时
                    "shortName": shortName,          # 学校简称
                    "is_finished": is_finished       # 是否完成
                })
            pagination = page_data.get("result", {}).get("pagination", {})
            current_page = pagination.get("pageIndex")
            total_pages = pagination.get("totlePageCount") or 0
            print(f"第{current_page}页解析完成,累计{len(all_courses)}门课程")
            if current_page >= total_pages:
                print("已经最后一页")
                break
            data["p"] = page = page + 1
        except Exception as e:
            print(f"请求异常:{e}")
            break
    return all_courses