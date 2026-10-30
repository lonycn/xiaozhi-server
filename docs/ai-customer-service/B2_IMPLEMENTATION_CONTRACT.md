# B2 实施协作契约

本批在 Dev 接通统一会话的 AI 文字执行、持久流式输出及人工接管停答。复用已配置百炼模型，原 ESP32 服务不切换。默认 `features.ai-text-enabled=false`，须与 `conversation-enabled=true` 同时启用。

## 数据保护

只追加 Liquibase changeset、新表和必要列；不改旧 changeset。不对已有 Dev、原智控台、aimeet 或用户业务库执行 DROP、TRUNCATE、DELETE、重建、重新初始化、覆盖导入或删除数据卷。真实 SQL 测试只运行在本批新建并校验名字的临时测试库。部署前保存 Dev 私有备份，保留现有账号、密码与历史。

## 控制面

Java/MySQL 是唯一权威状态。Python 是独立受控 worker，通过 HTTP 领取任务、心跳和回调；不复制用户表，不直连业务库，不接收前端的 owner/model/key 声明。worker 只在配置固定的模型和服务地址上执行。

用户消息保存成功且会话为 AI_ACTIVE 时，同事务入队一个 ai_run，分配唯一 assistant 消息占位 ID/seq。人工状态下只保存用户消息。相同 client_message_id 重试不重复排队。新用户消息取代尚未结束的旧 AI 运行，旧部分输出保留 canceled 状态。对话/任务锁顺序统一为 conversation -> run -> message；人工已有 profile/account -> conversation 顺序不逆转。

ai_run 状态为 queued/running/streaming/completed/failed/canceled。每会话最多一个活动 AI 任务。持久化 input_message_id、output_message_id、control_epoch、attempt、lease token/owner/expiry、last_chunk_sequence/hash、model、error_code、时间和真实用量。输出只更新同一消息及其 parts/status，不为每个 token 新增消息。租约 token 是每次领取新生成的能力凭据，不返回公共 API。

每次领取、心跳、delta、完成和失败回调都重新校验会话模式、control_epoch、当前 attempt 和未过期租约。handoff/requeue/resume 的控制变化同事务取消失效 AI 任务；迟到回调返回 409，绝不写入正式新答案。进程重启后的过期任务可有限恢复（至多 3 次）；重启输出前有明确 reset 事件，旧租约永久失效。手动重试仅允许当前最新用户输入且没有其他活动任务，不重放被后续用户输入取代的旧问题。

## 公共接口

沿用 Bearer Token、真实 HTTP 错误和 `{code,msg,data}`。ID、长序号及游标继续十进制字符串。请求体严格拒绝未知字段。

- GET `/api/v1/ai/conversations/{id}/runs?before=0&limit=50` -> CursorPage<Run>，按 list_sequence 倒序。
- GET `/api/v1/ai/conversations/{id}/runs/{runId}` -> Run。
- POST `/api/v1/ai/conversations/{id}/runs/{runId}/cancel`，空对象 -> Run；所有者操作。
- POST `/api/v1/ai/conversations/{id}/runs/{runId}/retry`，空对象 -> Run；所有者操作。
- GET `/api/v1/ai/conversations/{id}/stream?after=0`，认证 SSE，支持 Last-Event-ID。`id` 是事件 sequence，`event` 是事件 type，`data` 是现有 Event JSON 信封。服务端定期重新校验资源授权，历史客服只读到释放 cutoff。连接有限时长且心跳，断线按最后已处理序号恢复；前端保留轮询兜底。不把 Bearer Token 放进 URL。

Run 至少含 `run_id, list_sequence, conversation_id, input_message_id, output_message_id, status, control_epoch, attempt, model, last_chunk_sequence, error_code, created_at, updated_at`；不含 lease token、worker key 或 API key。真实 token usage 可为 null，不伪造估算。

事件至少含 `ai.run.queued/started/failed/canceled`、`assistant.message.created/reset`、`assistant.text.delta`、`assistant.message.completed`；data 关联 run_id、message_id、attempt。delta 还含 chunk_sequence、text；reset 要清空旧部分输出。前端按事件 sequence 去重，并根据 message_id 替换或更新已有消息。

## Worker 协议

专用 `X-AI-Worker-Key` 只在服务端私有配置传递，常量时间比较。新增路由仅 `/internal/ai-worker/**`，不能借此开放其他内部或用户 API。所有成功仍用 Result 封装。领取不到任务时 data=null。

- POST `/internal/ai-worker/claim` `{worker_id}` -> Task。
- POST `/internal/ai-worker/runs/{runId}/heartbeat` `{lease_token}` -> `{active:true}`（可附 lease_expires_at）。
- POST `/internal/ai-worker/runs/{runId}/delta` `{lease_token,sequence,text}` -> 确认；sequence 从 1 开始，每 attempt 单调递增，相同末包重试幂等，乱序/不同内容冲突 409。
- POST `/internal/ai-worker/runs/{runId}/complete` `{lease_token,last_sequence,finish_reason,usage,provider_request_id}` -> Run。usage 为 null 或 `{input_tokens,output_tokens,total_tokens}`，均为服务端模型实际报告的非负整数。
- POST `/internal/ai-worker/runs/{runId}/fail` `{lease_token,error_code}` -> Run，错误为脱敏白名单代码，不接受异常堆栈、任意 provider 原文或密钥。

Task 包括 `run_id,conversation_id,input_message_id,output_message_id,control_epoch,attempt,lease_token,lease_seconds,model,messages`，messages 为服务端构建的 role/content 数组，限长并只包括当前输入及其之前的合法上下文。默认租约 45 秒，worker 每 10–15 秒心跳。worker 的模型配置与 Java `ai.execution.model` 必须一致。

Python 独立 worker 使用现有 Python 环境与百炼兼容传输，配置来自私有文件（base_url/api_key/model/workspace_id 等）；不导入音频或 torch 大型依赖，不添加新身份服务。流式调用保留真实 usage；按小批 delta 回调，网络重试复用同一个 sequence/body。409、失效租约或模式改变立即关闭模型流；异步心跳要能打断等待中的模型流。错误与日志不输出消息正文、密钥或授权头。

## 本批验证

Java 事务、并发、epoch/lease fencing、重试、增量迁移、SSE 资源边界；Python 协议/流中止/错误/usage；Vue 3 流式帧解析、断线补拉、消息替换、停止/重试、人工优先、退出清理。真实模型小请求与隔离/替身测试分别记录，已有数据库与设备服务保持独立。
