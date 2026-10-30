# 状态和事件契约

## 本批已实现

- Conversation 初始 `AI_ACTIVE`，本批没有状态修改入口。
- 用户消息保存为 `accepted`，只表示持久化成功，不表示模型执行或回复完成。
- `conversation.created` 是每个对话的事件序号 1。
- `message.accepted` 包含 `message_id` 与消息 `seq`。
- 事件具有 `event_id`、`conversation_id`、`sequence`、`type`、`data`、`timestamp`。
- 事件序号与消息序号分别递增；创建对话本身占一个事件序号。
- `GET /events?after=N` 返回大于 N 的持久事件；客户端用 `event_id` 去重并保存最后成功消费的 `sequence`。

## 后续阶段的目标，当前不能调用

消息执行：`accepted -> processing -> streaming -> completed`，以及 `failed/canceled`。

```text
AI_ACTIVE -> HUMAN_REQUESTED -> HUMAN_QUEUED -> HUMAN_ASSIGNED -> HUMAN_ACTIVE
HUMAN_QUEUED -> OFFLINE_MESSAGE
HUMAN_QUEUED --用户取消--> AI_ACTIVE
HUMAN_ACTIVE -> EXPERT_PENDING -> HUMAN_ACTIVE
HUMAN_ACTIVE -> HUMAN_WAITING_USER -> HUMAN_ACTIVE
HUMAN_ACTIVE -> RESOLVED --明确恢复--> AI_ACTIVE
```

人工请求必须暂停 AI 自动回答；接单必须原子化；结束人工不能自动偷偷恢复 AI。
AI 与真人媒体连接分别管理，切换时保留 Conversation ID，开启用户媒体需用户同意。

后续事件：`assistant.text.delta`、`assistant.message.completed`、`media.processing`、`media.completed`、`support.requested`、`support.queued`、`support.assigned`、`support.taken_over`、`support.message.created`、`support.call.invited`、`support.call.connected`、`support.resolved`、`ai.resumed`、`tool.confirmation_required`。
