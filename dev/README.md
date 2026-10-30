# Dev 开发预览

日常开发与测试统一使用 Git 分支 **`Dev`**；`main` 保留上游基线，稳定后再从 Dev 提交可审核的合并请求。修改提交、数据库迁移与测试记录均跟随 Dev 保存。

本机页面：<http://127.0.0.1:18894/#/conversations>。后台入口：<http://127.0.0.1:18894/#/home>。

2026-10-30 本机 Docker VM 存储 I/O 故障期间，原库和卷保留，临时恢复副本使用 <http://127.0.0.1:18895/#/conversations>（20:39备份，API18901）。副本不是原库最新状态，不能覆盖回原库。实际验证及恢复边界见 [本轮整合记录](../docs/ai-customer-service/INTEGRATION_20261030.md)。

## 启停

在仓库根目录执行：

```sh
python3 dev/manage.py up --build
python3 dev/manage.py status
python3 dev/manage.py stop
```

前端为 Vue3 Vite 开发服务，修改源码自动更新；Java 后端改动后用 `up --build` 重建并重启 Dev API。启动前脚本将构建产物原子复制到 `dev/.runtime/manager-api.jar`，容器挂载该副本，使正在运行的容器不受下次构建覆盖影响。需要 Docker、JDK 21、Maven 和 Node/npm；缺少前端依赖时构建命令会按现有锁文件安装。

首次开发环境已经通过只读快照复制本机智控台数据，登录账号/密码沿用原智控台。Dev 的登录 Token 单独签发。后续测试写入只落在独立的 Dev 数据库，不同步回原服务。

若在另一台已有本机智控台的电脑首次创建 Dev 环境，可执行一次：

```sh
python3 dev/manage.py up --build --seed-local-console
```

该选项仅允许导入空 Dev 数据库，拒绝覆盖已有表。来源固定为 `xiaozhi-console-mysql-1`；没有这个来源时不使用此选项，按原项目流程初始化用户即可。

## 环境隔离

| 项目 | Dev |
| --- | --- |
| Compose 项目 | `xiaozhi-server-dev` |
| 前端 | `127.0.0.1:18894` |
| Java API | `127.0.0.1:18893`（前端 `/xiaozhi` 代理） |
| MySQL / Redis | 独立容器、独立持久卷、不映射宿主端口 |
| 配置、数据库快照、日志 | `dev/.runtime/`，忽略提交，私有文件权限 600 |
| 功能开关 | Dev 启用统一对话及 B1 人工文字 API；默认发布配置仍关闭 |

`stop` 保留 Dev 数据。不要运行删除数据卷的命令。开发环境不接管现有 ESP32，不改变设备 OTA、WebSocket、固件及原智控台。

## 当前页面能力

新建/选择/分页读取自己的会话，发送文字，刷新读取持久历史，展示原有位置消息。不同会话分别保留未发送草稿；网络失败时保留内容，重试沿用同一个幂等编号。中文输入法确认候选词不会触发发送。

统一对话页已接入转人工、排队取消、客服回复和结束后恢复 AI。B2 提供实际 AI 文字执行、流式回复、停止、失败重试与持久用量。B3/B4 提供私有附件及截图标注；B5 提供真人音视频与换房转接；P3 提供手动分段实时AI和摄像头采样；客服工作台增加内部证据摘要草稿。各模块按对应开关及私有配置启用，发布配置默认关闭。操作方式见 [B5](../docs/ai-customer-service/B5_DEV_OPERATIONS.md)、[实时AI](../docs/ai-customer-service/REALTIME_DEV_OPERATIONS.md) 和 [剩余验收](../docs/ai-customer-service/REMAINING_WORK.md)，源码存在不等于现场或外部业务已验收。

后端 B1 人工文字服务已提供独立 API，详见 [接口契约](../docs/ai-customer-service/SUPPORT_B1_API.md)；Vue 3 工作台位于 `/#/support`，启用的客服及管理员会在智控台看到入口。管理员须先给已有账号配置客服/专家档案，客服随后刷新在线租约才能接单。现有管理员身份不会自动赋予任意会话的读取权。

## Vue 3 迁移判断

默认开发前端已采用 Vue 3 + Vite + Vue Router 4 + Element Plus。原 `main/manager-web` 的 61 个 Vue 2 组件保留作为回退基线，后续功能在 `main/manager-web-next` 迭代。

Vue 3 独立迁移已落在 `main/manager-web-next`，运行方式见下节。原目录保留用于并行对照和回退，新前端的实现、验证范围与后续边界见该目录的 `README.md`。新功能继续在 Vue 3 目录迭代。

官方依据：

- [Vue 2 维护结束说明](https://v2.vuejs.org/eol/)
- [Vue 3 迁移构建与组件库限制](https://v3-migration.vuejs.org/migration-build.html)

## Vue 3 默认前端与兼容命令

默认页面运行在 <http://127.0.0.1:18894/#/conversations>，使用 Dev API（18893）。先按上述步骤启动 Dev，再在候选目录用 `npm ci` 安装自己的依赖，然后执行：

```sh
python3 dev/manage.py candidate-up
python3 dev/manage.py candidate-status
python3 dev/manage.py candidate-stop
```

默认 Vue3 进程和日志分别记录于 `dev/.runtime/candidate-process.json` 与 `candidate.log`。停止候选页面不会停止已有前端、API 或数据库。18894 被其他进程占用时启动会报错，不会终止该进程。

也可以构建独立的静态候选镜像，并连接已启动的 Dev 网络：

```sh
docker build -f Dockerfile-web-next -t xiaozhi-vue3-preview .
docker run --rm --name xiaozhi-vue3-preview --network xiaozhi-server-dev_default \
  -p 127.0.0.1:18895:8080 -e API_PROXY_TARGET=http://api:8002 xiaozhi-vue3-preview
```

访问 <http://127.0.0.1:18895/#/conversations>。镜像通过 `/xiaozhi/` 代理到 `API_PROXY_TARGET`；默认目标是 Dev 网络中的 `http://api:8002`。退出容器不影响 Dev 数据及其他服务。

`up` 默认启动 Vue3；`candidate-up/status/stop` 保留为 Vue3 单独管理的兼容命令。需要回退时可执行 `legacy-up`、`legacy-status`、`legacy-stop`，旧前端使用 18892。`stop` 停止两套受管理前端及 Dev 容器，不删除数据卷。端口被其他进程占用时不会终止它。

### Dev AI 文字回复

AI 默认关闭。先执行 `python3 dev/manage.py up --build` 确保已有 Dev 使用当前 Java 包，再使用 `python3 dev/manage.py ai-enable` 从 Dev 数据库只读获取唯一启用的默认 LLM 配置；该配置必须使用百炼 HTTPS 地址。命令将私有配置写入 `dev/.runtime`，重新创建 Dev API 容器，启动独立 AI worker 和 Vue 3。已有 worker 服务密钥会被复用；业务空间 URL 保留原样。配置、日志及运行时文件可能含敏感信息，勿提交或分享。后台修改默认 LLM 后需再次执行 `ai-enable` 同步 worker 配置。

使用 `python3 dev/manage.py ai-status` 查看开关、worker 进程与模型；使用 `python3 dev/manage.py ai-disable` 停止 worker 并关闭 API 的 AI 功能。关闭不会删除历史数据或任务。普通 `up` 会在 AI 已启用时启动 worker，普通 `stop` 会停止它。

worker 默认使用仓库父目录的 `.venv/bin/python`，该环境须已安装 `openai` 和 `httpx`。可通过 `XIAOZHI_AI_WORKER_PYTHON` 指定其他已准备好的 Python；脚本不会自动安装依赖。若 worker 无法启动，检查 `dev/.runtime/ai-worker.log`；API 和 worker 修改配置后必须使用上述命令重新启动。
