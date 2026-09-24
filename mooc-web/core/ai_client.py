"""AI 客户端:按调用方传入的配置调用 OpenAI 兼容接口。

本模块与整个服务端**不保存任何 API Key**:
provider 配置(name / base_url / api_key / model)由用户在浏览器本地填写,
仅在发起刷课任务或连通性测试时随请求传入,使用后即随任务对象释放。
代码内亦不内置任何默认 Key 或默认 provider。
"""

import time

from openai import OpenAI
from tenacity import retry, stop_after_attempt, wait_exponential_jitter, retry_if_exception_type
import requests
import openai


def is_configured(provider) -> bool:
    """判断 provider 配置是否完整(name/base_url/api_key/model)。"""
    if not isinstance(provider, dict):
        return False
    return all(
        isinstance(provider.get(k), str) and provider.get(k).strip()
        for k in ("name", "base_url", "api_key", "model")
    )


def get_client(provider):
    """按传入配置临时创建客户端。

    不缓存客户端,避免用户 Key 长期驻留服务端内存。
    """
    return OpenAI(
        base_url=provider["base_url"],
        api_key=provider["api_key"],
    )


@retry(
    stop=stop_after_attempt(3),  # 最多重试3次(总共4次调用)
    wait=wait_exponential_jitter(initial=1, max=8),  # 初始1s,上限8s,自带随机抖动
    retry=retry_if_exception_type((
        # OpenAI SDK抛出的网络、超时相关异常
        requests.exceptions.Timeout,
        requests.exceptions.ConnectionError,
        openai.APIConnectionError,
        openai.APITimeoutError,
        openai.InternalServerError,
    )),
    reraise=True  # 重试全部失败后,把异常抛出去,交给外层try捕获
)
def call_chat_api(client, provider, system_prompt, user_prompt):
    response = client.chat.completions.create(
        model=provider["model"],
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        temperature=0.9,
        max_tokens=1000,
        timeout=18  # 单次请求的超时时间,18秒
    )
    return response


def _chat(provider, system_prompt, user_prompt):
    """单次对话,成功返回纯文本内容,失败返回 None。"""
    if not is_configured(provider):
        print("AI 未配置,跳过调用")
        return None
    try:
        client = get_client(provider)
        response = call_chat_api(client, provider, system_prompt, user_prompt)
        content = (response.choices[0].message.content or "").strip()
        content = content.strip('"').strip("'").strip("“”")
        return content or None
    except Exception as e:
        print(f"[AI:{provider.get('name')}] 调用失败:{e}")
        return None


def get_answer(question_list, provider):
    """生成作业答案列表;provider 未配置时返回 None。"""
    if not is_configured(provider):
        print("AI 未配置,无法作答(作业任务会被跳过)")
        return None
    system_prompt = (
        "你是一名认真学习网课的大学生。请针对老师或同学提出的问题回答。要求:"
        "1.选择题直接输出选项,例如'c,d';简答题按照题目要求作答，字数贴合题目要求。"
        "2.禁止输出任何思考过程,只输出答案即可。"
        "3.直接输出回复内容,不要加引号或前缀。"
    )
    print(f"尝试使用{provider['name']}({provider.get('model')})")
    answerlist = []
    for question in question_list:
        user_prompt = (
            f"问题:{question['plainTextTitle']}\n\n"
            f"请写出你的答案:"
        )
        content = _chat(provider, system_prompt, user_prompt)
        if content:
            print(f"{provider['name']}生成成功")
            answerlist.append({
                "qid": question['qid'],
                "type": question['type'],
                "answer": content
            })
    return answerlist or None


def get_reply(title, content, provider):
    """生成讨论回复;provider 未配置时返回 None。"""
    if not is_configured(provider):
        print("AI 未配置,无法生成讨论回复(讨论任务会被跳过)")
        return None
    system_prompt = (
        "你是一名认真学习网课的大学生。请针对老师或同学提出的课程讨论题,"
        "写一段100字以上的、有思考深度的回复。要求:"
        "1. 使用第一人称,口吻自然、友好;"
        "2. 结合课程内容,不要空话套话;"
        "3. 不要暴露你是 AI,不要用『作为AI』这种表达:"
        "4. 直接输出回复内容,不要加引号或前缀。"
    )
    user_prompt = (
        f"讨论标题:{title}\n"
        f"讨论正文:{content}\n\n"
        f"请写出你的回复:"
    )
    print(f"尝试使用{provider['name']}({provider.get('model')})")
    return _chat(provider, system_prompt, user_prompt)


def test_provider(provider):
    """连通性测试:发送一条最小消息验证配置可用。

    返回 (ok: bool, message: str)。
    """
    if not is_configured(provider):
        return False, "配置不完整:名称 / Base URL / API Key / 模型 均必填"
    try:
        client = OpenAI(
            base_url=provider["base_url"],
            api_key=provider["api_key"],
        )
        t0 = time.time()
        response = client.chat.completions.create(
            model=provider["model"],
            messages=[{"role": "user", "content": "请只回复两个字母:pong"}],
            max_tokens=8,
            temperature=0,
            timeout=15,
        )
        latency_ms = int((time.time() - t0) * 1000)
        if response.choices and response.choices[0].message.content:
            text = response.choices[0].message.content.strip() or "(空回复)"
            return True, f"连接成功({latency_ms}ms),模型回复:{text[:50]}"
        return True, f"连接成功({latency_ms}ms),但模型未返回内容"
    except Exception as e:
        return False, f"连接失败:{e}"