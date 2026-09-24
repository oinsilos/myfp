"""作业提交(Web 版:显式传 session)。"""

from core import DEFAULT_TIMEOUT
from core.ai_client import get_answer
from core.auth import auth_signature


def get_hwdata(sess, csrfkey, task, referer, provider):
    """获取并组装作业提交数据;provider 为 AI 配置字典(未配置则无法作答)。"""
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
    response = sess.post(url, data=body_str, headers=headers, timeout=DEFAULT_TIMEOUT).json()
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
        answerlist = get_answer(question_list, provider)
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
    print(f"作业信息获取失败:{response}")
    return None


def save_hw(sess, csrfkey, task, referer, provider):
    url = f"https://www.icourse163.org/web/j/mocQuizRpcBean.submitAnswers.rpc?csrfKey={csrfkey}"
    data = get_hwdata(sess, csrfkey, task, referer, provider)
    if data is None:
        print("答案获取失败")
        return False
    headers, body_str = auth_signature(data)
    headers['Referer'] = referer
    response = sess.post(url, data=body_str, headers=headers, timeout=DEFAULT_TIMEOUT).json()
    if response.get("result") == 200:
        print("作业提交成功")
        return True
    print(f"作业提交失败:{response}")
    return False