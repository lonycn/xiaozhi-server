# 小智后端整合 aimeet 远程专家能力的开发计划

日期：2026-10-30。适用分支：`Dev`。

实施进度：B1 人工文字服务及其权限/账本基础已落地，实际接口以 [B1 契约](SUPPORT_B1_API.md) 和 [验证记录](SUPPORT_B1_VALIDATION.md) 为准。下文保留完整规划，涉及媒体、AI 执行等后续阶段的描述不代表已经开放。

`aimeet` 的人工咨询、媒体授权、截图标注、专家转接与 App 接入可以复用到小智后端。整合以小智的统一 Conversation、现有账号和 MySQL 为主体，分批迁移业务规则与测试，复用 LiveKit 和私有对象存储基础设施。原 `aimeet` 保留现有用途，不整体搬入它的 FastAPI、PostgreSQL 用户库和 React 前端。

下一批优先形成同一段对话里的人工文字服务闭环，再接附件、标注和真人音视频。AI 文本执行、转人工时的停答控制与事件推送共用同一控制机制。Vue 3 迁移由独立聊天 `server-vue3` 负责，后端接口不依赖其全部管理页面迁移完成。

## 1 源码基线与当前能力

| 项目 | 核对基线 | 当前边界 |
| --- | --- | --- |
| 小智 fork | `lonycn/xiaozhi-server`，本轮起点 `97067a2`，`Dev` | Spring Boot、MySQL、Shiro；已有持久对话、用户文字与位置消息、幂等与 JSON 事件补拉 |
| 远程专家 | `/Users/lengwang/code/aimeet`，`1a30b93` | FastAPI、SQLAlchemy、PostgreSQL、Redis、LiveKit、SeaweedFS S3、Next.js/React |
| 前端迁移 | `main/manager-web-next`，另一聊天正在开发 | Vue 3 候选应用；现有 Vue 2 预览继续保留 |

小智当前的 `ConversationService` 只保存用户消息，没有实际 AI 执行、客服成员权限、附件服务或人工状态变更入口。数据库已有 `ai_conversation`、`ai_message`、`ai_conversation_event`。不能将这些基础表视为人工客服模块已经实现。

`aimeet` 已有以下源码：

| 能力 | 主要源码 | 整合方式 |
| --- | --- | --- |
| 专家资料、在线、心跳、占用与咨询分配 | `services/api/app/business.py`、`api/business.py`、`models/__init__.py` | 迁入 Java support 模块；增加队列、容量与项目权限 |
| 人工文字、幂等、历史、图片与文件 | `api/chat.py`、`models/chat.py`、`uploads.py` | 复用权限和验证规则；写入统一 `ai_message.parts` |
| 附件私有访问、文件头检查、图片规范化 | `uploads.py`、`integrations/storage.py`、`api/chat.py` | 使用 S3 存储适配器和媒体任务；按新平台限额重新设计上传流程 |
| 截图、原图绑定、场景与成图、候选确认 | `phase45.py`、`api/phase45.py`、`models/phase45.py` | 迁移验证与证据关系，增加视频时间点和统一消息回传 |
| 专家转接、服务代次、旧身份撤销与清理重试 | `phase45.py`、`api/phase45.py`、`business.py` | 拆分文字转派与媒体转接，迁入 support 状态机 |
| 房间 Token、参与者、签名 webhook、房间清理 | `integrations/livekit.py`、`api/business.py`、`api/phase45.py` | 定义 Java RTC 适配接口，复用实际 LiveKit 基础设施 |
| 持久通知与重试 | `DurableEvent`、`phase45.maintain_phase45` | 合并为统一事件与通知 outbox，增加有限重试、退避和可补偿状态 |
| 单次票据、外部身份映射、来源会话撤销 | `app_sso.py`、`api/app_auth.py`、`0009_daleng_app_sso.py` | 复用签名和身份隔离设计；已有大冷总管 v1 接口保持兼容 |
| 媒体桥接、打断、采样、知识检索与 Worker lease | `services/agent/app/provider.py`、`session.py`、`media.py`、`clients.py` | 后置技术验证；通过适配层接入 Python 执行面 |
| 有效媒体计时和模拟账单 | `phase45.py`、`BillingSegment`、`Bill` | 保留设计与测试供后续运营使用，首期不接收费或支付 |

本轮运行了 `test_phase45_unit.py`、`test_chat_unit.py`、`test_app_sso_unit.py`、`test_ai_adapter.py`、`test_migrations.py`，31 项通过。测试使用隔离配置与替身，验证代码规则，不代表实际音视频、云模型或数据库迁移在小智中已经通过。测试环境有一条 FastAPI TestClient 依赖弃用警告，后续升级依赖时处理。

## 2 整合方案与服务职责

### 2.1 采用业务迁移与基础设施复用

| 方案 | 影响 | 决策 |
| --- | --- | --- |
| 将整个 aimeet 合并为另一套公开后台 | 同时维护两套认证、数据库、咨询及消息状态；Vue 与 React 工作台重复 | 不采用 |
| 先把 aimeet 作为独立远程专家系统，用 SSO 跳转 | 可继续服务已有 App，但不能直接满足同一 Conversation 的混合历史与人工接管 | 保留既有系统用途，不作为本次最终集成架构 |
| 将业务规则迁入 manager-api，复用 LiveKit、S3 与可提取的 Python 能力 | 权威数据统一，Java 规则需要重写并移植测试；现有用户与前端契约可保留 | 本次采用 |

复用不等于 Python 文件可以直接被 Java 引用。咨询状态、权限与事务需要按 Spring Boot/MySQL 重写；媒体解码等 Python 代码可提取为独立 worker。每次移植必须保留开源许可、来源提交和来源路径。

```text
Vue 3 工作台 / App / 后续其他终端
             |
             v
manager-api  用户与角色  Conversation  消息和事件  人工队列  RTC 授权
             |
             +---- MySQL  统一业务事实源
             +---- Redis  在线心跳和任务通知
             +---- S3  私有原始媒体与派生图
             +---- LiveKit  真人音视频
             +---- Python 模型与媒体 worker  受控内部任务

原 ESP32 WebSocket / OTA / MCP ---- xiaozhi-server 原链路
```

Spring Boot 负责最终授权、当前服务者、会话状态、消息落库及媒体访问。LiveKit 负责媒体，不担任业务状态数据库。Redis 中的在线状态失效不应丢失排队、消息或分配记录。Python worker 不复制用户数据库，也不根据客户端传来的用户 ID 授权。

### 2.2 现有项目保持各自数据边界

整合期间不修改 `aimeet` 业务库、现有 App SSO、局域网网关或正在运行的设备服务。共享 LiveKit/S3 时使用新的房间名称前缀、对象前缀和服务凭据配置，禁止通用“清空全部房间”操作。

`aimeet` 文档记录的 LAN 地址和 Docker context 不作为小智的新配置默认值。首次接入时重新检查服务可达、证书、命名空间及权限，再在 Dev 验证。

## 3 必须处理的契约差异

### 3.1 一段 Conversation 与多次人工服务分开

`aimeet.Consultation` 同时包含业务咨询、媒体房间、当前专家和服务状态。新的 `conversation_id` 必须跨人工请求、通话和跨天聊天持续存在；一次人工服务是 `support_request_id`，一次通话是 `media_session_id`，一次专家责任关系是 `assignment_id`。

同一 Conversation 可经历多次人工服务和多次通话。挂断只结束当前媒体连接；人工是否结束、AI 是否恢复分别由显式业务动作决定。

### 3.2 两套消息不能继续并行写入

`aimeet` 的 `conversation_messages` 保存 AI 终结文本，`chat_messages` 保存人工文字及单个附件。后者还使用全局 `sequence`、2000 字符上限和不同游标。小智使用对话内递增 `seq`、有序 `parts`、8000 字符单个文字 part 和 JSON 事件游标。

整合后所有用户、AI、客服、专家和系统消息都写 `ai_message`，不另建 `support_chat_message`。人工发送方和身份由服务器生成。现有客户端消息幂等键保持兼容，校验材料加入真实 sender，防止不同发送者复用同一个编号时误认成同一消息。

实时通知只携带已落库对象的 ID、序号与必要状态；断线后从统一事件和消息 API 补拉。模型 delta 与终结消息使用同一个 AI message ID，不能每个 token 新建消息。

### 3.3 专家选择与排队分配不同

现有视频呼叫选择在线空闲专家，无可用专家时返回 409；独立文字咨询允许直接指定已启用专家，甚至专家离线。它没有现成的通用排队、客服组、文本容量和 SLA 调度模块。

新后端同时支持指定专家与通用客服组。无人可接时进入排队或离线留言，不伪装成已接通。文字容量和媒体容量分开管理；媒体首期一位专家一个有效通话，待接来电和准备中的转接也占用媒体名额。

### 3.4 PostgreSQL 约束需要重新设计

`aimeet` 使用 PostgreSQL 的部分唯一索引、`pg_advisory_xact_lock`、Identity 和带时区字段。不能把 Alembic SQL 直接加入 MySQL Liquibase。

新实现采用 MySQL 行锁、唯一键和事务保护。活动人工请求可使用生成列加唯一约束或显式占用记录；具体 DDL 在 B0 验证，不能只靠 Controller 先查再插。统一保存 UTC 时间，API 输出包含时区的时间戳。并发测试必须运行在真实隔离 MySQL 上。

### 3.5 标注数据不绑定前端框架

`aimeet` 当前校验 `type=excalidraw`，前端编辑器是 React 组件；原规划建议 Vue 工作台使用 Konva。首期新契约采用 `format=annotation-v1`、`frame_id`、画布宽高、规范化坐标及白名单图形，支持箭头、圆圈、矩形、文字、手绘。

迁移来源的旧 Excalidraw JSON 可作为 `legacy_excalidraw` 只读归档格式保存；格式转换单独验证，不承诺任意旧场景都能在 Vue 中继续编辑。Konva 或其他编辑器需输出统一格式；原始帧、场景和成图均独立保存。后端拒绝外部 URL、嵌入脚本、任意图片资源及跨会话 frame 引用。

### 3.6 媒体切换与 AI 架构

首版维持原规划：AI 实时连接与真人 LiveKit 连接分开，转人工时保留 Conversation、切换媒体连接。`aimeet` 的“同 LiveKit 房间换 AI/真人”依赖显式派发的 AI Worker 与 DashScope 桥接，属于后续可选升级，不能直接套用为首版前提。

专家之间的真人转接可复用同一房间，目标接听并完成授权和媒体准备后再切换主负责人；超时或拒绝时保留原服务。Token 到期不等于已入房参与者离开，撤权需要服务端移除参与者、拒绝旧代次重新授权并处理签名 webhook。

## 4 统一状态与权限

### 4.1 区分服务模式 请求状态和媒体状态

| 对象 | 状态与语义 |
| --- | --- |
| Conversation | 沿用 `AI_ACTIVE`、`HUMAN_REQUESTED`、`HUMAN_QUEUED`、`HUMAN_ASSIGNED`、`HUMAN_ACTIVE`、`HUMAN_WAITING_USER`、`EXPERT_PENDING`、`RESOLVED` |
| SupportRequest | requested、queued、assigned、active、waiting_user、resolved、canceled、offline_message；终态保留记录 |
| Transfer | pending、accepted、preparing、completed、rejected、expired、canceled、failed；准备时原 assignment 仍有效 |
| MediaSession | invited、accepted、connecting、connected、reconnecting、ended、failed；连接状态不决定人工服务终态 |
| AI run | queued、running、streaming、completed、failed、canceled；保存运行代次和当前输出消息 ID |

`request_human` 成功的同一事务中创建或返回现有活动请求、更新 Conversation 并递增 `control_epoch`。AI worker 在每次入队、输出 delta、完成消息、调用工具和回调落库前检查 mode 与 epoch；申请人工前已启动但随后到达的 AI 输出不得继续作为正式答案发布。

后台运行中的 AI 辅助建议须使用独立内部可见数据与授权，仅在客服明确发送后转为用户可见消息；不能把内部建议写成公共消息再由前端隐藏。

### 4.2 账号与资源授权

- 复用 `sys_user` 与现有 Shiro 身份，增加客服、专家、主管的细粒度权限，不直接接受 `sender_type`、客服 ID 或租户 ID。
- 统一 `ConversationAccessService`，供消息、事件、附件、标注和媒体使用。它区分本人、当前分配、历史服务者和有明确资源范围的主管；具有菜单权限仍要校验资源范围。
- 离任专家失去写入、Token、挂断及转派权限。新平台默认将历史只读范围固定在交接时的 `read_through_seq`；更广售后档案授权另行记录。该范围比原项目的参与者历史读取更明确。
- 当前身份没有可信租户和项目域，继续拒绝客户端自报这些字段；完成可信来源映射后再开放项目、设备和客户范围。
- 来源 App 的 `(issuer, external_subject)` 建立显式映射；手机号不能用作自动合并或历史读取凭据。现有大冷总管 SSO v1 保持原协议，新增小智接入使用独立 issuer 与签名密钥。
- `aimeet` Cookie 会话、manager-api Bearer Token 和 LiveKit Token 职责不同；不共用密钥，也不把一种 Token 直接当成另一种凭据。

## 5 数据模型与迁移边界

以下是分批设计范围，不在一次迁移中预建全部表。

| 数据 | 主要内容 | 批次 |
| --- | --- | --- |
| 扩展 `ai_conversation` | control_epoch、当前活动人工请求引用；已有 state 与计数保持兼容 | B0 B1 |
| 扩展 `ai_message` | 真实 sender 类型、AI 运行引用、reply_to、model、终结状态与更新时间；沿用 parts 和序号 | B1 B2 |
| `support_agent_profile` | sys_user_id、技能、接单开关、文字及媒体容量、可信范围引用 | B1 |
| `support_request` | conversation、请求模式、目标专家或组、优先级、状态、等待及终结时间、幂等键 | B1 |
| `support_assignment` | 当前/历史负责人、分配和释放时间、交接截止消息序号、转派来源 | B1 |
| 统一通知 outbox | event_id、通道、尝试次数、下次执行时间、完成/失败状态；不复制消息正文 | B1 |
| `ai_run`、`ai_model_usage` | 输入消息、epoch、运行 lease、模型、耗时、错误、真实 usage | B2 |
| `ai_attachment`、`ai_media_job` | 原始与派生对象、所有者、消息关联、媒体元数据、校验/处理状态 | B3 |
| `support_frame`、`support_annotation` | 来源媒体/通话、视频时间点、原图、场景格式、成图、作者、幂等 | B4 |
| `support_media_session`、`support_transfer` | 房间、邀请和同意、参与者代次、转接准备/切换、清理重试 | B5 |
| 外部身份映射与历史导入映射 | 可信 issuer/subject、撤销状态、来源项目/原始记录 ID | B6 或单独迁移 |

表设计以当前分支复查结果为准。将 `aimeet` 的 Python rule 迁移为 Java Service 时，同步移植相应参数化测试。旧 `ai_agent_chat_history` 与 `aimeet` 数据库均不执行破坏性变更。

历史数据首期保持原系统可查，不默认整库导入。后续若迁移，先输出只读清单和 dry run，明确账号映射、媒体可访问性、幂等导入键、来源 ID 和原始序号。旧 AI、人工两条消息流没有共同序号，不能仅按时间戳拼接并声称恢复了原始全序。

## 6 对外接口的实施次序

现有 `/api/v1/ai/conversations`、`messages`、JSON `events` 与 `{code,msg,data}` 保持兼容。下表是待实现接口，不代表现有 OpenAPI 已开放这些能力。B0 通过后以单独版本的后端契约发布给前端，禁止将规划组件直接当作可调用接口。

| 批次 | 方法与路径 | 说明 |
| --- | --- | --- |
| B1 | POST `/api/v1/ai/conversations/{id}/handoff` | 用户申请人工；重复同请求返回原记录 |
| B1 | POST `/api/v1/support/requests/{id}/cancel` | 本人取消尚未接管请求，AI 恢复由明确结果决定 |
| B1 | GET `/api/v1/support/queue` | 按权限查询可接队列，游标分页 |
| B1 | POST `/api/v1/support/requests/{id}/claim` | 原子接单与容量检查 |
| B1 | POST `/api/v1/support/requests/{id}/messages` | 当前客服/专家回复，同一 ai_message 账本 |
| B1 | POST `/api/v1/support/requests/{id}/resolve` | 完成人工服务；不隐式恢复 AI |
| B1 | POST `/api/v1/ai/conversations/{id}/resume-ai` | 用户明确恢复 AI，递增控制代次 |
| B1 | POST `/api/v1/support/presence` | 在线、心跳、暂停接单；Redis 状态不替代持久分配 |
| B2 | GET `/api/v1/ai/conversations/{id}/stream` | 新增认证 SSE，保留原 JSON `/events`；可按事件序号恢复 |
| B3 | POST `/api/v1/ai/uploads/init`、`uploads/{id}/complete` | 私有上传授权和完成校验 |
| B3 | GET `/api/v1/ai/attachments/{id}/access` | 检查会话权限后返回短期读取能力 |
| B4 | POST `/api/v1/support/frames`、`annotations` | 保存帧、向量场景和成图；同事务关联最终回复 |
| B5 | POST `/api/v1/support/requests/{id}/calls` | 媒体邀请，和人工责任状态分离 |
| B5 | POST `/api/v1/support/calls/{id}/accept`、`token`、`end` | 用户同意、限定房间授权、结束媒体 |
| B5 | POST `/api/v1/support/requests/{id}/transfer` | 发起专家转接，接受/准备/取消使用 Transfer 子资源 |

SSE 由认证请求客户端读取，继续发送 Authorization；不把长效 Bearer Token 塞入 URL。各端返回游标使用统一约定；后续要解决 Java long 超过 JS 安全整数范围时的编码，不能在现有数字游标上静默改类型。

内部 Python 任务至少带 `conversation_id`、`run_id/job_id`、`control_epoch`、服务端生成的幂等 ID、trace_id 和明确输入引用。worker 只能回调已 claim 的任务，内部身份不得等价于跨会话任意读写授权。

## 7 开发批次与验收

### B0 冻结后端契约与并发边界

先提交客服/媒体状态、细粒度权限、错误码、事件、身份映射和 DTO 草案。复查当前 MySQL 结构，确认活动请求唯一性、接单容量、锁顺序及 MySQL 约束方案。统一 ConversationAccessService 与控制代次设计，保留现有本人访问和错误语义。

验收：原 API 契约不变；新增草案标记 planned；用户、现任客服、离任专家与主管权限矩阵可执行；真实隔离 MySQL 中证明活动请求和容量约束可用。前端无需等待其余管理页面迁移，就能依据草案准备工作台。

### B1 人工文字服务与队列

先实现用户申请、重复申请、排队/离线留言、接单、容量、人工回复、结束和明确恢复 AI，再接在线心跳与持久通知。人工请求不申请麦克风/摄像头，不要求 LiveKit 才能回复文字。

验收：20 个并发申请只生成一个活动请求；多个客服抢同一单只有一个主要负责人；同一专家跨会话抢单也不超容量；客服看完整已授权历史并写入同一 conversation；越权和离任写入被拒；刷新后队列和历史一致。SLA 先记录等待、首个有效人工响应、处理时长及超时，不提供虚构预计接通时间。

### B2 完成 AI 文字执行与事件流

通过受控内部接口接入 Python 模型执行，复用现有百炼配置/模型适配。实现任务 claim/lease、取消、失败状态、流式输出、终结消息、真实用量与旧 ESP32 历史只读映射。与 B1 共用 control_epoch；人工申请提交后拒绝旧 AI 任务迟到回写。

验收：文字多轮、断线补拉、超时重试、模型限流和进程重启恢复可测；人工申请与 AI token/最终答案竞态不出现双主体正式回复；模拟测试与已配置模型的真实请求分开记录。

### B3 私有附件与媒体任务

先接图片和文本/PDF，再接视频和录音异步任务。复用 aimeet 文件头与图片解码校验规则，但将原 8 MiB base64 上传改成授权上传/流式处理，遵循本平台图片、视频和录音的分级限额。原始证据对象和去元数据后的展示派生图分开保存并授权访问；Office/ZIP 的复杂校验后置。

验收：跨会话附件拒绝；重复上传和关联幂等；文件头、MIME、大小、像素、时长与畸形文件校验通过；对象存储故障不产生已可用附件；未被消息引用的暂存对象到期回收，数据库和对象存储非原子提交有补偿任务。

### B4 静态截图与标注回传

先照片与上传视频截图，实时画面截图跟随 B5。迁移 aimeet 的 frame 绑定、场景白名单、不可引入外部资源及原图不覆盖规则，提供 annotation-v1。客服保存后可作为回复附件发送，草稿保存不等于向用户发送。

验收：指定视频时间点的 frame 来源可追溯；原图、场景、成图均鉴权；箭头/圆圈/文字坐标跨分辨率保持一致；越权引用拒绝；保存/发送失败可同 ID 重试；AI 标注建议保持待确认。

### B5 真人音视频与专家转接

接入 LiveKit 授权、签名 webhook、受邀加入、断线重连、媒体状态和持久清理。先完成 1 对 1，再做同房间真人专家转接。复用 aimeet 准备新服务后切换、移除旧身份、保留原服务失败回退的规则。

验收：未同意不传媒体；拒绝邀请后可继续文字；挂断媒体仍保留人工对话；原专家在目标就绪前可继续服务，失败不丢原服务；切换后旧身份被撤销且不能重入；迟到 webhook 不复活旧通话；30 分钟、Wi-Fi/蜂窝切换与实际终端验收单列。真实手机号外呼不在本批范围。

### B6 外部 App 与业务接入

完成现有 Laravel/Kuikly 身份与新平台的可信映射，再接设备、工单、知识库和远程指导记录。可复用既有大冷总管 SSO 签名测试向量与撤销测试，但保持原接口与会话独立。业务先只读，正式工单提交和设备控制按原规划的授权边界执行。

验收：票据短期单次、重放拒绝、来源会话撤销有效、不按手机号合并；项目/设备范围可信；转接摘要区分已确认事实与 AI 推测。模拟计费、支付、邀请获客和专家注册审批作为后续运营任务，不阻塞远程指导主流程。

## 8 原规划的调整

保留根目录完整 V1 规划原文，本计划作为基于现有代码的实施补充。

- 原 P1 的 AI 执行与流式部分尚未完成，对应 B2；原 P4 的人工文字能力前移到 B1，优先复用 aimeet 的规则。
- 原 P2、P5、P6 分别对应 B3、B4、B5，保留可独立验收和回退的边界。
- 原 P3 的 Omni 实时能力在 B2 后做独立技术验证。评估直接实时连接与 aimeet LiveKit AI Worker 两条路径后再固定方案，不同时建设两套生产人机媒体通道。
- 原 P7 业务集成对应 B6；原 P8 真实终端、网络与安全试点仍是正式交付门槛。
- 原 Konva 建议调整为前端可选实现，后端固定 annotation-v1；旧 Excalidraw 归档单独处理。

批次是开发依赖关系，不是对团队人数、日历工期或复用比例的承诺。每批记录代码、迁移、契约、测试和回退开关，实际通过后再推进下一批。

## 9 与 Vue 3 任务的协作边界

| 责任 | 目录与约束 |
| --- | --- |
| 前端任务 `server-vue3` | 负责 `main/manager-web-next` 和候选前端测试、构建与专属预览入口；继续使用同一 Dev API |
| 本后端任务 | 负责 `main/manager-api`、Python 服务适配、后端专用文档与增量迁移 |
| 共同契约 | 现有 OPENAPI/MESSAGE_SCHEMA 保持有效；新接口先提交独立草案与示例，锁定后加入实际 OpenAPI |
| 共享文件 | `dev/manage.py`、Dev Compose、统一部署说明由实际修改者先核对最新版本，避免并行覆盖或重启对方测试环境 |
| Git | 都基于 Dev，按文件范围提交；不使用全目录暂存、强推或重置来覆盖另一任务的工作 |

本轮只增加后端整合计划，不修改前端目录、运行服务、旧专家系统或业务数据库。

## 10 下一次后端开发的第一组提交

1. **契约提交**：SupportRequest/Assignment、权限矩阵、错误与事件草案，以及 AI 运行代次规则；现有 API 不改名、不改响应封装。
2. **数据与权限提交**：增量表、ConversationAccessService、活动请求唯一性、角色与资源范围检查、MySQL 并发和升级测试。
3. **人工服务提交**：申请/取消/队列/接单/回复/结束/明确恢复 AI，附自动化流程与无前端依赖的可复现客户端；新增功能开关默认关闭。
4. **AI 执行提交**：受控 Python 执行、lease、SSE、取消/失败与真实用量，完成与人工接管的并发测试后再接真实模型验收。

第一可用验收路径：已有 Conversation → 用户申请人工 → 排队或指定专家 → 一名客服接单 → 读取此前记录并回复 → 用户刷新仍在同一段历史中 → 人工结束 → 用户明确恢复 AI。附件、标注和媒体连接在该路径稳定后逐项加入。
