from core import session
from core.ai_client import get_answer
from core.auth import auth_signature


# def get_workaid(csrfkey, task, referer):
#     url = f"https://www.icourse163.org/web/j/mocQuizRpcBean.getOpenHomeworkInfo.rpc?csrfKey={csrfkey}"
#     data = {
#         "tid": task['contentId'],
#         "isDraft": False
#     }
#     headers, body_str = auth_signature(data)
#     headers['Referer'] = referer
#     response = session.post(url, data=body_str, headers=headers).json()
#     if response.get("code") == 0:
#         return response.get("result", {}).get("aid")
#     else:
#         print(f"aid获取失败:{response}")


def get_hwdata(csrfkey, task, referer, name):
    url = f"https://www.icourse163.org/web/j/mocQuizRpcBean.getOpenHomeworkPaperDto.rpc?csrfKey={csrfkey}"
    data = {
        "tid": task['contentId'],
        "evaluateId": None,
        "withStdAnswerAndAnalyse": False,
        "phase": 1,
        "aid": None
    }
    headers, body_str = auth_signature(data)
    headers['Referer'] = referer
    response = session.post(url, data=body_str, headers=headers).json()
    if response.get("code") == 0:
        print("作业信息获取成功")
        paper_dto = response["result"]
        for q in paper_dto["subjectiveQList"]:
            del q["description"]
            q["score"] = int(q["score"])
        paper_dto["answers"] = None
        print("获取问题列表")
        question_list = []
        for q_item in paper_dto["subjectiveQList"]:
            question_list.append({
                "qid": q_item["id"],
                "type": q_item["type"],
                "plainTextTitle": q_item["plainTextTitle"]
            })
        answerlist = get_answer(question_list, name)
        if answerlist is None:
            return None
        ans_array = []
        for item in answerlist:
            ans_array.append({
                "qid": item["qid"],
                "type": item["type"],
                "content": {
                    "content": f"<p>{item['answer']}</p>",
                    "attachments": []
                }
            })
        paper_dto["answers"] = ans_array
        workdata = {
            "paperDto": paper_dto,
            "preview": False
        }
        return workdata
    else:
        print(f"作业信息获取失败:{response}")
        return None


def save_hw(csrfkey, task, referer, name):
    url = f"https://www.icourse163.org/web/j/mocQuizRpcBean.submitAnswers.rpc?csrfKey={csrfkey}"
    data = get_hwdata(csrfkey, task, referer, name)
    if data is None:
        print("答案获取失败")
        return False
    headers, body_str = auth_signature(data)
    headers['Referer'] = referer
    response = session.post(url, data=body_str, headers=headers).json()
    if response.get("result") == 200:
        print("作业提交成功")
        return True
    else:
        print(f"作业提交失败:{response}")
        return False