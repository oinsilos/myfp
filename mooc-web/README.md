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
│   ├── cookies/          #   cookie_{username}.json 按用户隔离存储
│   └── ai/               #   ai_{username}.json 按用户隔离的 AI 配置
├── requirements.txt
└── README.md
```

## 调用关系

```
浏览器(user.html) ──> /api/user/* ──> services.login / services.brush ──> core.* ──> icourse163
浏览器(admin.html) ─> /api/admin/* ─> services.store(本地文件)
```

- **登录链路**：`POST /api/user/login` → 无有效 cookie 时 `LoginFlowManager.start()` 后台线程拉取二维码并轮询 `poll.do`；成功后写 `data/cookies/cookie_{username}.json` 并登记用户。
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
1. 输入用户名 → 后端检查 `cookie_{username}.json`：
   - 存在且未过期 → 直接返回课程列表（含图片）；
   - 不存在或过期 → 生成并返回扫码二维码（base64 PNG），前端弹窗展示；
2. 扫码成功后弹窗自动关闭，自动拉取课程列表；
3. 点击课程 → 展示学习时长（每 5 秒自动刷新）与任务列表（✓已完成 / ✗未完成）；
4. 刷课控制台：
   - **全部未完成**：刷所有未完成的任务（视频/PPT/讨论/作业）；
   - **按类型刷**：仅刷勾选类型中未完成的任务；
   - **刷时长**：仅视频类型，且已完成视频也从头重刷以累计学习时长；
   - 速度可自定义（默认 600 秒/次提交）；
   - 启动/停止共用同一按钮；
5. 每个任务完成后服务端 `refresh_seq` 变化，前端自动刷新任务列表显示最新进度。
6. **AI 设置**（顶栏入口）：
   - 讨论、作业类任务需要调用 AI，**未配置 AI 时相关模式无法启动**（前后端双重拦截）；
   - 可选择预设 provider（deepseek/openrouter/modelscope/mimo）或完全自定义：名称、Base URL、API Key、模型均可手填；
   - 保存前可点击「连通性测试」实测接口可用性；
   - 代码中不内置任何默认 API Key；配置按用户隔离存放于 `data/ai/ai_{username}.json`，API Key 在接口返回时打码，不落前端。

### 管理页面（仅本机）
- 添加 / 删除用户（删除同时清理其 cookie 文件）；
- 一目了然的统计：用户总数、已绑定 cookie、cookie 有效数、失效数、刷课中人数；
- 对每位用户可**检测 cookie 是否失效**、**查看各课程学习时长与完成进度**。

## 关键 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/user/login | 校验/发起扫码登录,返回二维码或 ok |
| GET  | /api/user/login/status?flow_id= | 轮询扫码状态 |
| GET  | /api/user/courses?username= | 课程列表 |
| GET  | /api/user/course/detail?… | 课程任务+进度 |
| GET  | /api/user/progress?… | 仅进度(周期刷新) |
| POST | /api/user/brush/start | 启动刷课 |
| POST | /api/user/brush/stop | 停止刷课 |
| GET  | /api/user/brush/status?username= | 刷课状态(日志/进度) |
| GET  | /api/user/ai/config?username= | 用户 AI 配置(打码)+预设模板 |
| POST | /api/user/ai/config | 保存 AI 配置 |
| DELETE | /api/user/ai/config?username= | 删除 AI 配置 |
| POST | /api/user/ai/test | AI 连通性测试 |
| GET  | /api/admin/users | 用户列表(仅本机) |
| POST | /api/admin/users | 添加用户 |
| DELETE | /api/admin/users/{u} | 删除用户 |
| POST | /api/admin/users/{u}/check | 强制检测 cookie |
| GET  | /api/admin/users/{u}/courses | 各课程学习进度 |

## 测试验证

仓库内 `data/cookies/cookie_testuser.json` 为随代码提供的测试凭证（用户 `testuser`）。
可通过以下方式自测：

```bash
curl -X POST http://127.0.0.1:8000/api/user/login -H 'Content-Type: application/json' -d '{"username":"testuser"}'
# 返回 {"status":"ok"...} 即凭证有效
curl "http://127.0.0.1:8000/api/user/courses?username=testuser"
```

## 备注

- 管理页仅限本机：由 `main.py` 中 `admin_local_only` 中间件基于 `request.client.host` 强制，代理部署时需注意客户端 IP 透传；
- 讨论/作业任务的 AI 作答依赖用户在「AI 设置」中自行配置的 OpenAI 兼容接口（name/base_url/api_key/model），代码不内置任何默认 Key；未配置时含讨论/作业的刷课模式被拒绝启动，视频/PPT 刷课不受影响。