# 人工文字服务 B1 接口

以下为当前控制器契约，集成与并发验证结果见 [验证记录](SUPPORT_B1_VALIDATION.md)。需要同时启用 `features.conversation-enabled` 和 `features.support-handoff-enabled`；两者默认关闭。接口使用现有 token 认证，未认证返回 HTTP 401；业务结果使用现有 `Result` 封装。ID 为 32 位小写十六进制字符串。

| 方法与路径 | 请求体或参数 | 用途 |
| --- | --- | --- |
| `POST /api/v1/ai/conversations/{id}/handoff` | `{client_request_id, requested_mode: "text", target_agent_user_id}`，目标可为 null | 会话所有者请求人工服务；相同客户端请求 ID 重试须使用相同参数 |
| `GET /api/v1/support/me` | 无 | 已认证启用账号读取自己的客服档案；无档案或档案禁用时返回 data: null，不刷新在线租约 |
| `GET /api/v1/support/assigned` | `before=0&limit=50`，limit 范围 1–100 | 恢复当前客服活动接待，按 list_sequence 倒序；需 `support:queue:read` |
| `GET /api/v1/support/agents` | `before=0&limit=50`，limit 范围 1–100 | 管理员分页列出已有客服档案，按 user_id 倒序；需 `support:agents:manage` 及启用的超级管理员账号 |
| `GET /api/v1/support/queue` | `before=0&limit=50`，limit 范围 1–100 | 客服查询可认领队列，游标按 list_sequence 倒序 |
| `GET /api/v1/support/requests/{id}` | 无 | 查询请求 |
| `POST /api/v1/support/requests/{id}/claim` | 空对象或无请求体 | 认领请求 |
| `POST /api/v1/support/requests/{id}/messages` | `{client_message_id, parts}` | 当前指派客服发送文字消息，parts 仅支持 text |
| `POST /api/v1/support/requests/{id}/resolve` | 可选 `{reason}` | 当前指派客服结束服务 |
| `POST /api/v1/support/requests/{id}/cancel` | 可选 `{reason}` | 所有者取消排队中的请求 |
| `POST /api/v1/support/requests/{id}/requeue` | 可选 `{reason}` | 当前指派客服或管理员显式重新排队 |
| `POST /api/v1/ai/conversations/{id}/resume-ai` | 空对象或无请求体 | 所有者在没有活动人工请求时恢复 AI 状态 |
| `PUT /api/v1/support/agents/{userId}` | `{role, enabled, capacity_text}` | 管理员设置客服档案；role 为 `support_agent` 或 `expert`，容量 1–20 |
| `POST /api/v1/support/presence` | `{accepting, service_type}` | 按客服或专家业务独立更新接单开关与45秒在线租约 |

队列接单要求有效账号、启用的客服档案、45 秒内的在线租约及 accepting；用户不能认领自己的请求。租约过期不会自动撤销已有指派，恢复由客服或管理员显式重新排队。消息发送要求当前有效指派；历史客服只能读取释放时序号之前的会话消息和事件，不能查询当前请求或会话详情；重新排队时同样记录截断位置。功能关闭后已有历史账本授权仍有效，数据库迁移始终安装。管理员可管理客服档案，但不会因此自动获得任意对话读取权限；资源访问由服务端另行校验。

读取列表统一返回 `{items, next_cursor, has_more}`，下一页传 `before=next_cursor`。`assigned` 只返回 status 为 active、当前指派人是本人且存在未释放 assignment 的请求，返回已有 SupportRequest 字段；历史指派、已解决或重新排队请求不会出现在此列表。在线租约过期不会隐藏仍有效的接待。管理员档案列表在已有 Agent 字段上增加 `username`、`account_enabled`，包含已禁用档案或账号以便管理；该列表不包含对话数据，也不授予对话访问权限。管理更新仍只支持启用账号。`me` 返回自己的 Agent 字段（含 `presence_expires_at`）或 null，不授予任何客服业务权限；禁用账号返回 403。

人工文字和事件轮询已经实现；AI 实际执行、SSE 推送和媒体能力尚未实现。`resume-ai` 变更会话状态，本身不代表已经执行 AI。

响应中的长整型 ID、序号和游标以十进制字符串返回，例如 `last_seq: "3"`；可空值保留为 `null`，时间为 ISO 日期时间字符串。JavaScript 客户端应保留长 ID 的字符串形式，不要转换为 `Number`。查询参数 `before`、`after` 仍为整数；请求中的 `target_agent_user_id` 可传整数、十进制字符串或 `null`。


## B3 现行附件扩展

上文表格和“尚未实现”是 B1 初始交付时的历史边界。启用 B3 后，当前获指派且可写的客服可在原消息接口按顺序发送 text 和 `{type:"image"|"video"|"audio"|"file",attachment_id}`；附件必须 ready、属于同一会话和当前发送者，且未关联其他消息。每消息最多 10 个附件、其中最多一个视频和一个音频。上传、访问和状态契约见 [B3 实施契约](B3_IMPLEMENTATION_CONTRACT.md) 与 [OPENAPI](OPENAPI.yaml)，实测边界见 [B3 验证记录](B3_VALIDATION.md)。

附件和媒体任务 ID 是正 Java long 十进制字符串，不能按上文 B1 会话/请求的 32 位十六进制 ID 理解；会话 ID 格式不变。未关联附件仅上传者可读；关联后仍遵循会话授权与历史客服的消息截止序号，管理员不自动获得跨客户权限。保存附件或草稿不等于发送消息。
# 2026-11-02 增补：客服、专家服务类型与多角色

本节为 Dev 本轮增量契约，旧接口和字段继续兼容。

- 员工档案 `/api/v1/support/me` 和 `/api/v1/support/agents` 增加 `roles` 数组，值为 `support_agent`、`expert` 或两者。原 `role` 保留为兼容主角色。
- 更新档案可传 `roles`；显式数组必须非空且包含 `role`。旧调用省略时按 `[role]` 更新。所有账号ID、请求ID、游标保持字符串精度。
- 服务请求增加 `service_type`。`GET /api/v1/support/queue`、`/assigned` 可传 `service_type=support_agent|expert`，省略时也只返回员工获准的服务类型。文字接待容量仍按账号共享；接待开关与在线租约按业务独立计算。
- 客户 handoff 可传 `service_type`。未指定目标、未指定类型时进入客服队列；指定目标且未指定类型时使用目标的兼容主角色。显式类型必须与目标具备的角色一致。
- 内部员工转交：`POST /api/v1/support/requests/{id}/transfer`，JSON 示例：`{"client_request_id":"transfer-unique-1","service_type":"expert","reason":"需要远程技术指导"}`。可额外指定字符串 `target_agent_user_id`；原因最多500字。
- 转交只允许当前有效接待人执行，目标必须为另一种服务类型。响应为同一请求的排队状态；相同幂等键和相同内容返回原结果，内容变化返回409。目标人员仍需显式接单。
- 转交保留同一个 `conversation_id` 和消息历史，释放原分配、撤销原媒体授权并写入清理任务。HTTP成功不等于 LiveKit 物理清退已完成，仍须检查媒体 drain 状态。
- 回复身份由服务端当前请求类型与分配共同决定。客户端不能靠请求字段把普通用户消息改成专家回复。
- 外部 App 令牌仍只访问原客户允许列表，不开放员工队列、档案、转交接口；客户可读取自己的服务状态、消息及已发布私有标注附件。

详细菜单、共享媒体工作台、迁移和验收记录见 [本轮计划与交付](./CONVERSATION_DESKS_ITERATION_20261102.md)。

## 2026-11-04：App 专家目录与展示资料

- `PUT /api/v1/support/agents/{userId}` 可增加 `profile`：`display_name`（80 字）、`title`（120 字）、`bio`（1000 字）、`specialties`（最多 12 项，每项 40 字）、`listed`、`avatar_url`。省略整个 `profile` 保留已有资料；资料验证与角色更新共用事务。名称不修改登录账号。资料保存在新增的 `support_public_profile` 表，迁移不修改已有会话。
- `avatar_url` 接受 HTTP(S) 图片地址、当前账号已保存的头像路径，或不超过 512KiB 的 PNG/JPEG data URL。管理页将图片缩小到最多 512px 后提交；服务端验证尺寸、解码并重新编码为 PNG，独立保存图片内容，不把 base64 放进目录响应。头像由随机版本路径 `GET /api/v1/support/avatars/{userId}/{version}` 公开读取；账号或人员档案禁用后返回 404。
- `GET /api/v1/support/experts?before=0&limit=50` 面向已认证客户，包括外部 App 身份。响应仍为游标分页，`user_id` 和游标为十进制字符串。返回展示名称、头像路径、职称、简介、领域和 `online` / `busy`，不返回登录名、角色、权限或内部队列。仅展示启用账号中拥有专家角色且未隐藏的人员；旧档案回退到账号名。离线人员仍在目录，在线由接待心跳判断，忙碌由共享文字接待容量计算。
- App 已将首页专家入口改为该目录。点击资料不会创建咨询，明确发起后按 `service_type=expert` 与 `target_agent_user_id` 定向 handoff，复用当前小智会话；没有当前会话时创建一条。客服入口同样使用小智 handoff。返回 AI 时，排队请求调用 cancel、活动请求调用 end，再恢复原会话 AI，保留草稿。
- 小智专家视频邀请与客服邀请均可由 App 接收，来电名称使用展示资料。当前由接待台发起视频，App 接听；App 主动呼叫专家的媒体 UI 不在本次实现范围。Aimeet 资料与咨询数据不自动迁移或删除。

验证：13 项隔离 MySQL 支持服务测试、26 项支持服务/目录单测与接口测试、17 项外部邀请/协议/权限测试通过；管理页 23 项定向前端测试、构建与六语言键检查通过。真实 Dev 管理页已验证头像上传/读取、名称和领域同步、隐藏与恢复展示。Android 全量 1129 项（1 跳过）及共享 Android/iOS 编译通过；最终消息发送者映射另有定向回归。真人接单与实时音视频仍需双方在线验收。

### 接待状态隔离（2026-11-04）

`POST /api/v1/support/presence` 的 `service_type` 为 `support_agent` 或 `expert`，只更新该业务，且账号必须具备对应角色。兼容旧客户端省略类型时仅更新主角色。`GET /api/v1/support/me` 增加 `service_presence`，例如 `{"support_agent":{"accepting":false,"presence_expires_at":null},"expert":{"accepting":true,"presence_expires_at":"2026-11-04T08:00:00Z"}}`；顶层 `accepting` / `presence_expires_at` 仅表示主角色。心跳只续期已开启的业务，新队列提醒和接单校验使用对应业务状态；暂停新接待不影响已有接待继续回复。App 专家在线状态仅依据专家租约。媒体转接候选接口 `/api/v1/support/media-experts` 可携带同名类型参数，转接接收也校验原请求对应业务。

增量迁移 `202611041600-support-service-presence` 保留旧主角色的接待设置，第二个业务默认关闭，不更改已有会话和指派；禁用档案或撤销某个角色会关闭对应开关，重新授权后需明确开启。

### 消息与实时来电提醒（2026-11-04）

后台统一布局持续读取当前员工获准的队列、活动接待及音视频邀请，管理页面也保留提醒。左侧按最新消息时间排序，显示文字或图片、语音、文件等类型摘要与未读数量；首次进入只取每段接待最后一条历史消息，不把旧历史集中补响。阅读中的当前会话保持安静；点击新消息通知定位到对应业务、会话及最新消息。未读进度以账号和请求为范围保存，仅存游标与计数，不保存消息正文。

普通消息使用单个约120毫秒短音，并按会话合并页面右上角通知；音视频来电采用循环双声铃声与常驻通知，点击进入对应业务与邀请，继续要求明确同意接听，不自动打开麦克风或摄像头。关闭来电通知可对本次来电静音，总提示音开关仍对全部声音生效。支持 Web Locks 的浏览器中，同一账号多个标签页共用一个声音锁，避免重复响铃。页面首次交互或点击提示音按钮后启用声音；退出或切换账号会停止声音并移除提示。

媒体邀请接口响应新增 `request_id`、`service_type`、`mode` 和 `requires_response`。转接目标已明确接受、但仍在等待客户同意时，`requires_response=false`，不再继续响铃。邀请取消、拒绝、超时或接听后撤销常驻提醒；网络异常时也按已知邀请过期时间停止铃声。本功能为已打开后台页面的通知，服务端会话、接待记录和账号数据保持保留。
