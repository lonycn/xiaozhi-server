# Dev 开发预览

日常开发与测试统一使用 Git 分支 **`Dev`**；`main` 保留上游基线，稳定后再从 Dev 提交可审核的合并请求。修改提交、数据库迁移与测试记录均跟随 Dev 保存。

本机页面：<http://127.0.0.1:18894/#/conversations>。后台入口：<http://127.0.0.1:18894/#/home>。

2026-10-30 本机 Docker VM 存储 I/O 故障已在用户授权后恢复：colima与smartmaster两个profile正常restart，原Dev18894/API18893重新连接原MySQL/Redis，原18333 S3及既有对象恢复；迁移从115正常增加至116。22:47曾生成并核验APFS克隆/恢复快照，当前路径无法找到，仍在排查；原OS/data磁盘及所有原卷目前仍在，20:39 SQL及新建post-recovery一致性SQL备份已验证现存，字节与SHA见恢复记录。未执行清库/删卷，不据此推定无法定位的快照仍可用。故障期间的18895/API18901是20:39备份的隔离恢复副本，其进程及其他本轮备用服务现已正常SIGTERM停止，数据/DB/volumes及manifest/私有配置保留可恢复，不覆盖原库。原Dev18894/18893及AI/Media/realtime worker继续running。恢复后的Omni Dev S3录音/有声视频已通过，浏览器阶段及未完成边界见 [恢复与补充验收](../docs/ai-customer-service/DEV_RECOVERY_VALIDATION_20261030.md)。

## 当前已统一真实设备（2026-11-05）

本机现在只运行一套 Dev Manager API（18893）和 Vue3 页面（18894）。两块实际 ESP32 的设备记录与联调历史已增量合入原 Dev 数据库。旧 19193 API、旧控制台和并行候选网页已停用，原库及 SQL 备份保留。当前后台构建与最新源码的全部 class/resource 一致。

私有 `dev/.runtime/esp32-device-settings.json` 明确开启设备接入；`up` / `stop` 同时管理单一 `esp32_services.py` 网关与 OTA 代理。固件仍使用 LAN 19180/19183，内部统一转到 18893。不可再启动旧 first-board canary；其管理脚本已增加退役标记检查。迁移、备份和验证证据位于工作区 `work/esp32-unified-latest-20261105/`。

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

`stop` 保留 Dev 数据。不要运行删除数据卷的命令。默认新建的开发环境不接管设备。本机已按 2026-11-05 的明确要求统一接入现有 ESP32；固件入口不变，内部后端统一为 Dev。

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

### Agent 工具调用（显式启用）

工具调用默认关闭，必须先启用并配置 AI worker。使用最新 JAR 和更新后的 worker 环境：

```bash
python3 dev/manage.py agent-tools-enable --build
python3 dev/manage.py agent-tools-status
python3 dev/manage.py agent-tools-disable
```

普通 `agent-tools-enable` 不开放本地技术工具。仅在明确需要本地技术验收时使用 `agent-tools-enable --technical-local`；技术工具只报告固定协议能力，不提供客户业务数据。命令会检查 JAR、worker 依赖及私有配置，并重建本 Dev API、重启 AI worker。关闭时保留会话、工具审计、数据库和私有模型配置；关闭 AI 时工具随之不可用，已有工具开关偏好保留。`agent-tools-status` 报告配置及 AI 前提下的启用状态，不代表业务数据源、外部 App 或真实业务流程已联通。

### AI-first 能力（显式启用）

```bash
python3 dev/manage.py ai-first-enable --build
python3 dev/manage.py ai-first-status
python3 dev/manage.py ai-first-disable
```

默认关闭；先启用 AI 与 agent-tools，并准备包含 AI context、公开工具结果及 realtime tools 的最新 API JAR 和对应 worker 环境。仅在需要固定只读技术工具验收时显式添加 `--technical-local`；不添加时保留 agent-tools 原有技术工具偏好。命令按当前已配置视觉/原声模型构建受限白名单，未知模型会拒绝启用，不自动更换模型；若两者均未配置，多模态工具维持关闭。已有 realtime 开启时才在本次启用实时工具；之后再启用 realtime 需重新执行本命令。不会开启摄像头、麦克风或 realtime 本身。

命令会重建本 Dev API 并重启既有 AI/realtime worker；关闭仅移除 AI-first 开关，保留基础能力、模型与私有 worker 配置、会话和审计数据。实际 provider、媒体、外部业务与设备仍需各自验证，`status` 是配置状态而非健康探测。

实时上传材料处理默认关闭，仅在实时功能已启用、对应 API 与 worker 已就绪并需要该能力时，显式使用 `python3 dev/manage.py ai-first-enable --materials`。启用前会检查新版材料接口及迁移，并验证既有私有 AI 视觉配置和 Bailian HTTPS 地址；只将视觉模型及其服务地址、密钥和 workspace 写入私有 realtime worker 配置，其他 realtime 配置保留。视觉地址、密钥或 workspace 为空时使用现有 AI 对应配置。再次不带 `--materials` 执行或执行 `ai-first-disable` 会关闭该功能；旧三字段设置默认为关闭。`ai-first-status` 显示配置状态，不证明模型或真实材料链路可用。命令不会自动打开麦克风或摄像头。

普通 AI 回答朗读和固定只读技能也分别默认关闭，只在需要时对本次命令显式增加 `--speech` 和/或 `--skills`；下次不带相应选项执行会关闭该能力。朗读需要先准备独立 Python 环境：`python3 -m venv dev/.venv-answer-speech`，再使用该环境的 pip 安装 `main/xiaozhi-server/answer_speech_worker/requirements.txt`。命令先检查对应 JAR 类和迁移；朗读还只读查询唯一启用且默认的 `TTS_BailianCosyVoice` 配置，校验受信 Bailian WSS 及允许的模型/音色组合。代码默认组合为 `cosyvoice-v3.5-flash` / `longantai_v3`；保留已配置的 `cosyvoice-v2` / `longxiaochun_v2`，管理端派发参数随私有 worker 配置同步，避免迁移中发生不匹配。切换前必须先确认目标组合在当前业务空间能完整生成音频；2026-11-05 新组合实测返回引擎错误 `418`，当前运行状态请以数据库默认、角色绑定及各 worker 的实际私有配置为准。它不更换原 TTS 或 AI 配置，也不从视觉配置补充密钥。预检通过后才创建独立私有 worker 密钥和配置并启动 worker；日志不记录密钥。朗读使用会话中已保存的完成消息，不要求当前有运行中的普通 AI run；技能依赖已启用的 AI、agent-tools 与 AI context，只影响之后新建的普通 AI run。`ai-first-status` 的朗读/技能与 worker 状态不代表 provider 可用或音频真实验收完成。

关闭 AI-first 会停止本朗读 worker 并关闭相应标志，保留历史任务、音频、会话、数据库与卷；全栈 `stop` 停止 worker，随后 `up` 会按已保存的启用状态重启。不要通过删除或初始化数据来处理失败。

AI 运行控制默认关闭；需要时在 `ai-first-enable` 上显式附加 `--runtime-control`，例如 `python3 dev/manage.py ai-first-enable --runtime-control`。该选项只设置 `ai.runtime-control.enabled=true`，使用服务端默认的每用户并发 3、全局并发 32、每用户每小时 60 次和全局每小时 1000 次限制；启动前会检查 JAR 和迁移，旧版不自动启用。其他需要保留的可选能力也必须在同一命令中继续显式指定，例如 `--speech --skills --materials`。省略 `--runtime-control` 或执行 `ai-first-disable` 会关闭此开关，保留既有数据。`ai-first-status` 仅读取并显示配置，不证明 API 已部署、provider 健康或额度服务可用。

启用前会只读检查当前 JAR 是否包含待执行的 `esp32-gateway` 触发器迁移，并读取本专属 Dev MySQL 的迁移记录、binlog 和 `log_bin_trust_function_creators`。数据库或迁移记录不可读取时先失败；对 JAR 中被 master 引用的网关变更，分别检查初版 `202611020700-esp32-gateway` 及存在时的 `202611021102-esp32-gateway-strict-revocation`。所包含的触发器迁移均已执行时不受该检查限制；旧 JAR 不包含严格撤权变更时不要求该记录。若迁移尚未执行且 binlog 开启、trust 关闭，命令会在停止 worker、改 AI-first 设置和重建 API 之前报错。请由数据库管理员对**本专属 Dev**安排一次经审查的临时迁移操作，完成后恢复原有全局设置；命令本身不会授权 SUPER 或更改数据库全局变量。保留现有数据库、卷和数据，不要通过清空或初始化来处理。

### 管理端模型测试

在“模型配置 → 修改 → 测试模型”中，使用当前编辑参数启动短样本测试，无须保存，也不更换默认模型。测试可能产生少量供应商用量。只有管理员可以使用；最近十条测试记录按当前管理员和模型隔离，日志保存在 `ai_model_test_run`，不保存请求凭据或测试输入文件。测试音频存于私有 `model-test.audio-dir`，通过管理员鉴权接口读取。

| 类型 | 当前测试方法 |
| --- | --- |
| LLM | OpenAI 兼容 / SiliconFlow / Ollama 短文本推理 |
| VLLM | OpenAI 兼容 / SiliconFlow 图片问答；默认红色圆形，也可上传 PNG/JPEG |
| ASR | 百炼 `qwen_workspace` 实际音频识别；默认普通话样音，也可上传 WAV/MP3 |
| TTS | 百炼 CosyVoice WebSocket / OpenAI 兼容 / SiliconFlow 实际合成，成功后可加载 WAV 试听 |
| Intent | `intent_llm` 调用依赖 LLM；`function_call` 验证虚拟工具调用结构，不执行工具；`nointent` 仅检查配置 |
| Memory | `mem_local_short` 使用测试对话验证总结 LLM；Mem0 v3 对隔离测试用户做只读检索；`nomem` / `mem_report_only` 仅检查配置 |
| RAG | RAGFlow 知识库列表连接检查；填写知识库 ID 后执行只读检索 |
| VAD | Silero 阈值及静音参数检查；不代表网关 ONNX 模型推理或实物收音验收 |

其他供应器明确返回“尚未适配”，不会用普通 HTTP 可达性代替推理成功。测试任务最多两个并发、八个排队；请求有响应大小和超时限制，不自动重试供应商调用。相同请求编号仅创建一次测试。关闭窗口停止前端轮询，不会重发请求；已发起的有界任务继续写入结果，可稍后查看历史。

服务端从私有数据库读取原始凭据，浏览器保留掩码；修改供应器或服务地址后，原有掩码凭据不能被自动发送到新地址，须明确填入对应凭据。结果区提供实际检查范围、耗时、供应商错误、请求编号、用量与逐步脱敏日志；TTS 测试使用 PCM/WAV 输出并沿用当前采样率、音量、语速和音调。
