"""刷课任务管理:每个用户一个后台线程,支持启动/停止/状态查询。

任务类型:1视频 3PPT 6讨论 11作业 12测验。
- mode="all"      : 刷全部未完成(视频/PPT/讨论/作业)
- mode="types"    : 只刷勾选类型中未完成的
- mode="duration" : 刷时长(仅视频,无论是否完成;已完成视频从头重刷以增加学习时长)
"""

import random
import threading
import time
from collections import deque

from core.login import get_userid
from core.tasks import gettaskid
from core.video import learnprogress, savelearn_video
from core.ppt import savelearn_ppt
from core.disussion import savelearn_discuss
from core.homework import save_hw

TYPE_NAMES = {1: "视频", 3: "PPT", 6: "讨论", 11: "作业", 12: "测验"}
BRUSHABLE_TYPES = {1, 3, 6, 11}
# 需要调用 AI 的任务类型(讨论 6 / 作业 11)
AI_REQUIRED_TYPES = {6, 11}

def mode_needs_ai(mode, types) -> bool:
    """判断所选刷课模式是否涉及需要 AI 的任务类型。"""
    if mode == "all":
        return True
    if mode == "types":
        return bool(set(types or []) & AI_REQUIRED_TYPES)
    return False


def task_done(task) -> bool:
    """判断任务是否已完成。"""
    tp = task["type"]
    if tp in (1, 3, 6):
        return (task.get("completePercent") or 0) >= 1.0
    if tp in (11, 12):
        total = task.get("totalScore")
        user = task.get("userScore")
        if total and user is not None and user >= total:
            return True
        return (task.get("usedTryCount") or 0) > 0
    return False


def decorate_tasks(tasks):
    """为任务补充展示字段:类型名、是否完成。"""
    out = []
    for t in tasks:
        d = dict(t)
        d["type_name"] = TYPE_NAMES.get(t["type"], "其他")
        d["done"] = task_done(d)
        d["title"] = d.get("lessonName") or d.get("hwName") or d.get("quizName") or ""
        out.append(d)
    return out


def describe_task(task) -> str:
    ch = task.get("chapterName") or ""
    title = task.get("lessonName") or task.get("hwName") or task.get("quizName") or "-"
    return f"[{TYPE_NAMES.get(task['type'], '?')}] {ch} / {title}"


def task_key(task) -> str:
    """任务定位键:前端据此高亮当前正在刷的任务行。"""
    uid = task.get("unitId") or task.get("contentId") or ""
    return f"{task.get('lessonId') or ''}-{uid}-{task['type']}"


class BrushJob:
    def __init__(self, username, course, mode, types, speed, ai_config=None):
        self.username = username
        self.course = course          # {course_id, term_id, short_name, name}
        self.mode = mode
        self.types = list(types or [])
        self.speed = int(speed)
        self.ai_config = ai_config    # 用户的 AI provider 配置字典,可为 None
        self.status = "running"       # running/done/stopped/error
        self.current_task = ""
        self.current_key = ""         # 当前任务定位键(高亮用)
        self.current_progress = ""
        self.done_count = 0
        self.total_count = 0
        self.refresh_seq = 0          # 完成一个任务后自增,前端据此刷新任务列表
        self.started_at = time.time()
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._log = deque(maxlen=200)
        self._log.append({"t": self._ts(), "level": "info",
                          "msg": f"刷课任务启动(模式:{mode},速度:{speed},AI:{'已配置' if ai_config else '未配置'})"})
        self._thread = None

    @staticmethod
    def _ts():
        return time.strftime("%H:%M:%S")

    def log(self, msg, level="info"):
        with self._lock:
            self._log.append({"t": self._ts(), "level": level, "msg": msg})

    def snapshot(self):
        with self._lock:
            return {
                "username": self.username,
                "course": self.course,
                "mode": self.mode,
                "types": self.types,
                "speed": self.speed,
                "status": self.status,
                "running": self.status == "running",
                "current_task": self.current_task,
                "current_key": self.current_key,
                "current_progress": self.current_progress,
                "done_count": self.done_count,
                "total_count": self.total_count,
                "refresh_seq": self.refresh_seq,
                "started_at": self.started_at,
                "has_ai": bool(self.ai_config),
                "log": list(self._log),
            }


class BrushManager:
    def __init__(self, store):
        self.store = store
        self._jobs = {}
        self._lock = threading.Lock()

    def get(self, username) -> BrushJob | None:
        with self._lock:
            return self._jobs.get(username)

    def running(self, username) -> bool:
        job = self.get(username)
        return job is not None and job.status == "running"

    def start(self, username, course, mode, types, speed, ai_config=None):
        if self.running(username):
            return False, "该用户已有刷课任务在运行"
        # 需要 AI 的模式,未配置 AI 则拒绝启动
        if mode_needs_ai(mode, types) and ai_config is None:
            return False, "该模式包含讨论/作业任务,需要先在「AI 设置」中配置并保存 AI"
        job = BrushJob(username, course, mode, types, speed, ai_config)
        with self._lock:
            self._jobs[username] = job
        job._thread = threading.Thread(
            target=self._run, args=(job,), name=f"brush-{username}", daemon=True)
        job._thread.start()
        return True, "任务已启动"

    def stop(self, username) -> bool:
        job = self.get(username)
        if job is not None and job.status == "running":
            job._stop.set()
            job.log("收到停止指令,将在当前阶段结束后停止", "warn")
            return True
        return False

    # ---------- 后台执行 ----------
    def _run(self, job: BrushJob):
        try:
            sess = self.store.require_session(job.username)
            if sess is None:
                job.log("cookie 无效或缺失,无法开始刷课", "error")
                job.status = "error"
                return
            csrfkey = sess.cookies.get("NTESSTUDYSI")
            userid = get_userid(sess)
            c = job.course
            referer = f"https://www.icourse163.org/learn/{c['short_name']}-{c['course_id']}?tid={c['term_id']}"
            job.log("正在获取课程任务列表...")
            tasks = gettaskid(sess, csrfkey, c["short_name"], c["course_id"], c["term_id"])
            targets = self._select(job, tasks)
            job.total_count = len(targets)
            job.log(f"共 {len(tasks)} 个任务,按模式筛选出 {len(targets)} 个待刷任务")
            if not targets:
                job.log("没有需要刷的任务", "warn")
                job.status = "done"
                return
            for i, task in enumerate(targets, 1):
                if job._stop.is_set():
                    break
                job.current_task = f"[{i}/{len(targets)}] {describe_task(task)}"
                job.current_key = task_key(task)
                job.log(f"开始处理:{describe_task(task)}")
                ok = self._exec_one(job, sess, csrfkey, userid, referer, task)
                job.done_count += 1
                job.refresh_seq += 1
                job.log(f"第 {i}/{len(targets)} 个任务{'成功' if ok else '失败'}", "ok" if ok else "error")
                job.current_progress = ""
                job.current_key = ""
                if job._stop.wait(random.uniform(1, 3)):
                    job.log("收到停止指令,任务中止", "warn")
                    break
            job.status = "stopped" if job._stop.is_set() else "done"
            job.current_task = ""
            job.refresh_seq += 1  # 结束也通知前端刷新一次
            job.log("刷课任务结束", "info")
            try:
                prog = learnprogress(sess, csrfkey, referer, c["term_id"])
                if prog:
                    job.log(f"当前学习进度:{prog['completion_text']} | {prog['learntime_text']}")
            except Exception as e:
                job.log(f"获取最终进度失败:{e}", "warn")
        except Exception as e:
            job.log(f"任务异常:{e}", "error")
            job.status = "error"

    def _select(self, job: BrushJob, tasks):
        if job.mode == "duration":
            # 刷时长:只刷视频类型,无论是否完成
            return [t for t in tasks if t["type"] == 1]
        if job.mode == "types":
            allowed = set(job.types)
            return [t for t in tasks if t["type"] in allowed and not task_done(t)]
        # mode == all:全部未完成
        return [t for t in tasks if t["type"] in BRUSHABLE_TYPES and not task_done(t)]

    def _exec_one(self, job: BrushJob, sess, csrfkey, userid, referer, task) -> bool:
        t = task["type"]
        speed = job.speed
        try:
            if t == 1:
                start = task.get("starttime") or 0
                if job.mode == "duration" and task_done(task):
                    # 已完成视频从头重刷,以增加学习时长
                    start = 0
                    job.log("已完成视频,按刷时长模式从头重刷", "warn")

                def cb(cur, total):
                    job.current_progress = f"视频 {cur}/{total} 秒"
                savelearn_video(
                    sess, csrfkey, task, speed, userid, referer,
                    current_time=start, stop_event=job._stop, progress_cb=cb)
                return True
            if t == 3:
                savelearn_ppt(sess, csrfkey, task, speed, referer)
                return True
            if t == 6:
                return bool(savelearn_discuss(sess, csrfkey, task, referer, job.ai_config))
            if t == 11:
                deadline = task.get("deadline")
                if deadline and deadline <= time.time():
                    job.log("作业已过截止时间,跳过", "warn")
                    return True
                return bool(save_hw(sess, csrfkey, task, referer, job.ai_config))
        except Exception as e:
            job.log(f"任务执行异常:{e}", "error")
            return False
        return False