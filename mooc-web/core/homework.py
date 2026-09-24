"""作业/测验:拉取试卷、AI 作答(刷课用)、手动作答提交。

作业(type 11)与测验(type 12)在 icourse163 使用同一套
mocQuizRpcBean.getOpenHomeworkPaperDto / submitAnswers 接口。
"""

import html as _html

from core import DEFAULT_TIMEOUT
from core.ai_client import get_answer
from core.auth import auth_signature

PAPER_URL = "https://www.icourse163.org/web/j/mocQuizRpcBean.getOpenHomeworkPaperDto.rpc"
SUBMIT_URL = "https://www.icourse163.org/web/j/mocQuizRpcBean.submitAnswers.rpc"

# 题型:1单选 2多选 4判断 3/6填空 10简答等(以接口返回为准)
OBJECTIVE_TYPES = {1, 2, 4}


def fetch_paper(sess, csrfkey, task, referer):
    """拉取作业/测验试卷,返回 (paper_raw, questions)。

    paper_raw:  原始 paperDto,提交时必须原样回传(仅替换 answers);
    questions:  标准化题目列表:
        {"qid","type","score","title","kind"(objective/subjective),
         "options":[{"id","text"}]}
    """
    url = f"{PAPER_URL}?csrfKey={csrfkey}"
    data = {
        "tid": task['contentId'],
        "evaluateId": None,
        "withStdAnswerAndAnalyse": False,
        "phase": 1,
        "aid": None
    }
    headers, body_str = auth_signature(data)
    headers['Referer'] = referer
    try:
        response = sess.post(url, data=body_str, headers=headers, timeout=DEFAULT_TIMEOUT).json()
    except Exception as e:
        print(f"拉取试卷异常:{e}")
        return None, []
    if response.get("code") != 0:
        print(f"试卷拉取失败:{response}")
        return None, []
    paper = response["result"]
    questions = []
    for q in paper.get("objectiveQList") or []:
        options = []
        for o in (q.get("optionDtos") or q.get("optionsDetail") or []):
            if isinstance(o, dict):
                options.append({
                    "id": str(o.get("id") or o.get("optionId") or ""),
                    "text": _strip_html(o.get("content") or o.get("optionContent") or ""),
                })
        questions.append({
            "qid": str(q.get("id")),
            "type": q.get("type"),
            "score": q.get("score") or 0,
            "title": _strip_html(q.get("plainTextTitle") or q.get("title") or ""),
            "kind": "objective",
            "options": options,
        })
    for q in paper.get("subjectiveQList") or []:
        # 原实现提交时需剔除 description 并将 score 转为 int(接口要求)
        q.pop("description", None)
        try:
            q["score"] = int(q["score"])
        except (TypeError, ValueError):
            q["score"] = 0
        questions.append({
            "qid": str(q.get("id")),
            "type": q.get("type"),
            "score": int(q.get("score") or 0),
            "title": _strip_html(q.get("plainTextTitle") or q.get("title") or ""),
            "kind": "subjective",
            "options": [],
        })
    return paper, questions


def _strip_html(s):
    """去除 HTML 标签得到纯文本(题目标题/选项展示用)。"""
    import re
    s = re.sub(r"<br\s*/?>", "\n", s or "")
    s = re.sub(r"</p>", "\n", s)
    s = re.sub(r"<[^>]+>", "", s)
    return _html.unescape(s).strip()


def submit_manual(sess, csrfkey, task, referer, answers):
    """以用户手动作答提交作业/测验。

    answers: [{"qid": str, "type": int, "content": str}]
    content 语义:客观题(单选/多选/判断)为选项 id,多选用英文逗号分隔;
                 填空题/简答题为纯文本。
    返回 (ok, message)。
    """
    paper, _ = fetch_paper(sess, csrfkey, task, referer)
    if paper is None:
        return False, "试卷拉取失败"
    # 规范化回答
    normalized = []
    for a in answers:
        content = (a.get("content") or "").strip()
        if not content:
            continue
        if a.get("type") in OBJECTIVE_TYPES:
            body = content
        else:
            body = f"<p>{_html.escape(content)}</p>"
        normalized.append({
            "qid": a["qid"],
            "type": a["type"],
            "content": {"content": body, "attachments": []},
        })
    if not normalized:
        return False, "没有可提交的答案"
    paper["answers"] = normalized
    url = f"{SUBMIT_URL}?csrfKey={csrfkey}"
    headers, body_str = auth_signature({"paperDto": paper, "preview": False})
    headers['Referer'] = referer
    try:
        response = sess.post(url, data=body_str, headers=headers, timeout=DEFAULT_TIMEOUT).json()
    except Exception as e:
        print(f"提交异常:{e}")
        return False, f"提交异常:{e}"
    if response.get("result") == 200:
        return True, "提交成功"
    msg = response.get("message") or response.get("code") or "未知错误"
    print(f"提交失败:{str(response)[:300]}")
    return False, f"提交失败:{msg}"


# ---------- 以下为刷课用的 AI 作答(原逻辑保留) ----------

def get_hwdata(sess, csrfkey, task, referer, provider):
    """获取并组装作业提交数据;provider 为 AI 配置字典(未配置则无法作答)。"""
    paper, questions = fetch_paper(sess, csrfkey, task, referer)
    if paper is None:
        return None
    print("作业信息获取成功")
    print("获取问题列表")
    question_list = [
        {"qid": q["qid"], "type": q["type"], "plainTextTitle": q["title"]}
        for q in questions
    ]
    answerlist = get_answer(question_list, provider)
    if answerlist is None:
        return None
    paper["answers"] = [
        {
            "qid": item["qid"],
            "type": item["type"],
            "content": {
                "content": f"<p>{item['answer']}</p>",
                "attachments": []
            }
        }
        for item in answerlist
    ]
    return {"paperDto": paper, "preview": False}


def save_hw(sess, csrfkey, task, referer, provider):
    url = f"{SUBMIT_URL}?csrfKey={csrfkey}"
    data = get_hwdata(sess, csrfkey, task, referer, provider)
    if data is None:
        print("答案获取失败")
        return False
    headers, body_str = auth_signature(data)
    headers['Referer'] = referer
    try:
        response = sess.post(url, data=body_str, headers=headers, timeout=DEFAULT_TIMEOUT).json()
    except Exception as e:
        print(f"提交异常:{e}")
        return False
    if response.get("result") == 200:
        print("作业提交成功")
        return True
    print(f"作业提交失败:{str(response)[:300]}")
    return False