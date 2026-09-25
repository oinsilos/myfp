"""刷课任务管理:每个用户一个后台线程,支持启动/停止/状态查询。

任务类型:1视频 3PPT 6讨论 11作业 12测验。
- mode="types"    : 刷勾选类型中未完成的任务(默认全选=刷全部未完成)
- mode="duration" : 刷时长(仅视频,无论是否完成都从头重刷以增加学习时长),
                   停止条件由 duration_mode 决定:
                     target  —— 刷到该课程累计学习时长达到 duration_sec
                     session —— 本次累计新增学习时长达到 duration_sec
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
# 刷时长最多循环轮数(防止平台不计入时长时无限空转)
MAX_DURATION_ROUNDS = 10


def fmt_sec(sec) -> str:
    """秒 -> 可读时长文本。"""
    sec = int(sec or 0)
    h, m = sec // 3600, (sec % 3600) // 60
    if h:
        return f"{h}小时{m}分"
    return f"{m}分"


def mode_needs_ai(mode, types) -> bool:
    """判断所选刷课模式是否涉及需要 AI 的任务类型。

    types 为空表示全类型(刷全部未完成),此时也需要 AI。
    """
    if mode == "duration":
        return False
    allowed = set(types) if types else BRUSHABLE_TYPES
    return bool(allowed & AI_REQUIRED_TYPES)


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
    def __init__(self, username, course, mode, types, speed, ai_config=None,
                 duration_mode="target", duration_sec=0):
        self.username = username
        self.course = course          # {course_id, term_id, short_name, name}
        self.mode = mode
        self.types = list(types or [])
        self.speed = int(speed)
        self.ai_config = ai_config    # 用户的 AI provider 配置字典,可为 None
        self.duration_mode = duration_mode   # target | session(仅 mode=duration 生效)
        self.duration_sec = int(duration_sec or 0)
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
        if mode == "duration":
            cond = (f"刷到课程累计时长达 {fmt_sec(duration_sec)}"
                    if duration_mode == "target"
                    else f"本次新增时长达 {fmt_sec(duration_sec)}")
            self._log.append({"t": self._ts(), "level": "info", "msg": f"刷时长停止条件:{cond}"})
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
                "duration_mode": self.duration_mode,
                "duration_sec": self.duration_sec,
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

    def start(self, username, course, mode, types, speed, ai_config=None,
              duration_mode="target", duration_sec=0):
        if self.running(username):
            return False, "该用户已有刷课任务在运行"
        if mode == "duration" and int(duration_sec or 0) <= 0:
            return False, "刷时长需要设置大于 0 的时长"
        # 需要 AI 的模式,未配置 AI 则拒绝启动
        if mode_needs_ai(mode, types) and ai_config is None:
            return False, "该模式包含讨论/作业任务,需要先在「AI 设置」中配置并保存 AI"
        job = BrushJob(username, course, mode, types, speed, ai_config,
                       duration_mode=duration_mode, duration_sec=duration_sec)
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
            if job.mode == "duration":
                self._run_duration(job, sess, csrfkey, userid, referer, tasks)
            else:
                self._run_tasks(job, sess, csrfkey, userid, referer, tasks)
            job.status = "stopped" if job._stop.is_set() else job.status
            job.current_task = ""
            job.current_progress = ""
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

    def _run_tasks(self, job: BrushJob, sess, csrfkey, userid, referer, tasks):
        """按任务类型刷未完成项。"""
        targets = self._select(job, tasks)
        job.total_count = len(targets)
        job.log(f"共 {len(tasks)} 个任务,按勾选类型筛选出 {len(targets)} 个待刷任务")
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

    def _run_duration(self, job: BrushJob, sess, csrfkey, userid, referer, tasks):
        """刷时长:仅视频,无论是否完成都从头重刷,直到达到设定时长。

        duration_mode="target"  刷到课程累计学习时长达到 duration_sec
        duration_mode="session" 本次累计新增学习时长达到 duration_sec
        """
        videos = [t for t in tasks if t["type"] == 1 and (t.get("duration") or 0) > 0]
        if not videos:
            job.log("该课程没有可刷时长的视频任务", "warn")
            job.status = "done"
            return
        target = job.duration_sec
        c = job.course
        prog = learnprogress(sess, csrfkey, referer, c["term_id"])
        if prog is None:
            job.log("无法获取当前学习进度,刷时长中止", "error")
            job.status = "error"
            return
        start_total = prog["learntime_sec"]
        job.total_count = len(videos)
        if job.duration_mode == "target":
            job.log(f"当前累计学习时长 {fmt_sec(start_total)},目标 {fmt_sec(target)}")
            if start_total >= target:
                job.log("当前累计学习时长已达目标,无需再刷", "warn")
                job.status = "done"
                return
        else:
            job.log(f"本次需新增学习时长 {fmt_sec(target)}(当前累计 {fmt_sec(start_total)})")

        # 长视频优先,减少请求次数
        order = sorted(videos, key=lambda t: -(t.get("duration") or 0))
        added_local = 0                      # 本地估算:已重刷视频时长之和
        zero_gain_rounds = 0                 # 连续多少轮平台时长零增长
        reached = False
        stalled = False
        for rnd in range(1, MAX_DURATION_ROUNDS + 1):
            if job._stop.is_set() or reached or stalled:
                break
            job.log(f"开始第 {rnd} 轮刷时长(本地估算已新增 {fmt_sec(added_local)})")
            for t in order:
                if job._stop.is_set() or reached or stalled:
                    break
                d = t.get("duration") or 0
                job.current_task = f"[{rnd}轮] {describe_task(t)}(+{fmt_sec(d)})"
                job.current_key = task_key(t)

                def cb(cur, total):
                    job.current_progress = (
                        f"视频 {cur}/{total} 秒 · 本地估算已新增 {fmt_sec(added_local)}")
                savelearn_video(sess, csrfkey, t, job.speed, userid, referer,
                                current_time=0, stop_event=job._stop, progress_cb=cb)
                added_local += d
                job.done_count += 1
                job.refresh_seq += 1
                job.current_progress = ""
                job.current_key = ""
                job.log(f"完成 {describe_task(t)}(+{fmt_sec(d)},估算累计 {fmt_sec(added_local)})", "ok")
                if job._stop.wait(random.uniform(1, 3)):
                    break
                # 用真实接口校准,避免本地估算与平台计数偏差导致刷过头
                cur_prog = learnprogress(sess, csrfkey, referer, c["term_id"])
                if cur_prog:
                    real_total = cur_prog["learntime_sec"]
                    real_added = real_total - start_total
                    if job.duration_mode == "target":
                        job.log(f"实际累计 {fmt_sec(real_total)} / 目标 {fmt_sec(target)}")
                        if real_total >= target:
                            reached = True
                    else:
                        job.log(f"实际新增 {fmt_sec(real_added)} / 目标 {fmt_sec(target)}")
                        if real_added >= target:
                            reached = True
                elif (job.duration_mode == "target" and start_total + added_local >= target) \
                        or (job.duration_mode == "session" and added_local >= target):
                    # 接口暂时取不到进度时,退回本地估算判断
                    reached = True
            # 平台时长零增长持续 2 轮 → 判定平台不计入重复计时,提前止损
            if not reached and added_local > 0:
                chk = learnprogress(sess, csrfkey, referer, c["term_id"])
                gained = (chk["learntime_sec"] - start_total) if chk else 0
                if gained <= 0:
                    zero_gain_rounds += 1
                    if zero_gain_rounds >= 2:
                        job.log("平台连续 2 轮未计入重刷时长,已停止(该课程可能不支持重复计时)", "warn")
                        stalled = True
                else:
                    zero_gain_rounds = 0
            if not reached and not stalled and rnd == MAX_DURATION_ROUNDS:
                job.log(f"已循环 {rnd} 轮仍未达目标,停止以避免长时间空转", "warn")
        job.status = "stopped" if job._stop.is_set() else "done"
        if reached:
            job.log(f"已达到设定时长:{'累计' if job.duration_mode == 'target' else '本次新增'} "
                    f"{fmt_sec(target)}", "ok")

    def _select(self, job: BrushJob, tasks):
        """按勾选类型筛选待刷的未完成任务(duration 模式走 _run_duration,不经过此处)。"""
        allowed = set(job.types) if job.types else BRUSHABLE_TYPES
        return [t for t in tasks if t["type"] in allowed and not task_done(t)]

    def _exec_one(self, job: BrushJob, sess, csrfkey, userid, referer, task) -> bool:
        t = task["type"]
        speed = job.speed
        try:
            if t == 1:
                # 从未完成处继续(video 的 starttime 为已学秒数)
                start = task.get("starttime") or 0

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