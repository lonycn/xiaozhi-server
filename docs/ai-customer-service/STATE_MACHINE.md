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

## B1/B2 现行补充

以上“本批/后续阶段”是第一批账本交付时的历史边界；B1 人工契约完整保留在
[SUPPORT_B1_API.md](./SUPPORT_B1_API.md)，B2 实施边界见
[B2_IMPLEMENTATION_CONTRACT.md](./B2_IMPLEMENTATION_CONTRACT.md)，公开 wire 见 [OPENAPI.yaml](./OPENAPI.yaml)。
媒体、Omni、人工音视频及标注仍不由本批文字执行能力提供。

- Conversation 现行状态为 `AI_ACTIVE/HUMAN_QUEUED/HUMAN_ACTIVE/RESOLVED`；人工取消/结束后的恢复仍须明确 `resume-ai`，不得按上方早期目标图自动恢复。
- B2 run 状态为 `queued -> running -> streaming -> completed`，活动态可转 `failed/canceled`；没有正文时也可从 running 直接 completed。
- 初次入队 `attempt=0`，claim 递增至 1；过期恢复或手动重试最多递增至 3。手动 retry 先回 queued、保留旧 attempt/部分输出，下一 claim 发 reset 后开始新输出。
- 用户消息 `accepted` 仅代表已保存；assistant 消息用同一 ID 表达 `queued/running/streaming/completed/failed/canceled`，部分输出在失败/取消后保留。
- 新输入或人工状态/epoch 变化取消原活动 run；每次心跳、delta、complete、fail 均重新检查 mode/epoch/attempt/lease，旧回调 409。
- 新增事件：`ai.run.queued/started/failed/canceled`、`assistant.message.created/reset`、`assistant.text.delta`、`assistant.message.completed`。沿用既有 Event envelope，data 关联 run/message/attempt；delta 另带 chunk_sequence/text。
- SSE `id` 与 JSON `sequence` 都是事件序号。消息 `seq` 和 delta `chunk_sequence` 是其他序号空间；不得互相替换。重连保留十进制字符串精度；按事件序号去重，按 attempt reset 隔离旧输出。
- 旧历史 bridge 始终只读，不自动迁移进统一会话或进入模型上下文。

默认 AI-text 开关关闭；Dev 显式选择开启不改变其他环境默认值。上述是协议与实现说明，不是部署或真实服务验收结论。


## B3 附件与媒体任务现行补充

B3 默认关闭；启用还依赖 conversation 开关。上传初始化固定 conversation 与上传者，原件经 PUT 后为 `uploaded`；complete 幂等排队检查：`pending_upload -> uploaded -> processing -> ready/failed`。未关联附件过保留期变为 `expired`。检查失败且错误可重试、上传者仍可写、原件存在、无活动任务且领取次数少于 3 时可 retry 到 processing；不会覆盖原件。

`inspect_media` 和可选 `audio_transcription` 的任务状态为 `queued -> running -> completed/failed`，claim 最多 3 次，租约失效后的旧回调不能完成。检查成功的音频或带音轨视频即使转写失败，附件仍可 ready，转写任务独立失败。处理失败错误码包括 `INVALID_MEDIA/TYPE_MISMATCH/PIXEL_LIMIT/DURATION_LIMIT/PAGE_LIMIT/PROCESSING_TIMEOUT/PROCESSING_FAILED/STORAGE_UNAVAILABLE/TRANSCRIPTION_NOT_CONFIGURED/PROVIDER_AUTH/PROVIDER_RATE_LIMIT/PROVIDER_TIMEOUT/PROVIDER_ERROR`；仅超时、处理/存储故障、未配置转写及 provider 故障可重试。

已关联附件读取跟随会话授权及离任客服 `linked_seq <= read_through_seq`；未关联仅上传者可读，管理员无跨客户旁路。仅关联后向会话公开附件及媒体事件，避免泄露草稿。发送事务验证附件 ready、同会话、同发送者、未关联或属于同一次幂等消息；一个附件不能跨消息复用。

AI run 的媒体准备发生在 claim 前：转写 queued/running 时等待终态；缺少必要派生物或转写失败为 `MEDIA_INPUT_UNAVAILABLE`。图片使用 display，视频使用带时间点的帧，须显式配置 vision model；未配置或模型能力不可用为 `MODEL_CAPABILITY_UNAVAILABLE`。文件使用提取文本，音频及含音轨视频使用转写；只读取当前输入的派生物。内部协议见 [B3 实施契约](./B3_IMPLEMENTATION_CONTRACT.md) 和 [media worker README](../../main/xiaozhi-server/media_worker/README.md)，内部密钥、租约、存储对象 key 不属于公开响应。
