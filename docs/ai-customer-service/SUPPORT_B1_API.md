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
| `POST /api/v1/support/presence` | `{accepting}` | 客服刷新在线租约及接单状态 |

队列接单要求有效账号、启用的客服档案、45 秒内的在线租约及 accepting；用户不能认领自己的请求。租约过期不会自动撤销已有指派，恢复由客服或管理员显式重新排队。消息发送要求当前有效指派；历史客服只能读取释放时序号之前的会话消息和事件，不能查询当前请求或会话详情；重新排队时同样记录截断位置。功能关闭后已有历史账本授权仍有效，数据库迁移始终安装。管理员可管理客服档案，但不会因此自动获得任意对话读取权限；资源访问由服务端另行校验。

读取列表统一返回 `{items, next_cursor, has_more}`，下一页传 `before=next_cursor`。`assigned` 只返回 status 为 active、当前指派人是本人且存在未释放 assignment 的请求，返回已有 SupportRequest 字段；历史指派、已解决或重新排队请求不会出现在此列表。在线租约过期不会隐藏仍有效的接待。管理员档案列表在已有 Agent 字段上增加 `username`、`account_enabled`，包含已禁用档案或账号以便管理；该列表不包含对话数据，也不授予对话访问权限。管理更新仍只支持启用账号。`me` 返回自己的 Agent 字段（含 `presence_expires_at`）或 null，不授予任何客服业务权限；禁用账号返回 403。

人工文字和事件轮询已经实现；AI 实际执行、SSE 推送和媒体能力尚未实现。`resume-ai` 变更会话状态，本身不代表已经执行 AI。

响应中的长整型 ID、序号和游标以十进制字符串返回，例如 `last_seq: "3"`；可空值保留为 `null`，时间为 ISO 日期时间字符串。JavaScript 客户端应保留长 ID 的字符串形式，不要转换为 `Number`。查询参数 `before`、`after` 仍为整数；请求中的 `target_agent_user_id` 可传整数、十进制字符串或 `null`。
