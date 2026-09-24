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

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from core.ai_client import test_provider
from core.courses import getcourselist
from core.login import get_userid
from core.tasks import gettaskid
from core.video import learnprogress
from services.brush import BrushManager, decorate_tasks, mode_needs_ai
from services.login import LoginFlowManager
from services.store import Store, validate_username

BASE_DIR = Path(__file__).resolve().parent
TEMPLATES_DIR = BASE_DIR / "templates"

LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost"}

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
    return FileResponse(TEMPLATES_DIR / "user.html")


@app.get("/admin", include_in_schema=False)
def page_admin():
    return FileResponse(TEMPLATES_DIR / "admin.html")


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
    mode: str = "all"                  # all | types | duration
    types: list[int] = []              # mode=types 时生效
    speed: int = Field(600, ge=1, le=3600)
    ai: AiProviderIn | None = None     # 需要 AI 的任务类型时必须携带(来自浏览器本地配置)


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


# ============================================================
# 用户端 API
# ============================================================
@app.post("/api/user/login", tags=["user"])
def user_login(payload: LoginIn):
    """输入用户名:后端检查本地 cookie 是否存在且有效。

    有效 → {status: ok, user}
    无效/不存在 → 生成扫码登录流程,返回二维码图片(base64)与 flow_id。
    """
    username = _safe_username(payload.username)
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


@app.post("/api/user/brush/start", tags=["user"])
def brush_start(payload: BrushStartIn):
    username = _safe_username(payload.username)
    course = payload.course
    for key in ("course_id", "term_id", "short_name", "name"):
        if not course.get(key):
            raise HTTPException(status_code=400, detail=f"课程信息缺少字段:{key}")
    mode = payload.mode
    if mode not in ("all", "types", "duration"):
        raise HTTPException(status_code=400, detail="mode 必须为 all/types/duration")
    types = payload.types if mode == "types" else []
    if mode == "types" and not types:
        raise HTTPException(status_code=400, detail="按类型刷课需要至少勾选一种类型")
    ai_config = payload.ai.model_dump() if payload.ai else None
    if mode_needs_ai(mode, types) and ai_config is None:
        raise HTTPException(
            status_code=409,
            detail="该模式包含讨论/作业任务,请先在「AI 设置」中填写 AI 配置")
    ok, msg = brushman.start(username, course, mode, types, payload.speed, ai_config)
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