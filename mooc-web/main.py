"""挑灯夜读 · 中国大学MOOC刷课助手 —— FastAPI 主程序。

- 用户页面 `GET /`      :局域网可访问
- 管理页面 `GET /admin` :仅本机(localhost)可访问

并发模型:
- 阻塞的 MOOC 网络调用(课程列表/任务/刷课) → def 端点(Starlette 线程池执行)与后台线程;
- 内存状态查询(扫码状态/刷课状态) → async def,零阻塞;
- 扫码轮询、刷课任务各自运行在独立后台线程,支持中止。
"""

import time
from pathlib import Path
from urllib.parse import urlparse

import requests as _requests
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field

from core.ai_client import test_provider
from core.courses import getcourselist
from core.disussion import get_discuss, submit_reply_manual
from core.homework import fetch_paper, submit_manual
from core.login import get_userid
from core.tasks import gettaskid
from core.video import learnprogress
from services.brush import BRUSHABLE_TYPES, BrushManager, decorate_tasks, mode_needs_ai
from services.login import LoginFlowManager
from services.store import Store, validate_username

BASE_DIR = Path(__file__).resolve().parent
TEMPLATES_DIR = BASE_DIR / "templates"

LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost"}

# 课程图片代理允许的域名后缀(防 SSRF);课程封面均来自网易 nosdn CDN
IMG_ALLOWED_SUFFIX = ".nosdn.127.net"
IMG_FETCH_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
    "Referer": "https://www.icourse163.org/",
}

store = Store()
loops = LoginFlowManager(store)
brushman = BrushManager(store)

app = FastAPI(title="挑灯夜读 · MOOC刷课助手", version="1.0.0")


@app.middleware("http")
async def admin_local_only(request: Request, call_next):
    """管理页面与管理 API 仅允许本机访问。"""
    path = request.url.path
    if path.startswith("/admin") or path.startswith("/api/admin"):
        host = request.client.host if request.client else ""
        if host not in LOOPBACK_HOSTS:
            return JSONResponse(status_code=403, content={"detail": "管理页面仅限本机访问"})
        # 显式禁止缓存敏感管理页
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        return response
    return await call_next(request)


# ============================================================
# 页面
# ============================================================
@app.get("/", include_in_schema=False)
def page_user():
    # no-store:页面迭代频繁,避免浏览器缓存旧版 HTML
    return FileResponse(
        TEMPLATES_DIR / "user.html",
        headers={"Cache-Control": "no-store"},
    )


@app.get("/admin", include_in_schema=False)
def page_admin():
    return FileResponse(
        TEMPLATES_DIR / "admin.html",
        headers={"Cache-Control": "no-store"},
    )


# ============================================================
# 请求模型
# ============================================================
class LoginIn(BaseModel):
    username: str


class AiProviderIn(BaseModel):
    """AI provider 配置:由浏览器本地保存,随请求传入,服务端不落盘。"""
    name: str = Field(min_length=1, max_length=64)
    base_url: str = Field(min_length=1, max_length=256)
    api_key: str = Field(min_length=1, max_length=512)
    model: str = Field(min_length=1, max_length=128)


class BrushStartIn(BaseModel):
    username: str
    course: dict                       # {course_id, term_id, short_name, name}
    mode: str = "types"                # types(按勾选类型刷未完成) | duration(刷时长,仅视频)
    types: list[int] = []              # mode=types 时生效;留空=全部类型
    speed: int = Field(300, ge=1, le=3600)
    ai: AiProviderIn | None = None     # 需要 AI 的任务类型时必须携带(来自浏览器本地配置)
    duration_mode: str = "target"      # 刷时长停止条件:target(刷到课程总时长) | session(本次新增时长)
    duration_sec: int = Field(0, ge=0, le=3600000)   # 目标/新增时长(秒)


class StopIn(BaseModel):
    username: str


class AITestIn(BaseModel):
    """AI 连通性测试:配置随请求传入,服务端仅做一次探测,不保存。"""
    name: str = Field(min_length=1, max_length=64)
    base_url: str = Field(min_length=1, max_length=256)
    api_key: str = Field(min_length=1, max_length=512)
    model: str = Field(min_length=1, max_length=128)


class AdminAddUser(BaseModel):
    username: str


class CourseRef(BaseModel):
    """课程定位信息(referer 拼接用)。"""
    course_id: str
    term_id: str
    short_name: str = ""
    name: str = ""


class DiscussSubmitIn(BaseModel):
    username: str
    course: CourseRef
    content_id: str      # 帖子 id
    unit_id: str         # 讨论单元 id
    content: str = Field(min_length=1, max_length=5000)


class ManualAnswer(BaseModel):
    qid: str
    type: int
    content: str = Field(max_length=20000)


class PaperSubmitIn(BaseModel):
    username: str
    course: CourseRef
    content_id: str      # 作业/测验的 contentId(tid)
    answers: list[ManualAnswer]


def _safe_username(name: str) -> str:
    try:
        return validate_username(name)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e


def _user_session_or_401(username: str):
    sess = store.require_session(username)
    if sess is None:
        raise HTTPException(status_code=401, detail="未登录或cookie已失效,请重新扫码")
    return sess


def _course_ref_to_referer(sess, username, course: CourseRef) -> str:
    """校验用户会话并拼接课程 referer。"""
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    if not csrfkey:
        raise HTTPException(status_code=401, detail="cookie信息不完整,请重新扫码")
    return f"https://www.icourse163.org/learn/{course.short_name}-{course.course_id}?tid={course.term_id}"


# ============================================================
# 用户端 API
# ============================================================
@app.post("/api/user/login", tags=["user"])
def user_login(payload: LoginIn):
    """输入用户名:后端检查本地 cookie 是否存在且有效。

    有效 → {status: ok, user}
    无效/不存在 → 生成扫码登录流程,返回二维码图片(base64)与 flow_id。

    仅允许已登记用户(由管理页增删)登录;未登记的用户名直接拒绝,不生成二维码。
    """
    username = _safe_username(payload.username)
    if not store.has_user(username):
        raise HTTPException(
            status_code=403,
            detail=f"用户「{username}」不存在,请联系管理员先在管理页添加该用户")
    sess = store.require_session(username)
    if sess is not None:
        return {"status": "ok", "user": username}
    flow = loops.start(username)
    snap = flow.snapshot()
    return {
        "status": "qr",
        "user": username,
        "flow_id": snap["flow_id"],
        "message": snap["message"],
        "image_b64": snap["image_b64"],
    }


@app.get("/api/user/login/status", tags=["user"])
async def user_login_status(flow_id: str = Query(...)):
    """前端轮询扫码状态:waiting/scanned/success/expired/error。"""
    for f in loops.iter_flows():
        if f.flow_id == flow_id:
            return f.snapshot()
    raise HTTPException(status_code=404, detail="登录流程不存在或已过期")


@app.post("/api/user/login/cancel", tags=["user"])
async def user_login_cancel(flow_id: str = Query(...)):
    """用户手动关闭二维码弹窗时,取消后台轮询线程。"""
    return {"ok": loops.cancel_by_flow_id(flow_id)}


@app.get("/api/user/courses", tags=["user"])
def user_courses(username: str = Query(...)):
    username = _safe_username(username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    userid = get_userid(sess)
    if not csrfkey or not userid:
        raise HTTPException(status_code=401, detail="cookie信息不完整,请重新扫码")
    courses = getcourselist(sess, csrfkey, userid)
    return {"courses": courses}


@app.get("/api/user/course/detail", tags=["user"])
def user_course_detail(
    username: str = Query(...),
    course_id: str = Query(...),
    term_id: str = Query(...),
    short_name: str = Query(...),
):
    """返回课程学习进度 + 任务列表(已完成/未完成标记)。"""
    username = _safe_username(username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    userid = get_userid(sess)
    if not csrfkey or not userid:
        raise HTTPException(status_code=401, detail="cookie信息不完整,请重新扫码")
    referer = f"https://www.icourse163.org/learn/{short_name}-{course_id}?tid={term_id}"
    progress = learnprogress(sess, csrfkey, referer, term_id)
    tasks = gettaskid(sess, csrfkey, short_name, course_id, term_id)
    return {"progress": progress, "tasks": decorate_tasks(tasks)}


@app.get("/api/user/progress", tags=["user"])
def user_progress(
    username: str = Query(...),
    course_id: str = Query(...),
    term_id: str = Query(...),
    short_name: str = Query(...),
):
    """仅返回学习时长进度(供前端周期性刷新)。"""
    username = _safe_username(username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    referer = f"https://www.icourse163.org/learn/{short_name}-{course_id}?tid={term_id}"
    progress = learnprogress(sess, csrfkey, referer, term_id)
    return {"progress": progress}


@app.get("/api/user/img", tags=["user"])
def user_img(url: str = Query(...)):
    """课程封面图代理:解决浏览器直连 CDN 的 Referer 防盗链/mixed-content 问题。

    仅允许网易 nosdn CDN 域名(防 SSRF),响应可缓存一天。
    """
    target = url.strip()
    host = (urlparse(target).hostname or "").lower()
    if not host.endswith(IMG_ALLOWED_SUFFIX):
        raise HTTPException(status_code=400, detail="不允许的图片域名")
    if target.startswith("http://"):
        target = "https://" + target[len("http://"):]
    try:
        r = _requests.get(target, headers=IMG_FETCH_HEADERS, timeout=15)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"图片拉取失败:{e}") from e
    if r.status_code != 200 or not r.content:
        raise HTTPException(status_code=502, detail=f"图片拉取失败:HTTP {r.status_code}")
    return Response(
        content=r.content,
        media_type=r.headers.get("Content-Type", "image/jpeg"),
        headers={"Cache-Control": "public, max-age=86400"},
    )


# ---------- 手动作答:讨论 / 作业 / 测验 ----------

@app.get("/api/user/task/discuss", tags=["user"])
def task_discuss(
    username: str = Query(...),
    course_id: str = Query(...),
    term_id: str = Query(...),
    short_name: str = Query(...),
    content_id: str = Query(...),
    unit_id: str = Query(...),
):
    """查看讨论帖标题与正文(手动作答用)。"""
    username = _safe_username(username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    if not csrfkey:
        raise HTTPException(status_code=401, detail="cookie信息不完整,请重新扫码")
    referer = f"https://www.icourse163.org/learn/{short_name}-{course_id}?tid={term_id}"
    task = {"contentId": content_id, "unitId": unit_id}
    title, content = get_discuss(sess, csrfkey, task, referer)
    return {"title": title, "content": content}


@app.post("/api/user/task/discuss/submit", tags=["user"])
def task_discuss_submit(payload: DiscussSubmitIn):
    """手动回复讨论帖。"""
    username = _safe_username(payload.username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    if not csrfkey:
        raise HTTPException(status_code=401, detail="cookie信息不完整")
    referer = _course_ref_to_referer(sess, username, payload.course)
    task = {"contentId": payload.content_id, "unitId": payload.unit_id}
    ok, msg = submit_reply_manual(sess, csrfkey, task, referer, payload.content)
    if not ok:
        raise HTTPException(status_code=409, detail=msg)
    return {"ok": True, "message": msg}


@app.get("/api/user/task/paper", tags=["user"])
def task_paper(
    username: str = Query(...),
    course_id: str = Query(...),
    term_id: str = Query(...),
    short_name: str = Query(...),
    content_id: str = Query(...),
):
    """查看作业/测验试卷题目(客观题含选项)。"""
    username = _safe_username(username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    if not csrfkey:
        raise HTTPException(status_code=401, detail="cookie信息不完整,请重新扫码")
    referer = f"https://www.icourse163.org/learn/{short_name}-{course_id}?tid={term_id}"
    paper, questions = fetch_paper(sess, csrfkey, {"contentId": content_id}, referer)
    if paper is None:
        raise HTTPException(status_code=502, detail="试卷拉取失败")
    return {
        "tname": paper.get("tname", ""),
        "submitStatus": paper.get("submitStatus"),
        "questions": questions,
    }


@app.post("/api/user/task/paper/submit", tags=["user"])
def task_paper_submit(payload: PaperSubmitIn):
    """以用户手动作答提交作业/测验。"""
    username = _safe_username(payload.username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    if not csrfkey:
        raise HTTPException(status_code=401, detail="cookie信息不完整")
    referer = _course_ref_to_referer(sess, username, payload.course)
    task = {"contentId": payload.content_id}
    answers = [a.model_dump() for a in payload.answers]
    ok, msg = submit_manual(sess, csrfkey, task, referer, answers)
    if not ok:
        raise HTTPException(status_code=409, detail=msg)
    return {"ok": True, "message": msg}


@app.post("/api/user/brush/start", tags=["user"])
def brush_start(payload: BrushStartIn):
    username = _safe_username(payload.username)
    course = payload.course
    for key in ("course_id", "term_id", "short_name", "name"):
        if not course.get(key):
            raise HTTPException(status_code=400, detail=f"课程信息缺少字段:{key}")
    mode = payload.mode
    if mode not in ("types", "duration"):
        raise HTTPException(status_code=400, detail="mode 必须为 types/duration")
    types = payload.types if mode == "types" else []
    if mode == "types" and payload.types and not set(types) & BRUSHABLE_TYPES:
        raise HTTPException(status_code=400, detail="勾选的任务类型无效")
    if mode == "duration":
        if payload.duration_mode not in ("target", "session"):
            raise HTTPException(status_code=400, detail="duration_mode 必须为 target/session")
        if payload.duration_sec <= 0:
            raise HTTPException(status_code=400, detail="请设置要刷的时长(大于 0)")
    ai_config = payload.ai.model_dump() if payload.ai else None
    if mode_needs_ai(mode, types) and ai_config is None:
        raise HTTPException(
            status_code=409,
            detail="该模式包含讨论/作业任务,请先在「AI 设置」中填写 AI 配置")
    ok, msg = brushman.start(username, course, mode, types, payload.speed, ai_config,
                             duration_mode=payload.duration_mode,
                             duration_sec=payload.duration_sec)
    if not ok:
        raise HTTPException(status_code=409, detail=msg)
    return {"ok": True, "message": msg}


@app.post("/api/user/brush/stop", tags=["user"])
def brush_stop(payload: StopIn):
    username = _safe_username(payload.username)
    ok = brushman.stop(username)
    return {"ok": ok}


@app.get("/api/user/brush/status", tags=["user"])
async def brush_status(username: str = Query(...)):
    username = _safe_username(username)
    job = brushman.get(username)
    if job is None:
        return {"running": False, "status": "idle"}
    return job.snapshot()


# ============================================================
# 用户端 AI(配置存于浏览器本地,服务端不保存)
# ============================================================
@app.post("/api/user/ai/test", tags=["ai"])
def ai_test(payload: AITestIn):
    """AI 连通性测试(阻塞调用,由 Starlette 线程池执行)。

    配置由前端浏览器本地存储提供,随请求一次性传入,服务端不保存。
    """
    ok, message = test_provider({
        "name": payload.name.strip(),
        "base_url": payload.base_url.strip(),
        "api_key": payload.api_key.strip(),
        "model": payload.model.strip(),
    })
    return {"ok": ok, "message": message}


# ============================================================
# 管理端 API(仅本机访问,由中间件强制)
# ============================================================
@app.get("/api/admin/users", tags=["admin"])
async def admin_users():
    rows = []
    for u in store.list_users():
        rows.append({
            "username": u["username"],
            "created_at": u["created_at"],
            "note": u["note"],
            "has_cookie": store.has_cookie(u["username"]),
            "cookie_file": f"cookie_{u['username']}.json",
            "valid": store.cached_validity(u["username"]),
            "brush_running": brushman.running(u["username"]),
        })
    return {"users": rows}


@app.post("/api/admin/users", tags=["admin"])
def admin_add_user(payload: AdminAddUser):
    username = _safe_username(payload.username)
    if not store.add_user(username):
        raise HTTPException(status_code=409, detail="用户已存在")
    return {"ok": True, "username": username}


@app.delete("/api/admin/users/{username}", tags=["admin"])
def admin_del_user(username: str):
    username = _safe_username(username)
    brushman.stop(username)
    loops.cancel(username)
    store.remove_user(username)
    return {"ok": True}


@app.post("/api/admin/users/{username}/check", tags=["admin"])
def admin_check_user(username: str):
    """强制真实校验该用户 cookie 有效性。"""
    username = _safe_username(username)
    valid = store.check_validity(username, force=True)
    return {"username": username, "valid": valid}


@app.get("/api/admin/users/{username}/courses", tags=["admin"])
def admin_user_courses(username: str):
    """查看用户每门课的学习时长进度及 cookie 是否失效。"""
    username = _safe_username(username)
    sess = _user_session_or_401(username)
    csrfkey = sess.cookies.get("NTESSTUDYSI")
    userid = get_userid(sess)
    if not csrfkey or not userid:
        raise HTTPException(status_code=401, detail="cookie信息不完整")
    courses = getcourselist(sess, csrfkey, userid)
    result = []
    for c in courses:
        referer = (
            f"https://www.icourse163.org/learn/"
            f"{c['shortName']}-{c['courseId']}?tid={c['termId']}"
        )
        prog = learnprogress(sess, csrfkey, referer, c["termId"])
        result.append({**c, "progress": prog})
    return {"valid": True, "courses": result}


@app.get("/api/health", tags=["meta"])
async def health():
    return {"ok": True, "ts": int(time.time())}