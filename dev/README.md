# Dev 开发预览

日常开发与测试统一使用 Git 分支 **`Dev`**；`main` 保留上游基线，稳定后再从 Dev 提交可审核的合并请求。修改提交、数据库迁移与测试记录均跟随 Dev 保存。

本机页面：<http://127.0.0.1:18894/#/conversations>。后台入口：<http://127.0.0.1:18894/#/home>。

2026-10-30 本机 Docker VM 存储 I/O 故障已在用户授权后恢复：colima与smartmaster两个profile正常restart，原Dev18894/API18893重新连接原MySQL/Redis，原18333 S3及既有对象恢复；迁移从115正常增加至116。22:47曾生成并核验APFS克隆/恢复快照，当前路径无法找到，仍在排查；原OS/data磁盘及所有原卷目前仍在，20:39 SQL及新建post-recovery一致性SQL备份已验证现存，字节与SHA见恢复记录。未执行清库/删卷，不据此推定无法定位的快照仍可用。故障期间的18895/API18901是20:39备份的隔离恢复副本，其进程及其他本轮备用服务现已正常SIGTERM停止，数据/DB/volumes及manifest/私有配置保留可恢复，不覆盖原库。原Dev18894/18893及AI/Media/realtime worker继续running。恢复后的Omni Dev S3录音/有声视频已通过，浏览器阶段及未完成边界见 [恢复与补充验收](../docs/ai-customer-service/DEV_RECOVERY_VALIDATION_20261030.md)。

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

统一对话页已接入转人工、排队取消、客服回复和结束后恢复 AI。B2 提供实际 AI 文字执行、流式回复、停止、失败重试与持久用量。B3/B4 提供私有附件及截图标注；B5 提供真人音视频与换房转接；P3 提供手动分段、服务端自动语音检测和有界摄像头采样；客服工作台增加内部证据摘要草稿。各模块按对应开关及私有配置启用，发布配置默认关闭。操作方式见 [B5](../docs/ai-customer-service/B5_DEV_OPERATIONS.md)、[实时AI](../docs/ai-customer-service/REALTIME_DEV_OPERATIONS.md) 和 [剩余验收](../docs/ai-customer-service/REMAINING_WORK.md)，源码存在不等于现场或外部业务已验收。

本 Dev 可执行 `python3 dev/manage.py realtime-enable --server-vad` 开启自动听音能力，使用 `--no-server-vad` 关闭该模式；省略参数保留原设置。命令重启本 Dev API 和 realtime worker，保留会话历史。通过 `realtime-status` 查看状态。页面选择自动语音检测、同意麦克风后连接，再点击开始听音；连接本身不打开麦克风。暂停丢弃尚未提交的发言，自动听音期间文字注入排队，暂停确认后派发。外部 App 仍需独立可信来源密钥及 `features.external-realtime-media-enabled`，原 Dev 未配置或启用该来源入口。详见 [授权和自动听音运维](../docs/ai-customer-service/EXTERNAL_MEDIA_VAD_OPERATIONS.md)。

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


## 内部模型辅助交接

在已启用并配置百炼 AI 的本 Dev 中，显式开启独立能力：

```sh
python3 dev/manage.py summary-enable --build
python3 dev/manage.py summary-status
python3 dev/manage.py summary-disable
```

默认 `features.support-summary-assistance-enabled=false`。开启前检查新 JAR、私有 AI 配置及 Python 依赖；只启动独立 `ai_worker.summary`，复用已配置模型，不改变普通聊天协议。客服在当前接待中点击“AI辅助整理”，结果和建议只供内部查看；明确加入回复草稿后仍需人工发送。禁止将模型结果当作已核验事实。新消息、版本变化、转接及撤权会使旧任务过期；失败与用量不删除。

`up`/`stop`会管理已启用的独立摘要 worker；`ai-disable`也停止摘要任务执行，保留开启偏好与历史，重新启用 AI 时按偏好恢复。`summary-disable`仅关闭能力并停止 worker，不清理任务/摘要/客户数据。进程身份不符时拒绝终止。当前本机 Dev 已于2026-10-31显式启用，原库增量迁移至117；详见[运维说明](../docs/ai-customer-service/HANDOFF_ASSISTANCE_OPERATIONS.md)和[真实技术验收](../docs/ai-customer-service/HANDOFF_ASSISTANCE_VALIDATION.md)。

B6 只读业务 adapter 仍默认关闭。接通前必须由真实来源实现[签名委托接口](../docs/ai-customer-service/B6_READONLY_HTTP_PROTOCOL.md)，提供独立密钥、TLS 与真实用户/租户/资源归属复核；不能用已有 Workbench 凭据替代。
