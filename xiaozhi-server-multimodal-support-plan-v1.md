# 小智 Server 多模态 AI 与人工客服一体化平台开发规划

> 版本：V1.0（Codex 实施版）  
> 日期：2026-10-30  
> 目标项目：`xinnan-tech/xiaozhi-esp32-server`  
> 实施方式：在小智 Server 现有仓库内进行兼容性增量开发，不另起一套同功能平台  
> 目标终端：ESP32、安卓 APP、网页、后续 Linux 工业终端及 MR 眼镜  
> 业务扩展：大冷总管冷库维保、IoT 数据、WeKnora 知识库、人工制冷专家

## 0. 执行摘要与最终技术决策

项目要建设的不是单一语音机器人，而是**持久化、多模态、可转人工的统一对话平台**。同一条对话可连续容纳文字、照片、视频、录音、位置、实时音视频、IoT 结果、人工回复与标注图片，允许在 AI 与人工之间转接，不丢失上下文。

**最终技术原则：**

1. **保留原小智设备协议。** ESP32 的 `/xiaozhi/v1/`、OTA、Opus 音频、原有 ASR/LLM/TTS、IoT/MCP 继续可用；新增 APP 功能不得破坏既有设备。
2. **以原仓库为实施主体。** `manager-api`（Spring Boot）负责用户、鉴权、持久对话、附件元数据、客服队列、权限、用量和 REST/WebSocket API；`xiaozhi-server`（Python）负责原 ESP32 会话、模型接入、Omni 实时能力和媒体分析；`manager-web` 增加网页客服工作台。
3. **不在第一版再新建独立 FastAPI 身份/会话服务。** 现有仓库已经有管理 API 和数据库迁移，重复引入 FastAPI 会造成用户、对话和权限双中心。Python 服务内需要对外能力时，优先扩展现有 `aiohttp` 或以同仓库独立 worker 承担长任务。
4. **对话永久 ID 与媒体会话 ID 分离。** `conversation_id` 代表跨天、跨模型的持久聊天；`realtime_session_id` 与 `livekit_room_id` 只代表一段临时实时连接。
5. **人工是服务责任主体。** AI 提供接待、检索、摘要和客服辅助；用户请求转人工后暂停 AI 自动回复；制冷疑难问题由人工专家判断。
6. **音视频通道分工。** AI 实时通话使用千问 Omni Realtime；人工实时音视频使用自建 LiveKit；普通文字/附件统一使用本项目消息 API。
7. **首期只读业务工具。** 知识库、设备、温度、报警、工单查询先上线；创建正式工单、派单、控制设备等写动作均需后端鉴权和明确的用户确认。

**交付节奏：** 规划 12～14 周，按现有 2 名后端 + 1 名前端/安卓开发的团队规模估算。第 4 周可验收多模态图文，第 6 周可验收 AI 实时音视频，第 9 周可验收人工文字客服与标注，第 12 周可验收人工视频，第 14 周完成现场试点和安全加固。若新增专职安卓开发，可并行提速。

## 1. 原仓库现状与改造边界

本次代码核对基线：`xinnan-tech/xiaozhi-esp32-server`，Git 提交 `1a095d5bed23`。实际开发前 Codex 必须重新核对用户当前工作分支，不能假设所有路径保持不变。

| 原仓库模块 | 已有能力或现状 | 本次实施定位 |
| --- | --- | --- |
| `main/xiaozhi-server/app.py` | 启动 WebSocket 和 `aiohttp` HTTP 服务 | 保留启动机制，新增模型适配与内部服务 |
| `main/xiaozhi-server/core/websocket_server.py`、`core/connection.py` | ESP32 会话及音频收发 | 不破坏原协议；抽象旧会话到统一 Conversation |
| `main/xiaozhi-server/core/http_server.py` | `aiohttp` 路由 | 在兼容前提下扩展内部 AI/媒体接口 |
| `main/xiaozhi-server/core/api/vision_handler.py` | 原单图视觉接口 `/mcp/vision/explain` | 保留旧接口，新增多图/视频通用分析路径 |
| `main/xiaozhi-server/core/providers/asr`、`tts`、`llm`、`vllm` | 已有 ASR、TTS、LLM、视觉适配器 | 作为旧链路和后备能力保留，新增 Qwen Omni/Flash 适配器 |
| `main/xiaozhi-server/core/providers/tools` | 已有 Server MCP、设备 MCP、IoT 和插件执行 | 复用执行机制，补强用户/租户/项目权限及审批 |
| `main/manager-api` | Spring Boot、MySQL、Redis、已有用户权限 | 作为统一对话、客服、API 的唯一业务控制面 |
| `main/manager-api/src/main/java/xiaozhi/modules/agent/controller/AgentChatHistoryController.java` | 原聊天历史上报、查询、下载 | 保留旧数据入口，建立新统一消息与旧历史的兼容映射 |
| `main/manager-api/src/main/java/xiaozhi/modules/agent/entity/AgentChatHistoryEntity.java` | `ai_agent_chat_history`：`session_id`、`chat_type`、`content` 等 | 不能直接承担混合消息和人工客服，新增通用消息表 |
| `main/manager-api/src/main/resources/db/changelog/` | 已有 SQL 数据库迁移体系 | 所有新表和索引走增量迁移，禁止直接改生产库 |
| `main/manager-web` | 管理后台；实际依赖 `Vue 2.6 + Element UI 2` | 在现有 Web 内新增客服工作台，不能误用 Vue 3-only 组件 |

**注意：** `manager-api/README.md` 提及 Vue 3 开发环境，但实际 `manager-web/package.json` 为 Vue 2.6.14；以当前源码为准。现有开源项目 README 也声明未完成网络安全测评，不宜直接视为已具备公网生产安全能力。开发必须专门执行第 12 节的安全工作。

### 1.1 保留与禁止改动的范围

- 保留 ESP32 固件正在使用的 WebSocket/MQTT+UDP 协议、原 OTA 地址与配置。
- 保留原 MCP 设备工具执行流程，不将新客服系统的权限绕过到设备控制指令。
- 保留 `ai_agent_chat_history` 和已有管理端历史记录功能；采用增量新表、适配层或映射同步，禁止破坏性重建。
- 不重构整个 `manager-web` 为 Vue 3，不迁移全量 Spring Boot 到 Python。
- 不依赖尚未实现的安卓 APP UI 才能完成后端单元/集成测试；提供契约、模拟客户端与 SDK 接入说明。

## 2. 产品范围与用户故事

### 2.1 首期支持的输入

| 类型 | 表现形式 | 首期规则 | 模型或处理方式 |
| --- | --- | --- | --- |
| 文字 | 普通消息、Markdown、报警码 | 多轮连续、可编辑草稿 | `qwen3.8-flash` |
| 图片 | 现场照片、铭牌、仪表、控制器 | 多图混合、原图留存引用 | `qwen3.8-flash` |
| 视频文件 | 手机拍摄的机组运行片段 | 首期建议 ≤100MB、≤3 分钟；异步分析 | `qwen3.8-flash`（画面） |
| 视频含声音 | 压缩机运行异响、振动视频 | 同时分析音频与视频 | `qwen3.8-omni-flash` |
| 录音文件 | 用户口述、设备声音 | 上传并分析 | `qwen3.8-omni-flash` 或现有 ASR |
| 地理坐标 | 手机定位、地图选点 | 标注坐标系、精度、来源与时间 | 地图/业务工具；模型负责解释 |
| 实时语音 | 按钮通话、打断、持续多轮 | APP、WebRTC | `qwen3.8-omni-flash-realtime` |
| 实时视频 | 摄像头画面 + 语音 | 用户授权、可以随时关闭摄像头 | Omni Realtime |
| 文档 | 设备说明书、PDF、报告 | 首期 PDF/文本解析；复杂格式后续扩充 | 文件解析 + WeKnora/Flash |
| 业务数据 | 设备 ID、工单 ID、IoT 指标 | 后端按权限查询 | MCP/业务 API |

单条消息允许：**文字 + 多张图片 + 一个短视频 + 一个位置 + 关联设备**。不允许为了调用模型而把这五类内容拆成五条无关会话。

### 2.2 首期支持的输出

`text`、`markdown`、`audio`、`image_reference`、`annotated_image`、`video_reference`、`location_card`、`equipment_card`、`work_order_card`、`tool_result`、`action_confirmation`、`processing_status`。

AI 文本回复和用户历史记录必须支持流式增量更新；输出卡片的业务数据必须由可信后端提供，不能直接相信模型编造的 JSON。

### 2.3 人工服务的用户故事

1. 用户点击“转人工”，也可通过明确文字或语音意图申请转人工。
2. 系统生成人工请求，显示等待状态，AI 停止自动解答。
3. 客服网页收到新请求，查看用户身份、项目、聊天全文、图片视频和 AI 交接摘要。
4. 客服可以文字回复、发送图片、查看位置、发起 APP 内语音或视频邀请。
5. 用户同意后，网页客服可以实时观看用户摄像头，使用麦克风语音指导。
6. 客服可以对已上传照片、视频暂停帧、实时画面截图进行箭头、圆圈、矩形和文字标注；标注后发送回 APP。
7. 一线客服可转派制冷专家，专家看到完整上下文；后续可升级为多人会诊。
8. 结束后形成可编辑的远程指导纪要，必要时关联或创建大冷总管维修工单。
9. 用户稍后继续发消息时，能够继续 AI 对话，历史不丢失。

## 3. 目标技术架构与职责边界

### 3.1 建议部署组成

```text
安卓 APP / ESP32 / 其他终端
          │
          ├─ 业务 HTTP / SSE / 消息 WebSocket ─→ manager-api（Spring Boot）
          │                                       ├─ 持久对话和消息
          │                                       ├─ 文件元数据和上传授权
          │                                       ├─ 人工客服分配、权限、SLA
          │                                       ├─ LiveKit 房间 Token
          │                                       └─ IoT / 大冷总管业务授权
          │
          ├─ 老 ESP32 WebSocket / MQTT+UDP ─────→ xiaozhi-server（Python）
          │                                       ├─ 既有 ASR/LLM/TTS
          │                                       ├─ 既有视觉/MCP/IoT
          │                                       └─ 新 Qwen 模型适配器
          │
          ├─ AI 实时媒体 WebRTC ────────────────→ 阿里云百炼 Omni Realtime
          │
          └─ 人工实时媒体 WebRTC ──────────────→ LiveKit SFU ←── 客服网页
                                                     │
                                                     └─ 截图标注（Konva.js）

公共基础：MySQL、Redis、对象存储、Python Media Worker、监控和日志
```

上述是逻辑边界，外网部署时必须使用独立的反向代理/API 网关和 HTTPS/WSS；不把 Python 内部服务或管理数据库直接暴露给 APP。

### 3.2 对话控制面与模型执行面

**`manager-api` 是唯一权威控制面：** 用户身份、客户端授权、聊天记录、当前人工负责人、客服队列、附件权限、审计及成本账本。

**`xiaozhi-server` 是模型/设备执行面：** 原 ESP32 音频链路、Qwen 模型调用、VLM 分析、设备 MCP 工具适配。内部接口使用服务间鉴权，不复制业务用户数据库。

**`manager-web` 是客服工作台：** 继续使用仓库现有 Vue 2/Element UI；Konva 可直接作为 JS 库，不强制引入 Vue 3 依赖。

**LiveKit 是独立音视频基础设施：** 仅负责真人之间的实时音频/视频轨道和房间，不保存业务聊天历史。APP 内人机实时通话继续走阿里云百炼。切换到人工时会有一次媒体连接切换，但 `conversation_id` 不变。

### 3.3 租户与用户身份

身份层必须支持 `tenant_id`、`user_id`、`client_type`、`device_id`、`agent_id`、`project_id`。如果部署只服务单公司，也保留可空租户字段及用户/项目权限约束，避免后续多客户数据泄漏。

安卓 APP 使用平台签发的访问令牌。若大冷总管已有 Laravel 登录，采用**可信服务端令牌交换/SSO**，不让 APP 直接提交 `user_id` 后就获得权限。ESP32 原有设备凭据继续有效，新增设备与用户账号绑定关系。

## 4. 千问模型与路由

| 模型 | 路由场景 | 优先级 | 说明 |
| --- | --- | --- | --- |
| `qwen3.8-flash` | 普通文字、图片、视频画面、结构化提取 | 必需 | APP 普通消息默认主模型 |
| `qwen3.8-omni-flash-realtime` | 实时语音、摄像头、语音/文字回复、函数或 MCP | 必需 | 通过百炼 Realtime API |
| `qwen3.8-omni-flash` | 非实时录音及有声视频联合分析 | 条件必需 | 首期要分析“异响视频”则必须开通 |
| `qwen3.8-max` | 困难图片及疑难问题二次会诊 | 可选 | 不进入首期默认链路 |
| `qwen3.5-ocr` | 批量手写工单/复杂文档 OCR | 后续可选 | 不影响首期交付 |

现有 ASR、LLM、CosyVoice2 等 TTS、Silero VAD 不删除。它们用于原 ESP32 链路、百炼故障时的降级或后续私有部署；**不应假设百炼 `qwen3.8-omni-flash-realtime` 可直接下载到 RTX 5090 本地运行。**

**路由规则：**

1. 存在有效实时会话且该轮在实时通话中：Omni Realtime。
2. 上传录音或视频带音轨且用户需要分析声音：Omni Flash。
3. 其他文字、图片、纯画面视频：Qwen3.8 Flash。
4. 坐标、温度、工单、人员距离等精确数据先由可信业务工具计算，模型只负责解释结果。
5. 任何模型失败只记录错误和重试状态；不悄悄换模型给出未经确认的设备安全判断。

云模型需要百炼 API Key + Workspace ID；优先北京地域，使用服务端密钥配置。APP、ESP32 固件、网页静态 JS 中不得包含百炼长效密钥。

## 5. 统一 Conversation 数据契约

### 5.1 永久对话与临时会话分离

- `conversation_id`：一段可长期继续的业务对话，跨终端、跨模型、跨人工。
- `message_id`：一条不可重复的消息；发送方为 `user`、`ai`、`support_agent`、`expert` 或 `system`。
- `client_message_id`：客户端生成幂等标识，避免重试产生重复消息。
- `realtime_session_id`：一次短时 Omni 实时通话。
- `support_request_id`：一次转人工请求。
- `livekit_room_id`：一次真人音视频房间。
- `attachment_id`：图片、视频、录音、标注图片或文件的对象存储引用。

### 5.2 消息请求示例

```json
{
  "conversation_id": "conv_01",
  "client_message_id": "uuid-01",
  "parts": [
    {"type": "text", "text": "这台压缩机运转正常吗？"},
    {"type": "image", "attachment_id": "att_image_01"},
    {"type": "video", "attachment_id": "att_video_01"},
    {
      "type": "location",
      "latitude": 31.000001,
      "longitude": 121.000001,
      "coordinate_system": "GCJ02",
      "accuracy_m": 15,
      "source": "user_shared"
    }
  ],
  "context": {
    "project_id": "project_01",
    "equipment_id": "equipment_01"
  }
}
```

`project_id` 和 `equipment_id` 只属于客户端上下文提示，服务端必须验证其所有权和访问范围。示例坐标仅用于演示格式，不是实际用户位置。位置需记录坐标系（WGS84/GCJ02）、精度、用户授权时间，不从 EXIF 静默获取定位。

### 5.3 消息状态和输出事件

消息状态：`accepted` → `processing` → `streaming` → `completed`；也可能 `failed`、`canceled`。

建议统一事件名称：

| 事件 | 消费者 | 含义 |
| --- | --- | --- |
| `message.accepted` | APP/Web | 已保存请求，返回 `message_id` |
| `assistant.text.delta` | APP/Web | AI 文字增量 |
| `assistant.message.completed` | APP/Web | AI 完整回复及引用 |
| `media.processing` / `media.completed` | APP/Web | 视频、录音异步进度 |
| `support.requested` / `support.queued` | APP/客服台 | 转人工与排队 |
| `support.assigned` / `support.taken_over` | APP/客服台 | 客服接单与接管 |
| `support.message.created` | APP/客服台 | 人工消息 |
| `support.call.invited` / `support.call.connected` | APP/客服台 | 通话邀请与连接 |
| `support.resolved` / `ai.resumed` | APP/客服台 | 人工结束、AI 恢复 |
| `tool.confirmation_required` | APP/客服台 | 写操作需要明确批准 |

每个事件具备 `event_id`、`conversation_id`、`sequence`、`timestamp`，支持断线后按游标补拉；幂等消息与事件去重必须有自动化测试。

### 5.4 跨模型上下文策略

1. 所有原始消息、附件及业务结果先存到本项目，不把模型服务端历史视为唯一来源。
2. 文本问答使用最近 N 轮消息 + 较早摘要 + 相关知识检索结果。
3. 从文字切入 Omni 时，将最近重要消息、图像分析摘要、关联设备/工单作为可信上下文注入实时模型。
4. Omni 通话中的转写、回复及工具调用结果写回同一 Conversation；明确标记语音转写与真实录音的来源。
5. 通话结束后更新摘要；重新聊天能够继续追问。
6. 已上传媒体保留附件 ID 与分析摘要；除非用户要求复核，不重复把全部历史视频提交给模型。
7. 通话过程中用户发送文字时，消息先在后端落库，再通过实时 DataChannel/受支持事件同步 Omni；必须有 ACK、幂等和失败回退测试。

**实现边界：** WebRTC 直接连接模型时，部分转写事件只在 APP 的 DataChannel 上出现。首期可以签名上报并标注事件来源，但不要把客户端上报视为不可篡改审计；如果后续需要严肃录音证据，应单独建设受授权的服务端媒体录制/审计链路。

## 6. 新增数据表与迁移原则

**先查重再建表。** Codex 必须检查 `ai_agent_chat_history`、`ai_chat_history`、`ai_chat_message`、用户、设备和会话相关已有表，不能直接执行下面的概念清单去创建重复实体。

| 建议实体 | 核心字段 | 关键约束 |
| --- | --- | --- |
| `ai_conversation` | id、tenant_id、owner_user_id、agent_id、project_id、title、summary、state、last_seq、timestamps | 持久会话索引、租户授权 |
| `ai_message` | id、conversation_id、seq、sender_type、sender_id、parts_json、status、client_message_id、reply_to、model、timestamps | 唯一 `(conversation_id, seq)`；唯一 `(conversation_id, client_message_id)` |
| `ai_attachment` | id、tenant_id、owner_id、storage_key、mime、size、sha256、width、height、duration、status、expiry | 所有读取经服务端授权 |
| `ai_media_job` | id、attachment_id、task_type、model、status、attempt、error、timestamps | 幂等执行、超时/重试/死信 |
| `ai_realtime_session` | id、conversation_id、channel、provider、status、started_at、ended_at、last_event_seq | 同一会话多次通话 |
| `ai_tool_invocation` | id、conversation_id、caller、tool_name、approval、result、audit | 只读或审批控制 |
| `ai_model_usage` | id、conversation_id、model、tokens、elapsed、estimated_cost、error_code | 预算和成本 |
| `support_request` | id、conversation_id、requested_mode、group、priority、status、requested_at、assigned_at | 防止重复申请 |
| `support_assignment` | id、support_request_id、agent_user_id、assigned_at、released_at、reason | 单个主要负责人，保留转派历史 |
| `support_agent_profile` | sys_user_id、skills、project_scope、capacity、status | 复用现有管理员账号/权限 |
| `support_media_room` | id、conversation_id、request_id、room_name、state、start/end | 仅保存元数据，不存媒体密钥 |
| `support_annotation` | id、source_attachment_id、video_time_ms、vector_json、preview_attachment_id、author_id | 原图不可覆盖 |
| `support_event` | id、conversation_id、event_type、actor、from_state、to_state、occurred_at | 审计/SLA |
| `ai_legacy_session_link` | conversation_id、legacy_agent_id、legacy_session_id、mac_address | 老 ESP32 历史兼容 |

在 `main/manager-api/src/main/resources/db/changelog/` 追加迁移 SQL，并更新仓库实际使用的 changelog 入口。每项迁移必须是正向、可重复部署的变更；**禁止修改旧迁移、`DROP` 旧聊天表、清空历史或重建生产数据库。**

历史数据迁移采用“新消息写新表、老历史只读适配、按需增量回填”的顺序。首先保证旧管理台查询不受影响，稳定后再把展示层切到统一 Conversation。

## 7. 媒体服务：上传、视频理解与截图

### 7.1 上传流程

1. APP 调用 `POST /api/v1/ai/uploads/init`，获取限定 MIME、大小、有效期的私有对象存储上传授权。
2. APP 将媒体直接上传 OSS/兼容对象存储。
3. 调用 `POST /api/v1/ai/uploads/{id}/complete`。
4. 服务端核对文件头、大小、哈希、实际媒体格式；拒绝伪造 MIME、恶意文件和越权关联。
5. 入库 `ai_attachment`，调用统一发送消息 API 引用附件。
6. 视频/音频通过 Python media worker 异步处理；处理成功后发送进度/结果事件。

首期产品限额建议：图片单张 ≤20MB；短视频 ≤100MB、≤3 分钟；录音 ≤50MB。它们是本项目为了成本和用户体验设定的初始值，不是百炼模型的最终能力上限。上传超限时提供明确错误，后续再开放长视频后台任务。

### 7.2 媒体工作进程

在同仓库新增独立 Python worker，复用 FFmpeg。推荐 **Redis Streams 消费组 + MySQL 任务状态**：`manager-api` 写任务和通知，Python worker 消费、处理、写回状态。若实际仓库已有可复用任务框架，优先沿用，避免再装一套 Celery。

至少实现：`inspect_media`、`thumbnail`、`video_visual_analysis`、`audio_video_analysis`、`audio_transcription`、`annotation_preview`。大型视频不在主 HTTP 请求内同步处理。

### 7.3 图片/视频标注

使用 `Konva.js` 在 `manager-web` 实现静态标注：

- 来源：用户照片、已上传视频的暂停帧、客服看到的 LiveKit 实时视频截图。
- 工具：箭头、圆圈/椭圆、矩形、文字、撤销、重做、清空当前标注、保存发送。
- 保存：原始图片不改；保存 `vector_json`（各图形相对坐标 0～1），并输出一张可直接在 APP 中查看的合成 PNG/JPEG。
- 关联：如果来自视频，记录原视频附件 ID 和精确时间戳 `video_time_ms`；如果来自实时视频，记录抓图时间和通话 ID。
- 权限：只有对该会话有权限的客服/专家可标注或下载原媒体。
- 回传：使用统一 `ai_message`，`sender_type=support_agent`，`parts.type=annotated_image`。

**首期只做“冻结画面→标注→发送图片”。** 不做双端实时白板、实时视频轨道绘制同步；后续若确有需要可加数据通道和画布状态同步。

## 8. 实时音视频与切换规则

### 8.1 AI 实时通话

- APP 创建持久 Conversation 后申请 Omni 实时会话。
- `manager-api` 通过后端持有的百炼密钥代理 WebRTC SDP 交换，APP 不接触长效 API Key。
- APP 音频/视频通过模型支持的媒体轨道发送；文字与事件通过 DataChannel。
- 支持麦克风开关、摄像头开关、前后置切换、用户打断、断线恢复、历史摘要注入和回复转写。
- 模型视频输入的抽帧率与费用由实际接入方式控制：APP 本地预览可流畅，送模型的视觉帧按场景限频并实测，首先按每秒约 1 帧评估。
- 模型实时会话结束不等于 `conversation_id` 结束。

### 8.2 真人客服音视频

LiveKit 自建 SFU，网页使用 `livekit-client`，安卓使用官方原生 Android SDK（uni-app 通过原生插件封装）。不建议直接使用纯 WebView 冒充完整 Android 音视频 SDK。

`manager-api` 校验用户/客服对话权限后签发**短期、限定房间、限定发布/订阅权限**的 LiveKit Token。客服可以邀请用户加入语音或视频；用户明确同意后才开启设备轨道。

支持：

- 文字聊天中升级为语音或视频；
- 实时视频中关闭摄像头保留语音；
- 前后摄像头切换、用户静音、客服静音；
- 弱网自动降分辨率，必要时降级为语音；
- 客服在直播画面截图并标注；
- 主客服转派专家，后续可加入第二位专家会诊。

**转 AI 到人工的媒体切换：** 不把现有 Qwen WebRTC 连接误认为能直接迁移到 LiveKit。先完成接单和 LiveKit 房间授权，APP 过渡到真人房间，关闭 AI 自动回复；允许出现短暂重连，但不能丢聊天历史。不得在用户未同意前自动把摄像头流发送给人工。

**真实电话号码外呼：** 与 APP 内 VoIP 不同，属于后续 SIP/PSTN 项目，需额外电话服务商、号码和计费，不纳入 V1 必须验收范围。

### 8.3 自建网络准备

LiveKit 部署需要公网 IP、域名、受信 TLS 证书，WebRTC UDP/TCP 网络与 TURN/TLS。上海/昆山现场需用园区 Wi-Fi、4G、5G、企业防火墙分别测试。官网自建说明要求正确配置 HTTPS、TURN 和必要端口；开发环境通过不能视为生产可用。

## 9. AI 转人工、排队、客服工作台

### 9.1 状态机

```text
AI_ACTIVE
  └─ request_human → HUMAN_REQUESTED
       ├─ queue → HUMAN_QUEUED
       │   ├─ claim → HUMAN_ASSIGNED → HUMAN_ACTIVE
       │   ├─ cancel → AI_ACTIVE（明确告知）
       │   └─ timeout/offline → OFFLINE_MESSAGE
       └─ 发现已有请求 → 返回已有请求（幂等）

HUMAN_ACTIVE
  ├─ transfer_expert → EXPERT_PENDING → HUMAN_ACTIVE
  ├─ waiting_user → HUMAN_WAITING_USER → HUMAN_ACTIVE
  └─ resolve → RESOLVED → AI_ACTIVE（经明确恢复）
```

- 用户点击转人工，或明确说“帮我转人工”，均创建 `support_request`。
- 申请后 AI 可以告知“正在为您转接”，但不得继续与正式接管客服竞争回复。
- 一次请求只能有一个主要客服通过原子更新/事务抢单成功。
- 若客服下线、浏览器断连或超时，要释放/重排队并记录事件。
- 没客服在线允许留言，APP 显示排队状态和预计处理方式；不承诺人员未排班时仍有真人 24 小时接听。
- 人工结束服务后是否恢复 AI，由明确状态事件决定，不默认偷偷把人工客户转给 AI。

### 9.2 工作台界面

建议新增 `main/manager-web/src/views/SupportWorkbench.vue`（以当前路由布局调整），配套 `src/apis/module/support.js` 与 Konva 标注组件。

- **左栏：** 待接入、排队中、我的会话、超时、已结束；客服在线、接单状态和技能组。
- **中栏：** 全部 AI/人工混合历史、图片/视频、标注图、当前输入框、通话窗口和截图按钮。
- **右栏：** 用户与项目、设备、维修工单、实时温度/报警、AI 摘要、“请求专家”操作。
- **顶部：** 接单、回复、语音、视频、截图标注、转派、结束服务。

首期工作台仅要求 PC 浏览器，兼容常用 Chrome/Edge；不要求客服必须使用原生 APP。

### 9.3 AI 交接摘要

转接时生成一条结构化摘要，字段：`user_goal`、`project/equipment`、`symptoms`、`evidence_attachments`、`verified_facts`、`hypotheses`、`attempted_steps`、`missing_information`、`safety_flags`。

“AI 猜测”必须与“由用户或设备数据证实”严格区分；客服可点击查看对应原始消息。摘要供客服参考，不取代人工技术判断。

### 9.4 运营与 SLA

支持一线客服、制冷技术专家、客服主管、管理员；基于现有用户角色体系增加权限和技能标签，不重新建立一套登录账号。

优先级来源：客户服务等级、项目、故障紧急程度、是否现有工单、等待时长。记录 `transfer_wait`、`first_human_response`、`call_connect_delay`、`expert_wait`、`human_handling_duration`、`timeout_rate`，阈值配置化，供大冷总管 SLA 体系使用。

客服工作台“AI 辅助建议”只供内部查看；**人工点击发送后才对客户可见**。人工完成后可生成远程指导记录草稿，关联照片、截图、视频时间点及工单。

## 10. 业务 MCP 与大冷总管集成

延续原小智服务已有工具框架，在业务 API 外建立授权网关。首期实现：

| Tool 名称 | 返回内容 | 权限 |
| --- | --- | --- |
| `search_maintenance_knowledge` | WeKnora 制冷知识、引用来源 | 只读 |
| `get_equipment_info` | 厂牌、型号、所属园区、设备属性 | 只读 |
| `get_realtime_temperature` | 温度、时间、测点和有效性 | 只读 |
| `get_equipment_alarms` | 历史报警、持续时间、解除状态 | 只读 |
| `get_service_order` | 工单状态、服务进度、关联设备 | 只读 |
| `get_project_by_location` | 经纬度匹配的园区与项目 | 只读，先校验定位授权 |
| `find_available_expert` | 可服务的专家或客服组 | 只读 |
| `create_service_order_draft` | 待人工确认的工单草稿 | 可写草稿；正式提交须确认 |

实现约束：

- 模型仅传“希望执行的工具与参数”，真正的项目/客户/权限由后端依据已认证用户上下文验证。
- 永不允许模型自填 `tenant_id` 就获得跨租户访问。
- 涉及 PLC、压缩机启停、阀门状态、改设定值等危险操作，首期完全禁用自动执行。
- 工具返回保留数据时间戳、单位和来源，提示“数据过期/传感器异常”。
- 服务端记录工具调用审计；写动作采用二次确认和可追溯操作人。

## 11. API 契约初稿

以 `manager-api` 为统一对外服务入口。下列 URI 是建议新接口，Codex 需按已有路由前缀及统一 `Result` 响应结构整理 OpenAPI，最终接口命名可微调，但**消息模型和语义不允许各端自行改变**。

| 方法 | 建议 URI | 功能 |
| --- | --- | --- |
| POST | `/api/v1/ai/conversations` | 创建持久对话 |
| GET | `/api/v1/ai/conversations` | 对话列表 |
| GET | `/api/v1/ai/conversations/{id}/messages` | 游标分页历史 |
| POST | `/api/v1/ai/conversations/{id}/messages` | 统一文本/图片/视频/位置/混合消息 |
| GET | `/api/v1/ai/conversations/{id}/events` | SSE 流式 AI 答案和任务进度 |
| POST | `/api/v1/ai/uploads/init` | 附件直传初始化 |
| POST | `/api/v1/ai/uploads/{id}/complete` | 完成上传及安全校验 |
| POST | `/api/v1/ai/conversations/{id}/realtime/start` | 创建 Omni 实时会话 |
| POST | `/api/v1/ai/realtime/{id}/offer` | 后端代理 SDP 交换 |
| POST | `/api/v1/ai/realtime/{id}/end` | 关闭实时会话并保存摘要 |
| POST | `/api/v1/ai/conversations/{id}/handoff` | 用户申请转人工 |
| GET | `/api/v1/support/queue` | 人工请求队列 |
| POST | `/api/v1/support/requests/{id}/claim` | 原子抢单 |
| POST | `/api/v1/support/requests/{id}/transfer` | 转其他客服/专家 |
| POST | `/api/v1/support/requests/{id}/resolve` | 结束人工服务 |
| POST | `/api/v1/support/rooms` | 创建 LiveKit 房间 |
| POST | `/api/v1/support/rooms/{id}/token` | 按角色签发房间令牌 |
| POST | `/api/v1/support/annotations` | 保存截图、向量标注及生成图 |
| POST | `/api/v1/ai/actions/{id}/confirm` | 工具写动作确认 |

人工消息推送、排队变化、坐席心跳采用专用认证 WebSocket（如 `/api/v1/support/ws`）；历史与附件必须先持久化后推送，断线按事件游标重放，不能只依赖 LiveKit 的数据通道保存客服聊天。

### 11.1 错误和冲突约定

- 未认证：401。
- 无权限/越权附件：403，不能泄漏附件是否存在。
- 不存在的会话或媒体：404。
- 重复转接/重复抢单：返回当前合法状态，或 409 冲突，保证幂等。
- 过大/不支持文件：413/415。
- 模型超时/限流：明确 `MODEL_TIMEOUT`/`MODEL_RATE_LIMIT`，提供重试提示。
- 摄像头未授权：提示用户同意，不绕过操作系统权限。

## 12. 安全、稳定性与运维

### 12.1 安全必做

1. **多租户与项目隔离：** 管理后台、APP、客服、专家、MCP、附件所有链路统一鉴权；必须加入跨租户攻击测试。
2. **密钥保管：** 百炼、OSS、LiveKit、MCP 密钥只在服务端配置/密钥管理中；不得写入数据库明文日志、前端包或提交到 Git。
3. **媒体隐私：** 摄像头和麦克风要用户授权；视频默认不录制；截图提示用户且保留操作人；录音录像若扩展另需告知与保留策略。
4. **附件安全：** MIME 与文件头双校验、文件大小限制、短期签名 URL、禁止公开桶、EXIF 隐私处理、访问审计和可配置删除周期。
5. **模型提示注入隔离：** 上传文本/图片中的指令不得覆盖系统权限或调用高风险 MCP；模型的工具请求始终由服务端强制校验。
6. **真实设备控制：** AI 不得直接控制压缩机、阀门、PLC；仅提供操作建议，并提示专业人员在安全条件下执行。
7. **部署加固：** 反向代理 HTTPS/WSS、限流、CORS 白名单、HTTP 安全头、WebSocket 鉴权、管理后台 RBAC、安全扫描和日志脱敏。

### 12.2 运行保障

- Redis 维护会话热点状态/客服在线状态，但 MySQL 是持久化事实源。
- 所有上传、视频处理、通知和工具写操作实现幂等、可重试、有限重试和死信处理。
- 记录 `trace_id` 串联 APP 请求 → manager-api → xiaozhi-server → 百炼/LiveKit/MCP。
- 模型用量按 `conversation_id`、`tenant_id`、`model` 计费统计，视频抽帧、长上下文使用和闲置通话自动限额。
- LiveKit SFU 单独监控 CPU、带宽、TURN 中继率、通话成功率、平均丢包和重连次数。
- 所有新功能通过配置开关启用，支持逐项目灰度；出现问题一键关闭新功能而不影响老 ESP32。

建议配置开关：`features.multimodal_enabled`、`features.ai_realtime_enabled`、`features.support_handoff_enabled`、`features.human_video_enabled`、`features.annotation_enabled`；实际字段名称以当前配置结构为准。

## 13. 研发里程碑（12～14 周）

| 阶段 | 周期 | 主要开发 | 验收产物 |
| --- | --- | --- | --- |
| P0 仓库基线审计 | W1 | 核对最新分支、历史表、权限、现有 API、模型配置、测试；验证百炼 SDP、文本事件和 LiveKit PoC | 架构 ADR、接口契约、回归测试清单 |
| P1 统一对话 | W2–W3 | 新数据表、消息 parts、历史/幂等、文本流式、旧历史映射、账号权限 | 文字多轮及旧 ESP32 历史兼容 |
| P2 多模态媒体 | W4 | OSS 上传、图文、位置、短视频/录音任务、Flash/Omni 分析 | 混合消息、视频异步分析 |
| P3 AI 实时通话 | W5–W6 | Omni WebRTC、模型事件、摄像头、文字注入、上下文同步 | APP/示例客户端实时语音视频 |
| P4 人工文字客服 | W7–W8 | 转人工、队列、抢单、网页工作台、完整历史、SLA | 人工接管文字与附件 |
| P5 标注 | W9 | Konva 截图/标注/保存/发送、视频时间戳 | 客服可圈、箭头、标注并回复 |
| P6 人工语音视频 | W10–W11 | LiveKit、自建 TURN、网页/安卓客户端、媒体切换、断线恢复 | 真人客服实时看画面并语音指导 |
| P7 业务集成 | W12 | WeKnora、IoT、工单、专家转派、AI 交接摘要、费用与审计 | 远程技术指导闭环 |
| P8 现场试点 | W13–W14 | 园区弱网、并发、安全、权限、超时、恢复、灰度 | 可供正式试点的部署方案与记录 |

优先级：P0～P5 是第一可用版本，P6 是完整远程指导版本，P7～P8 是生产试点门槛。若个别外部业务 API 暂未可用，必须提供模拟/契约测试，不能阻塞基础客服功能。

### 13.1 人员分工

- **后端 A：** Python 模型适配、媒体 worker、Omni 实时、LiveKit 集成、音视频性能。
- **后端 B：** Spring Boot 数据模型、鉴权、客服路由、接口、MCP 权限、迁移与代码审核。
- **前端/安卓：** Vue 2 客服工作台、Konva 标注、Android 原生 WebRTC/LiveKit 接入、消息展示。
- **产品/项目：** 中外运等冷库场景验收数据、客服流程、SLA、现场网络测试。

原计划如果只有一名前端同时负责管理网页和安卓原生音视频适配，W10～W11 风险较高；应在 P0 提前验证原生桥接，必要时临时增加 Android 支援或顺延音视频里程碑。

## 14. 统一验收与测试矩阵

### 14.1 功能测试（必须通过）

1. ESP32 旧协议可连接、唤醒、语音问答、打断、OTA、MCP，不因新功能回归失败。
2. APP 发送文字，AI 流式回复；刷新/重连后能取回完整消息。
3. 同一条消息包含文字、两张照片、一段视频和坐标；后端存为一条消息，保持顺序和来源。
4. 用户上传风机铭牌图片得到结构化分析；隔天追问仍能引用原图及摘要。
5. 上传一段带声音的压缩机视频，系统路由 Omni Flash 并说明画面与声音分析的证据边界。
6. AI 实时语音能持续多轮、打断和关闭摄像头；语音历史同步到 Conversation。
7. 文字聊天→实时语音→再文字聊天，能继续同一设备问题，不丢失前文；切换失败时消息不丢。
8. 用户申请人工；只有一位客服成功抢单；AI 停止自动回复；排队和接单状态准确。
9. 客服网页可看此前 AI 文字、图片、视频和摘要，可继续发文字、图片、位置和标注图。
10. 客服在用户上传视频 18 秒处截图、画圆和箭头并发送；APP 展示清晰，原视频和原图未被覆盖。
11. 客服邀请用户语音、视频；用户拒绝时不会打开媒体，接受后双方正常通话。
12. 4G/Wi-Fi 切换导致实时通话断开时，APP 可以重连并继续查看聊天历史。
13. 客服可转专家；专家看到完整交接材料；结束后生成指导记录草稿。
14. 会话权限、附件权限、工具权限在任意两个客户之间不能交叉访问。
15. 对端离线、未排班、接单超时、重复请求、重复上传、模型限流、对象存储失效时均有可理解的结果与恢复路径。

### 14.2 性能目标（工程目标，需在 P0 基线实测后锁定）

- 普通 Wi-Fi 下 AI 实时通话首段语音响应目标 P95 ≤2.5 秒；如因云端时延不达标，以实测记录调整，不把该值写作模型官方保证。
- 纯文字 P95 首 token 时间、视频分析任务时长按模型和文件大小分别制定；先收集真实样本再确定阈值。
- 1 对 1 人工通话在测试的办公 Wi-Fi、4G/5G 网络中尽量达到 ≥95% 成功接通率；通话质量按丢包、重连、端到端延迟监测。
- 连续 30 分钟通话、20 路并发文字/若干路并发视频作为初始压测起点；正式并发指标以实际 CPU、带宽和百炼配额评估确定。
- 原子抢单竞态测试：并发 N 次只有 1 次成功；跨租户权限用例 100% 拒绝。

### 14.3 必须执行的仓库测试

- Python：`pytest` 与现有 `main/xiaozhi-server/tests`、语音/视觉性能工具。
- Java：`manager-api` 的 `mvn test`、接口和数据库迁移测试。
- Web：`manager-web` 的 `npm test`、`npm run build`、现有契约测试与新客服台组件测试。
- 联合：OpenAPI 合约测试、ESP32 实机回归、浏览器与安卓真实设备、权限安全与弱网测试。

## 15. Codex 开发执行规约

这份说明是研发目标与验收契约，不要求 Codex 一次性改完整仓库。建议分阶段逐个实现，每阶段都要交付代码、测试、文档和可回滚配置。

### 15.1 第一次进入仓库必须做的事

1. 读取仓库 `AGENTS.md`（如果有）、`README`、`main/README.md`、现有 `docker-compose`、配置说明、`manager-api` 权限和数据库迁移。
2. 确认当前提交/分支；扫描现有功能，找出与本文重叠的已实现能力，不重复开发。
3. 整理原 ESP32 WebSocket、OTA、视觉、历史查询、MCP 的回归测试基线。
4. 将 `Conversation`、`MessagePart`、`Attachment`、`RealtimeSession`、`SupportRequest`、`SupportAssignment`、`Annotation` 的 OpenAPI 与状态机作为“第一份可审核提交”。
5. 将技术偏离、迁移冲突和外部服务缺失记录为 `docs/ai-customer-service/DECISIONS.md`，重大改变先在 PR 中解释。

### 15.2 代码目录建议

按现有模块组织方式扩展，不硬搬新框架：

```text
main/
  manager-api/
    src/main/java/xiaozhi/modules/
      conversation/       # 持久对话、消息、附件、SSE
      support/            # 转人工、分配、工作台、SLA
      media/              # 媒体元数据和处理任务
    src/main/resources/db/changelog/
  xiaozhi-server/
    core/
      providers/          # Qwen Flash/Omni 适配器，沿用原风格
      api/                # 原 vision/OTA 保留；新增内部路由
      services/           # 新 conversation bridge / realtime bridge
    media_worker/         # 独立视频/录音处理进程
    tests/
  manager-web/
    src/views/            # SupportWorkbench.vue
    src/components/       # SupportChat、VideoPanel、AnnotationEditor
    src/apis/module/      # 新 conversation/support API
docs/ai-customer-service/
  ARCHITECTURE.md
  OPENAPI.yaml
  MESSAGE_SCHEMA.json
  STATE_MACHINE.md
  DEPLOYMENT.md
  TEST_PLAN.md
  DECISIONS.md
```

目录是建议结构，代码命名需尊重原仓库的模块和分包习惯。安卓 APP 可能位于另一个项目：本次后端交付必须先包含 API 契约、WebRTC/LiveKit 接入示例和事件协议，不能把前端实现写在不存在的仓库内。

### 15.3 每个阶段的 Definition of Done

- 修改范围明确，兼容原 API；新增功能默认 feature flag 关闭。
- 数据库迁移可在干净测试库执行，也能在已有数据测试库增量执行。
- Controller/Service/DAO、媒体任务、模型路由、状态机均有关键单元与集成测试。
- API 变更同步更新 OpenAPI 和示例请求/响应。
- 关键业务含鉴权、异常码、超时、日志、trace_id、幂等和回滚方案。
- 更新 Docker/部署、环境变量说明，密钥不进入代码。
- 通过 Python、Java、Web 现有回归；附一段真实联调记录或可复现模拟测试步骤。
- 不自动把设计中的“阶段二”功能扩充进当前阶段，避免任务失控。

### 15.4 推荐 Codex 第一条开发任务

> 你现在在 `xinnan-tech/xiaozhi-esp32-server` 仓库中工作。请先阅读 `xiaozhi-server-multimodal-support-plan-v1.md`，完成 P0 仓库基线审计和 P1 统一数据契约的设计，不要立即修改旧 ESP32 语音链路。请列出当前仓库可复用的类、控制器、SQL 迁移、配置和测试，指出与规划的冲突，给出可执行的第一批 commit/PR 切分。接着实现 Conversation/MessagePart 的最小持久化与测试；所有改动保持旧服务兼容，先运行相关测试并报告结果。

## 16. 依赖、部署准备与风险清单

### 16.1 开发前准备

- 百炼北京地域 Workspace ID、API Key、模型开通与额度：`qwen3.8-flash`、`qwen3.8-omni-flash-realtime`；需要分析音视频声音时额外 `qwen3.8-omni-flash`。
- MySQL 8、Redis、既有 manager-api 与 xiaozhi-server 的可运行开发环境。
- 私有 OSS/兼容对象存储、必要的 CORS 与签名上传配置。
- 可用于 LiveKit 的公网云主机、域名、TLS、TURN/UDP/TCP 开放策略。
- 安卓测试机、浏览器麦克风/摄像头及至少一台原 ESP32；园区 Wi-Fi 和 4G/5G 实测环境。
- 约 10–20 组真实冷库图片、短视频和报警场景（先脱敏并取得使用授权）。
- 大冷总管测试环境的只读工单、设备、温度、报警 API，以及 WeKnora 的可用入口。

### 16.2 主要风险

| 风险 | 处理策略 |
| --- | --- |
| 旧服务和新会话数据库重复 | 先建桥接表及兼容适配，不重写老历史 |
| manager-web 实际是 Vue 2 | 使用 Vue 2 可用 Konva、LiveKit JS，不依赖 Vue 3-only UI |
| 安卓 uni-app 与两个 WebRTC SDK 适配困难 | P0 先做原生小 Demo；必要时封装原生插件 |
| AI 和人工音视频不能原地同一 RTP 无缝迁移 | 首期明确重新建 LiveKit 房间，保证聊天历史无缝 |
| Qwen Realtime 事件和 APP DataChannel 的落库可靠性 | ACK + 服务端消息账本 + 重连补发；标注审计来源 |
| 现场网络不稳定 | TURN/TLS、降视频分辨率、允许退回语音 |
| 多个客服抢同一单 | 数据库事务原子抢单和去重 |
| 传视频费用和延迟过高 | 限大小/时长、抽帧、异步队列和每会话预算 |
| 客户数据越权或视频隐私 | RBAC、附件短期签名、用户明确同意、视频默认不录制 |
| 外部模型升级或不可用 | 模型适配层、配置开关、旧 ASR/LLM/TTS 可降级 |
| 超过团队实际开发能力 | 分里程碑交付，优先人工文字客服和截图标注 |

## 17. 参考项目与官方文档

以下内容是开发期间应查阅的权威入口；代码实施时核对实际版本及官方最新 API：

- 小智 Server 开源仓库：https://github.com/xinnan-tech/xiaozhi-esp32-server
- 小智 ESP32 固件：https://github.com/78/xiaozhi-esp32
- Qwen3.8 Omni Realtime：https://help.aliyun.com/zh/model-studio/qwen3-8-omni-flash-realtime
- Qwen Omni Realtime API：https://help.aliyun.com/zh/model-studio/realtime
- Qwen3.8 Flash：https://help.aliyun.com/zh/model-studio/qwen3-8-flash
- Qwen3.8 Omni Flash：https://help.aliyun.com/zh/model-studio/qwen3-8-omni-flash
- Qwen 音视频理解：https://help.aliyun.com/zh/model-studio/qwen-omni
- LiveKit 服务端源码（Apache-2.0）：https://github.com/livekit/livekit
- LiveKit 自部署与 TURN：https://docs.livekit.io/transport/self-hosting/deployment/
- LiveKit Android SDK：https://docs.livekit.io/transport/sdk-platforms/android/
- LiveKit 浏览器 SDK：https://docs.livekit.io/intro/basics/connect/
- Konva.js 源码（MIT）：https://github.com/konvajs/konva
- Konva 截图和导出：https://konvajs.org/docs/data_and_serialization/Stage_Data_URL.html

---

**最终验收定义：** 用户在同一条对话里先向 AI 发送故障文字、照片、运行视频和位置，接着开启 AI 摄像头进行追问，随后点击转人工；网页客服完整看到此前历史，通过实时视频查看现场，截取画面画箭头和圆圈发回 APP，必要时转技术专家，最终生成指导记录并关联大冷总管工单。以上全流程成功，且原 ESP32 协议、历史和 IoT 功能无回归，即达到 V1.0 核心业务目标。
