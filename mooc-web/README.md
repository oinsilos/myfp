# 挑灯夜读 · 中国大学MOOC刷课助手（Web 版）

基于 FastAPI(≥0.100) + 原生 HTML/JS 的 MOOC(icourse163) 刷课助手。
核心刷课逻辑来自既有模块化代码（`core/`），Web 层对其做了多用户适配：
所有网络函数改为显式传入 `requests.Session`，实现用户间 cookie 隔离。

## 项目结构

```
mooc-web/
├── main.py               # FastAPI 入口:页面路由 + 用户端/管理端 API + 本机限制中间件
├── core/                 # MOOC 核心逻辑(既有模块的 Web 适配版)
│   ├── __init__.py       #   统一请求头 / 超时 / 新会话工厂
│   ├── login.py          #   扫码登录:二维码获取、轮询、cookie 校验、userid 解析
│   ├── cookie.py         #   cookie 文件读写(按路径隔离)
│   ├── courses.py        #   课程列表抓取
│   ├── tasks.py          #   课程任务列表抓取(视频/PPT/讨论/作业/测验)
│   ├── video.py          #   视频刷课时(带停止事件与进度回调)、学习进度查询
│   ├── ppt.py            #   PPT 文档任务提交
│   ├── homework.py       #   作业提交(AI 作答)
│   ├── disussion.py      #   讨论回复(AI 生成)
│   ├── auth.py           #   请求签名
│   └── ai_client.py      #   AI 多 provider 客户端
├── services/
│   ├── store.py          #   用户注册表 + cookie_{username}.json 隔离 + 有效性缓存
│   ├── login.py          #   扫码登录流程状态机(后台轮询线程)
│   └── brush.py          #   刷课任务管理(每用户一个后台线程,可启停)
├── templates/
│   ├── user.html         #   用户页面(局域网可访问)
│   └── admin.html        #   管理页面(仅本机可访问)
├── data/
│   ├── users.json        #   用户注册表(运行时自动生成)
│   └── cookies/          #   cookie_{username}.json 按用户隔离存储
├── requirements.txt
└── README.md
```

> **服务端不保存任何 AI Key**：AI 配置（含 API Key）由用户在本浏览器填写，
> 存于 `localStorage`，仅在启动刷课或点击连通性测试时随请求一次性传给后端，
> 使用后即随任务对象释放，服务端不落盘、不缓存。

## 调用关系

```
浏览器(user.html) ──> /api/user/* ──> services.login / services.brush ──> core.* ──> icourse163
浏览器(admin.html) ─> /api/admin/* ─> services.store(本地文件)
```

- 登录链路：`POST /api/user/login` → 无有效 cookie 时 `LoginFlowManager.start()` 后台线程拉取二维码并轮询 `poll.do`；成功后写 `data/cookies/cookie_{username}.json` 并登记用户。
- **扫码会话隔离**：登录流程全程使用 `new_clean_session()` 创建的**纯净会话**（不带任何历史 cookie），不会加载该用户已存凭证；二维码握手（`code.do`）在该会话上完成以保证 pollKey 同源，而**二维码图片下载使用独立的一次性纯净会话**，避免图片 CDN 域 cookie 污染登录会话。落盘时仅保留认证域（`AUTH_COOKIE_DOMAINS = icourse163.org / 163.com`，按域名后缀匹配）的 cookie，剔除第三方域写入的追踪类 cookie。
- **刷课链路**：`POST /api/user/brush/start` → `BrushManager` 起后台线程逐任务执行（视频/PPT/讨论/作业），任务完成后 `refresh_seq` 自增，前端据此刷新任务列表与进度；`POST /api/user/brush/stop` 置停止事件，任务在安全点退出。

## 并发模型

| 操作 | 方式 | 原因 |
|---|---|---|
| 课程列表/任务/进度查询等网络调用 | Starlette 线程池（普通 def 端点） | core 均为阻塞 requests 调用 |
| 刷课任务 | 每用户独立后台线程 | 长时循环 + 睡眠,不可占请求线程 |
| 扫码轮询 | 后台线程(2s 间隔,可取消) | 同上 |
| 内存状态读取(刷课/登录状态) | async 端点 | 纯内存,零阻塞 |

core 代码为纯同步实现，未引入 aiohttp 等异步重写；FastAPI 的线程池 + 后台线程组合即满足本场景。

## 运行

```bash
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000   # 用户页需局域网访问
```

- 用户页面：`http://<本机局域网IP>:8000/`
- 管理页面：仅本机 `http://127.0.0.1:8000/admin`（其他主机访问返回 403）

## 页面与接口说明

### 用户页面
1. 输入用户名 → 后端**先校验用户名单**（`data/users.json`，由管理页增删）：
   - 未登记的用户名 → 返回 403「用户不存在」，**不生成二维码**；
   - 已登记 → 检查该用户的 `cookie_{username}.json`：
     - 存在且未过期 → 直接返回课程列表（含图片）；
     - 不存在或已过期 → 生成并返回扫码二维码（base64 PNG），前端弹窗展示；
2. 扫码弹窗可随时手动关闭（右上角「×」、点击遮罩或「取消」按钮），关闭时会调用
   `POST /api/user/login/cancel` 取消后台轮询线程；
3. 扫码成功后弹窗自动关闭，自动拉取课程列表；
4. 点击课程 → 展示学习时长（每 5 秒自动刷新）与任务列表（✓已完成 / ✗未完成）；
5. 刷课控制台：
   - **按类型勾选**：视频 / PPT / 讨论 / 作业 四个类型可勾选一个或多个，只刷所选类型中未完成的任务（默认全选=刷全部未完成）；
   - **刷时长**：勾选后仅刷视频类型（此时类型勾选自动置灰不参与），无论视频是否已完成都从头重刷以增加学习时长；
     停止条件二选一：
       - `刷到课程总时长`（target）：刷到该课程**累计**学习时长达设定值即停止；
       - `本次刷够时长`（session）：**本次新增**学习时长达设定值后停止；
     时长可填数值 + 选择分钟/小时，界面实时显示"刷到课程累计学习时长达 3时0分 即停止"这类确认文案；
   - 速度可自定义（默认 300 秒/次提交）；
   - 启动/停止共用同一按钮；勾选讨论/作业而未配置 AI 时禁止启动（前后端双重拦截）；
6. 每个任务完成后服务端 `refresh_seq` 变化，前端自动刷新任务列表显示最新进度。
7. **手动作答**：讨论、作业、测验任务行提供「作答/查看」按钮：
   - 讨论：查看帖标题与正文，手动输入回复后提交；
   - 作业/测验：查看试卷全部题目（单选/多选/判断含选项、简答/填空含分值），手动输入或点选答案后提交；提交成功后自动刷新任务列表；
   - 与 AI 自动刷课互不影响，手动作答不需要 AI 配置。
8. **AI 设置**（顶栏入口）：
   - 讨论、作业类任务需要调用 AI，**未配置 AI 时相关模式无法启动**（前后端双重拦截）；
   - 可选择预设 provider（deepseek/openrouter/modelscope/mimo）或完全自定义：名称、Base URL、API Key、模型均可手填；
   - 配置**只保存在当前浏览器**（`localStorage`，键 `mooc_ai_config::<用户名>`），提供「清除配置」按钮；
   - 保存前可点击「连通性测试」实测接口可用性（后端仅做一次探测，不留存）；
   - 代码与 `requirements.txt` 中不内置任何默认 Key；后端接收 AI 配置仅在发起刷课任务时，存于任务对象内存，不写入磁盘。

### 管理页面（仅本机）
- 添加 / 删除用户（删除同时清理其 cookie 文件）；
- 一目了然的统计：用户总数、已绑定 cookie、cookie 有效数、失效数、刷课中人数；
- 对每位用户可**检测 cookie 是否失效**、**查看各课程学习时长与完成进度**。

## 关键 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/user/login | 校验用户名单与凭证,返回 ok 或二维码 |
| GET  | /api/user/login/status?flow_id= | 轮询扫码状态 |
| POST | /api/user/login/cancel?flow_id= | 取消扫码流程(关闭弹窗时调用) |
| GET  | /api/user/courses?username= | 课程列表 |
| GET  | /api/user/course/detail?… | 课程任务+进度 |
| GET  | /api/user/progress?… | 仅进度(周期刷新) |
| POST | /api/user/brush/start | 启动刷课(需要 AI 的类型须在 body 带 `ai` 配置) |
| POST | /api/user/brush/stop | 停止刷课 |
| GET  | /api/user/brush/status?username= | 刷课状态(日志/进度) |
| GET  | /api/user/img?url= | 课程封面图代理(仅网易 nosdn CDN,防 SSRF) |
| GET  | /api/user/task/discuss?… | 查看讨论帖标题与正文 |
| POST | /api/user/task/discuss/submit | 手动回复讨论 |
| GET  | /api/user/task/paper?… | 查看作业/测验试卷题目 |
| POST | /api/user/task/paper/submit | 手动作答提交作业/测验 |
| POST | /api/user/ai/test | AI 连通性测试(配置随请求传入,不保存) |
| GET  | /api/admin/users | 用户列表(仅本机) |
| POST | /api/admin/users | 添加用户 |
| DELETE | /api/admin/users/{u} | 删除用户 |
| POST | /api/admin/users/{u}/check | 强制检测 cookie |
| GET  | /api/admin/users/{u}/courses | 各课程学习进度 |

### `POST /api/user/brush/start` 请求体

```json
{
  "username": "testuser",
  "course": {"course_id": "1469198164", "term_id": "1476714442", "short_name": "nudt", "name": "信息隐藏技术"},
  "mode": "types",              // types=按类型刷未完成 | duration=刷时长
  "types": [1, 3, 6, 11],       // mode=types 时生效;留空=全部类型
  "speed": 300,                 // 每次提交的秒数
  "duration_mode": "target",    // mode=duration 时生效:target=刷到课程总时长 | session=本次刷够时长
  "duration_sec": 10800,        // 目标/新增时长(秒)
  "ai": {"name": "..", "base_url": "..", "api_key": "..", "model": ".."}   // 需要 AI 时携带
}
```

## 测试验证

仓库内 `data/cookies/cookie_testuser.json` 为随代码提供的测试凭证（用户 `testuser`）。
可通过以下方式自测：

```bash
curl -X POST http://127.0.0.1:8000/api/user/login -H 'Content-Type: application/json' -d '{"username":"testuser"}'
# 返回 {"status":"ok"...} 即凭证有效
curl "http://127.0.0.1:8000/api/user/courses?username=testuser"
```

## 刷时长机制

刷时长（`mode="duration"`）只处理**视频**任务，且对已完成的视频也从 0 重刷（`finished` 随进度推进自然置真）。

| 停止条件 | `duration_mode` | 判定依据 |
|---|---|---|
| 刷到课程总时长 | `target` | 接口返回的课程 `learnedTimeCount` ≥ `duration_sec` |
| 本次刷够时长 | `session` | 本次 `learnedTimeCount` 增量 ≥ `duration_sec` |

安全设计：

- **真实进度校准**：每刷完一个视频都调 `getTermLearn.rpc` 取真实累计时长判定，而非只依赖本地估算，避免刷过头；接口临时取不到时才回退本地估算；
- **停滞检测**：若平台连续 2 轮完全未计入重刷时长（部分课程不重复计时），自动停止并提示"该课程可能不支持重复计时"，避免无效上传；
- **轮数上限**：最多 10 轮循环，防止无限空转；
- **已达目标即结束**：启动时若累计时长已达标，直接结束，不做任何上传；
- 长视频优先排序，减少请求次数；随时可点"停止"中断。

## 二维码过期与超时处理

| 情形 | 判定 | 前端表现 |
|---|---|---|
| 二维码被平台判定过期 | `poll.do` 返回 `codeStatus=3` | 停止轮询，红字提示「二维码已过期，请重新获取」，并显示「重新获取二维码 / 取消」按钮 |
| 用户长时间不扫码 | 轮询达上限（150 次 × 2s ≈ 5 分钟）后返回失败 | 同上，按过期处理 |
| 用户手动关闭弹窗 | 点击「×」/遮罩/「取消」 | 关闭弹窗并调用 cancel 接口终止后台轮询线程 |

## 备注

- 管理页仅限本机：由 `main.py` 中 `admin_local_only` 中间件基于 `request.client.host` 强制，代理部署时需注意客户端 IP 透传；
- 讨论/作业任务的 AI 作答依赖用户在浏览器「AI 设置」中自行填写的 OpenAI 兼容接口（name/base_url/api_key/model）；**服务端与代码均不保存任何 Key**，未配置时含讨论/作业的刷课模式被拒绝启动，视频/PPT 刷课不受影响；
- 由于 Key 存于浏览器 `localStorage`，更换浏览器或清除浏览器数据后需重新填写。