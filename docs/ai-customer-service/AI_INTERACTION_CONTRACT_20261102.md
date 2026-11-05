# AI 交互协议 A0 / Kuikly A1 交接

日期：2026-11-02。此文冻结 A0 目标 wire，接口实现状态逐项区分。伴随文件 [AI_INTERACTION_OPENAPI_20261102.yaml](./AI_INTERACTION_OPENAPI_20261102.yaml) 仅描述能力接口和目标结果 DTO，不替代旧 OpenAPI。不能根据本文认为计划接口已部署或实时工具已验收。

## 1. 能力发现

`GET /api/v1/app/capabilities` 已存在，认证后返回现有 `{code,msg,data}`；`data.contract_version=1.0` 及现有 `features`、`limits`、`errors`、`notes` 保持兼容。新增 `data.interaction` 是 additive 字段，现为本批代码，不代表已部署：`protocol_version=1.0`、`availability_basis=configuration_only`、`capabilities`、`attachment_inputs`、`realtime`。客户端须忽略不认识的字段。

能力项 `{status,implementation}`：`disabled` 表示未启用或计划能力尚未实现；`unconfigured` 表示已启用但该项必要模型配置缺失；`configured` 表示本地功能配置启用，**不证明依赖健康**；`verification_required` 表示相关开关/模型配置存在，provider 账号、worker、存储、网络、鉴权及真实媒体仍待验证。`implementation=planned` 永远不能视为可调用；typed_tool_results、多模态工具、实时工具、AI 会话记忆已在本批实现，依赖明确开关且默认关闭；普通答复 TTS 仍为计划关闭。无新增能力默认启用。未授权、缺配置和 provider 失败以实际请求响应为准，不能由配置快照推断。

附件受大小、文件名/MIME、真实内容、解码及持久化检查，图片 JPEG/PNG/WebP 最大 20 MiB；PDF/UTF-8 文本最大 20 MiB；WAV/MP3/M4A/OGG 最大 50 MiB、处理时长上限 600 秒；MP4/WebM 最大 100 MiB、处理时长上限 180 秒。图片最多 2400 万像素、边长 8192；PDF 最多 200 页，提取文本上限 60000 字符，可标记截断，扫描 PDF 不等于已 OCR。短视频最多提取 6 帧，不表示逐帧理解。服务器返回大小上限是上传约束，不保证该文件可解码、可提取或可被模型分析。原声模型上限另受具体模型约束，不能用 ASR 文本代称原声理解。

实时使用已存在 `/api/v1/ai/realtime/capabilities`，只在服务已启用且配置有效时以它的响应为权威；manual 默认，server_vad 仅明确启用时可选。会话创建后模式和设备同意不可更改；更改须结束会话并重新同意。输入单声道 s16le 16 kHz，输出 s16le 24 kHz；音频单块 65536 字节、手动单轮 1920000 字节；采样帧 JPEG 最多 190000 字节、921600 像素、间隔至少 1000 ms。矩阵只说明协议格式，不意味着实际 provider 已连接。Camera 默认关闭、必须经真实同意。音频/视频原件、实时 PCM 和采样帧的处理不能相互冒充。

## 2. A1 已有交互及恢复

使用同一 Server conversation，身份兑换、续期和 Bearer 授权沿现有 external identity 契约。基本入口：`POST/GET /api/v1/ai/conversations`，`GET /{id}`、`POST/GET /{id}/messages`、`GET /{id}/events`、`GET /{id}/stream`、`GET /{id}/runs`、`GET /{id}/runs/{runId}`、`POST /{id}/runs/{runId}/cancel|retry`（上述相对 `/api/v1/ai/conversations`）。上传 `POST /api/v1/ai/uploads/init` → `PUT /api/v1/ai/uploads/{id}/content` → `POST /api/v1/ai/uploads/{id}/complete`，查询 `/api/v1/ai/attachments/{id}`；外部附件访问走 source-bound `/api/v1/external/attachments/{id}/access`。最终可用性取决于功能开关、服务和身份。

消息 `client_message_id` 在网络重试中保持不变，服务端拒绝同 ID 不同正文。`parts` 顺序保留，最多 16 项，每项 text 最多 8000 字符、总文本最多 32000 字符；媒体最多 10 项，其中视频、音频各最多 1 项，附件须已完成私有上传和处理。位置类型为 `location`，带 `latitude`（-90..90）、`longitude`（-180..180）、`coordinate_system`（`WGS84|GCJ02`）、`accuracy_m`（0..100000）、`source=user_shared` 与 `authorized_at`；由用户主动授权分享，服务端不是实时定位源。区分本地草稿、上传、处理、消息保存、模型处理和服务端终态。run 状态 `queued|running|streaming|completed|failed|canceled`；工具 audit 为 `running|succeeded|failed|discarded`，等待工具不是回答完成。事件与消息服务器账本为准，断线先恢复授权并从持久游标补拉、去重；流增量不覆盖已保存终态。实际请求错误：401 重新认证或续期；403 停止受限操作；404 表示关闭、资源不存在或无权，不自行猜测；409 重新读取权威状态并按具体 code 处理；429/503 有界退避；其他失败明示并保留草稿。不要把失败转换成假完成或将未知 usage 填零。

身份、租户、环境或来源切换时中止请求和播放，丢弃旧回调，草稿/游标/缓存按完整身份隔离。新 AI 用户消息只允许一个正式 executor；实时有效时不同时启动普通 run。断线实时会话不重用旧 session、PCM 或设备同意，先查服务端终态，显式新建并重新同意；不会自动开麦/开相机。Laravel 原 client 仍负责登录及原有非 AI 页面，不把 Xiaozhi 请求自动重发给旧 AI 或多个 provider。人工服务不是本阶段依赖。

普通会话的数据库 BIGINT 标识/sequence/cursor 是十进制字符串；不要转 JS 浮点数。**兼容例外**：现有 P3 realtime wire 的 `control_epoch`、`last_event_sequence`、realtime `next_cursor`、usage 和测量项明确是 JSON number，输入的 `expected_epoch` 也要求 JSON integer；不得擅自改成字符串。客户端应精确解析且拒绝不能安全表示的数字。A0 新结果项的 `sequence`、`event_sequence` 和页游标固定十进制字符串，ID 全字符串；其他既有时间遵循原接口序列化；公开工具结果 created_at 使用 ISO-8601 UTC 时间戳。未知值为 null 或省略，不填伪造值。

A4 工作者内部使用 `features.realtime-tools-enabled=false` 默认关闭；新的会话/响应/轮次隔离和 ACK 协议已在本批实现，不改变现有 App 可发送的 WebSocket 白名单。不能由配置矩阵推断任意实时工具已可用。

## 3. 类型化工具结果 / 引用（本批实现）

本批实现 `GET /api/v1/ai/conversations/{id}/tool-results?before=0&limit=50`，新环境开关默认关闭；本地 Dev 已显式部署和验证，不能据此假定其他环境已部署。返回已授权的只读 `{items,next_cursor,has_more}`，按不可变 audit sequence 降序，`next_cursor` 精确返回字符串，limit 1–100，客户端原样传回；现有 `/tools` audit 路由与语义不变。

`GET /api/v1/ai/conversations/{id}/context` 读取当前会话授权范围内最近的截取型 AI context 快照；`available=false` 表示暂无快照，`AI_CONTEXT_DISABLED` 表示功能关闭。它不是客服内部摘要、跨会话记忆或业务事实。

每项字段：`sequence`、`event_sequence`、`tool_call_id`、`execution_type=run|realtime`、可选 `run_id`/`session_id`/`response_id`、`name`、整数 `version`、`status=succeeded|failed`、`result`（失败可为 null）、`result_access=available|restricted`、可选安全 `error_code`、`retryable`、`created_at`。受限时保留真实 status、`result=null`、`error_code=TOOL_RESULT_AUTHORIZATION_REQUIRED` 且 `retryable=false`。`result={kind:technical|business|conversation_evidence,data,citations,observed_at?,stale?,truncated?}`；`data` 是工具专属 allowlist，未知 kind 不执行、不渲染为业务事实。citation 为 `{id,kind:message|attachment|business|technical,title,source_ref,locator?}`；`source_ref` 只允许 `conversation_id`、`message_id`、`attachment_id`、`resource_type`、`record_id`，`locator` 可含整数 `page`、`time_ms`。引用需重新授权后读取，显示真实来源、观察时间与陈旧/截断状态；technical 结果不作为客户业务事实。不给 App raw arguments、未裁剪 provider 结果、内部地址、密钥或 token；公开 response_id 仅用于关联本次已授权实时响应，不公开原始 provider 请求诊断。

访问仍为会话 owner / 当前授权 staff，离岗视图严格遵循不可变 event cutoff；外部业务结果必须重新校验持久化的精确 source 与 Bearer，换 token 不继承旧工具读取权限（原已发表消息和状态仍按原权限可读）。本批 realtime 结果沿同一 DTO，但要独立 session/response/generation 账本，不伪造普通 run。仅展示成功/失败元数据不能替代真实业务端到端验收。

## 4. A5 实时材料扩展

暂停、清空确认、私有材料、独立感知、上下文精确 ACK 和显式恢复的新增协议见 [A5 实时会话补充材料契约](REALTIME_MATERIALS_CONTRACT_20261102.md)。通过 `features.realtime_materials` 与 `interaction.capabilities.realtime_materials` 协商；旧客户端缺少该能力时保持原有实时文字路径。A5 的消息、材料、感知用量与普通 run 分开，不启动第二个正式回答。

支持材料的客户端另需协商 `capture_protocol: material_capture_v1` 并为采集帧携带固定代次 `capture_token`，防止 HTTP 恢复先于旧 WebSocket 音频尾包到达。初始 ready 与每次恢复返回新令牌；不是用新标签重新发送旧媒体。未协商的旧纯实时任务维持原协议，旧 A5 客户端请求材料控制则安全拒绝。完整字段、界限与错误码见 A5 契约补充节。

## 5. 验收边界

A0 定义协议和能力配置矩阵；本批实现不代表运行验证。不构成 Kuikly 接入、真实 provider 授权、弱网、设备音频/视频、Laravel 数据或业务结果验收。新 `interaction` 需通过编译、序列化和默认关闭/前提测试后再部署；计划能力和最终 endpoint 逐项以合入代码、测试、实际部署和设备证据更新。

## 6. A6/A7 与费用审计扩展

普通回答朗读和固定只读技能见 [A6 契约](ANSWER_SPEECH_SKILLS_CONTRACT_20261102.md)。跨普通 AI、实时、材料感知与朗读的准入、滚动小时请求预算及只读诊断见 [A7 契约](AI_RUNTIME_CONTROL_CONTRACT_20261102.md)。`features.ai_runtime_control` / `interaction.capabilities.runtime_admission` 只说明本环境显式配置；HTTP 429 不应触发跨后端或无限重试。模型 token、TTS characters 与未知报告保持不同语义，不合计为虚构的总费用上限。

普通 AI Worker 任务新增可选 `usage_audit: true`。服务端支持时，claim 同事务记录不可变的 run/attempt/派发 lease 哈希、模型与 epoch，Worker 通过 **内部专用凭据**调用 `POST /internal/ai-worker/runs/{id}/usage`：

```json
{
  "lease_token": "original-dispatch-lease",
  "attempt": 1,
  "provider_requests": [
    {"round": 1, "provider_request_id": null, "usage": {"input_tokens": 5, "output_tokens": 2, "total_tokens": 7}, "finish_reason": "tool_calls"}
  ]
}
```

请求为不超过 16 KiB 的严格 JSON，无 query，attempt 为整数 1–3，prefix 最多 4 个 provider round。只接受真实标量用量/null 与受限 request ID/finish reason，不接收正文、客户端模型或聚合猜测。精确历史派发能力可在任务取消、来源撤销、账号停用或普通 AI 开关关闭后补记已发生费用；不授予工具、内容读取、继续执行、完成回写或播放权限。精确重放幂等，冲突或把已知覆盖为未知返回 409；缺历史派发不能退回信任当前 lease。既有晚到 complete/fail 校验保持严格。

旧任务缺少 `usage_audit` 时维持原 Worker 协议。这个内部入口不向 App 开放；会话功能和对应专用 worker 凭据仍是部署前提。未知用量不补零；晚报按实际入账时间参与 A7 滚动窗口，不能据此假称全部缺失报告已覆盖或已实现生产计费。


## 会话创建端与控制台来源页签（2026-11-02）

`POST /api/v1/ai/conversations` 的 JSON 仍为原有 `title`、可选 `agent_id`，不增加必填字段。新增可选请求头 `X-Conversation-Source: app|web`，只用于展示和筛选来源，不参与身份或权限判断；其他取值（包括 `hardware`）返回400。控制台创建时发送 `web`；未传时，已认证的外部主体默认 `app`，普通管理账号默认 `web`。

来源随创建事务记录在既有 `conversation.created` 事件的 `data.source`，回复消息不会改变会话创建端。硬件来源仍以服务端设备会话关联及旧设备账本为准，普通来源头不能覆盖硬件通道。没有新增表或迁移。

内部控制台索引 `GET /api/v1/ai/conversations/index?source=all|app|web|hardware` 对应「全部 / app / 网页 / 硬件」四页签。旧记录没有显式来源时按接入身份兜底：外部主体映射账号归App，其余归网页；这是历史推断，不对旧事件做补写，也不代表设备取证。使用普通管理Bearer的App客户端如需准确归类，应显式发送 `app`；外部主体在网页创建时应显式发送 `web`。
