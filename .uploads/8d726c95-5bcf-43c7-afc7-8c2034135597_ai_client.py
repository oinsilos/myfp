import random
import time
from openai import OpenAI
from tenacity import retry, stop_after_attempt, wait_exponential_jitter, retry_if_exception_type
import requests
import openai

AI_PROVIDERS = [
    {
        "name": "deepseek",
        "base_url": "https://api.deepseek.com/v1",
        "api_key": "",
        "model": "deepseek-v4-flash[1m]",
        "enabled": True,
    },
    {
        "name": "openrouter",
        "base_url": "https://openrouter.ai/api/v1",
        "api_key": "",
        "model": "z-ai/glm-5.2:fre6",
        "enabled": True,  
    },
    {
        "name": "modelscope",
        "base_url": "https://api-inference.modelscope.cn/v1",
        "api_key": "",
        "model": "deepseek-ai/DeepSeek-V4.1-Flash",
        "enabled": True,
    },
    {
        "name": "mimo",
        "base_url": "https://api.xiaomimimo.com/v1",  # 按官方文档替换
        "api_key": "",
        "model": "mimo-v2.5",
        "enabled": True,
    },
]

_clients={}

def get_client(provider):
    name=provider['name']
    if name not in _clients:
        _clients[name]=OpenAI(
            base_url=provider['base_url'],
            api_key=provider['api_key'],
        )
    return _clients[name]

@retry(
    stop=stop_after_attempt(3),  # 最多重试3次（总共4次调用）
    wait=wait_exponential_jitter(initial=1, max=8), # 初始1s，上限8s，自带随机抖动
    retry=retry_if_exception_type((
        # OpenAI SDK抛出的网络、超时相关异常
        requests.exceptions.Timeout,
        requests.exceptions.ConnectionError,
        openai.APIConnectionError,
        openai.APITimeoutError,
        openai.InternalServerError,
    )),
    reraise=True # 重试全部失败后，把异常抛出去，交给外层try捕获
)
def call_chat_api(client, provider, system_prompt, user_prompt):
    response = client.chat.completions.create(
        model=provider['model'],
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        temperature=0.9,
        max_tokens=1000,
        timeout=18 # 单次请求的超时时间，18秒
    )
    return response

def get_answer(question_list,name):
    system_prompt=(
        "你是一名认真学习网课的大学生。请针对老师或同学提出的问题回答。要求:"
        "1.选择题直接输出选项,例如'c,d';简答题按照题目要求作答，字数贴合题目要求。"
        "2.禁止输出任何思考过程,只输出答案即可。"
        "3.直接输出回复内容,不要加引号或前缀。"
    )
    providers=[p for p in AI_PROVIDERS if p.get("enabled")]
    provider=next((p for p in AI_PROVIDERS if p['name']==name),None)
    if provider in providers:
        print(f"尝试使用{name}({provider.get('model')})")
        answerlist=[]
        for question in question_list:
            user_prompt=(
                f"问题:{question['plainTextTitle']}\n\n"
                f"请写出你的答案:"
            )
            try:
                client=get_client(provider)
                response = call_chat_api(client, provider, system_prompt, user_prompt)
                content=response.choices[0].message.content.strip()
                content=content.strip('"').strip("'").strip("“”")
                if content:
                    print(f"{provider['name']}生成成功")
                    answerlist.append({
                        "qid":question['qid'],
                        "type":question['type'],
                        "answer":content
                    })                    
            except Exception as e:
                print(f"{provider['name']}调用失败:{e}")
        return answerlist

def get_reply(title,content,name):
    system_prompt=(
        "你是一名认真学习网课的大学生。请针对老师或同学提出的课程讨论题,"
        "写一段100字以上的、有思考深度的回复。要求:"
        "1. 使用第一人称,口吻自然、友好;"
        "2. 结合课程内容,不要空话套话;"
        "3. 不要暴露你是 AI,不要用『作为AI』这种表达:"
        "4. 直接输出回复内容,不要加引号或前缀。"
    )
    user_prompt=(
        f"讨论标题：{title}\n"
        f"讨论正文：{content}\n\n"
        f"请写出你的回复:"
    )
    providers=[p for p in AI_PROVIDERS if p.get("enabled")]
    provider=next((p for p in AI_PROVIDERS if p['name']==name),None)
    if provider in providers:
        try:
            print(f"尝试使用{name}({provider.get('model')})")
            client=get_client(provider)
            response = call_chat_api(client, provider, system_prompt, user_prompt)
            content=response.choices[0].message.content.strip()
            content=content.strip('"').strip("'").strip("“”")
            if content:
                print(f"{provider['name']}生成成功")
                return content
        except Exception as e:
            print(f"{provider['name']}调用失败:{e}")
    
if __name__=="__main__":
    reply = get_reply(
        title="加密技术与信息隐藏技术之我见！",
        content="请结合自身所学说说加密技术和信息隐藏技术的优缺点，并列举各自可能的应用场景！",
        name="modelscope"
    )
    print("\n最终回复:", reply)