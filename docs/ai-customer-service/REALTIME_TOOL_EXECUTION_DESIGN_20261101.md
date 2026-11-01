# P1 实时 Function Calling：执行设计与当前安全边界

日期：2026-11-01。本次只交付明确的禁用状态、拒绝边界和测试；**实时工具执行未启用、未完成**。没有修改 Java、数据库、Dev 私有配置或设备同意状态，也没有真实业务 API 或物理通话验收。

## 1. 官方能力与本仓状态

阿里云当前文档确认 Qwen3.8-Omni-Flash-Realtime 支持自定义 Function Calling；联网搜索与 tools 不能同时开启。此为提供方文档能力，不是本 Worker 的实测或启用声明。[官方模型说明](https://help.aliyun.com/zh/model-studio/realtime)

工具声明放在 `session.tools`，Function 元素采用 `{"type":"function","function":{"name":...,"parameters":...}}`。工具结果以 `conversation.item.create`、`item.type=function_call_output`、原 `call_id` 和字符串 `output` 回填；之后仍需显式 `response.create`，包括 VAD 模式。[官方客户端事件](https://help.aliyun.com/zh/model-studio/client-events)

提供方事件参考包含 Function Calling 的流式参数及完成事件；执行设计必须绑定 response/item/call 身份，不能把一段文本 JSON 当成已授权调用。[官方服务端事件](https://help.aliyun.com/zh/model-studio/server-events)

本仓当前仍发送 `tools: []`、`enable_search: false`，不暴露 MCP、任意 HTTP 或设备控制。`Provider.tool_capability()` 只返回：

```json
{
  "model": "qwen3.8-omni-flash-realtime",
  "provider_function_calling": "documented",
  "worker_function_calling": "disabled",
  "error_code": "UNSUPPORTED_TOOL"
}
```

这不是网络能力探测，也不向当前音频 WebSocket 增加新字段。未提供 tools 或明确空数组时，既有 manual/server_vad 会话配置不变；非空或错误类型声明明确拒绝，不静默忽略后声称工具可用。

## 2. 本次可合并的安全补齐

- 新 `realtime_worker/tool_contract.py` 集中描述状态和识别工具信封，只为拒绝；不解析或执行参数，不合成工具结果。
- `Provider.session_update(..., tools=None)` 保持默认配置；非空/错误类型 tools 抛 `UNSUPPORTED_TOOL`。
- 已消费的 manager task 若携带非空 tools，在连接 provider 前按原有 `session.error` 路径终止。浏览器只收到错误码和清音要求，没有函数名、参数或业务正文。
- provider 的函数参数事件、MCP 事件、工具 output item、`function_call_output` 回声、带工具的 response output，以及 provider session 中意外启用的 tools 均明确拒绝。
- provider 事件仍先执行既有 authority 检查；失租/撤权优先终止，不为迟到工具事件增加回调或播放。

既有零工具语音、ASR、图像、VAD、注入 ACK、手动打断、序号去重、心跳、租约和同意规则不变。客户端仍不能发送任意 `conversation.item.create` 或 Function 结果。

## 3. 为什么不能直接调用普通 ai_run 工具端点

普通 `AgentToolService` 的 `prepareRun`、`fence` 和账本绑定真实 `ai_run`、run attempt、run lease 与 conversation epoch。实时会话是 `ai_realtime_session` 和 provider response 的生命周期，不能伪造业务 run 或创建占位 ai_run 来绕过这些前提。

现有 `RealtimeModels.ProviderEvent` 没有提议/工具 ACK 结构，Java `providerEvent` 只允许既有明确事件。`response.completed` 会完成 assistant 占位；传 `tool_calls` 还会终止会话。它不能承担“模型此步等待工具”的语义。当前禁用分支不额外宣称保存被拒工具 response 的 usage；未来必须增加非终态的真实 usage 记录路径。

当前 Python Session 以单一队列串行处理 provider/client/heartbeat。直接在该循环 `await` 30 秒工具 HTTP，会阻塞约 2 秒心跳、语音打断和输入处理，因此也不能简单复制普通文本 Worker 的执行循环。

## 4. Java 最小协作改造（后续，尚未实现）

### 4.1 共用工具核心，独立实时账本

抽取/复用现有 `AgentToolRegistry` 名称白名单、严格参数 schema、只读 adapter、来源配置指纹、字段裁剪、来源证据、预算与审计逻辑。实际调用从当前可信 Principal 恢复，而非 Worker 上报 user/tenant/scope。不要复用普通 ai_run 的 fence 方法。

新增实时 binding/call/attempt 账本，或经独立评审后的通用 execution context；首个实现优先独立实时表，避免破坏已有 ai_run 外键和普通对话行为。最少关联：

| 绑定/记录 | 必需身份与边界 |
| --- | --- |
| 实时授权 binding | conversation、realtime session、provider session、owner、可信 source/token ID/hash、固定 tenant/scope、来源配置指纹 |
| 调用 | session、provider response/item/call ID、工具版本、参数摘要、轮次、状态、真实结果摘要/引用 |
| 尝试 | worker lease hash、conversation epoch、turn generation、开始/结束时间、真实耗时、错误、结果是否丢弃 |
| 提供方步骤 | 逻辑用户轮次及同一 assistant message ID、response ID、request/event 身份、真实 usage、完成原因、是否等待工具/取消/最终答复 |

`source/token` 必须来自已经验证的实时客户绑定；同一账号的新 Bearer 不能接管。合法同 ID 续期只改变有效期，比较不可变 binding，期限由新鲜鉴权读取。执行前、来源响应后、结果回填前、续答前和客户发布前都重新验证。

锁顺序沿现有 conversation → session → call，外部网络 IO 在短事务外；不要持有会话锁后再申请 source 行锁。复用既有独立、限时的来源授权读取，避免池耗尽与锁顺序反转。

### 4.2 必需的新 turn generation

conversation epoch 只表示 AI/人工等控制权变化，不区分同一会话中的新语音。Java 需新增持久的 `turn_generation`，以明确协议处理 `input.started`、新文本注入、用户打断和后续输入。

例如 VAD `speech_started` 到来时，Worker 先通过受租约保护的新 canonical 事件令 Java 递增 generation、废弃上一代工具；不能等 speech commit 后才失效旧结果。generation、response ID、call ID 一起作为 reserve/dispatch/accept/result-ACK/continue 的 fence。旧结果可以留审计，不得进入新语音上下文。

具体重复事件、迟到 speech 事件与注入排列顺序应先冻结并测试；浏览器不能自行报一个更高 generation 来获权。

### 4.3 拟议内部端点（名称尚未冻结）

仅在独立 `X-Realtime-Worker-Key` 认证下追加精确实时方法，例如：

```text
POST /internal/realtime-worker/sessions/{session_id}/tool-proposals
{
  lease_token, control_epoch, turn_generation,
  provider_session_id, response_id, round,
  calls: [{provider_item_id, provider_call_id, name, arguments: object}]
}
```

Java 从 session 恢复可信执行上下文，使用 call ID + 参数摘要 + 身份绑定进行幂等检查。返回的是明确的接受/执行状态与已裁剪结果；相同 call ID 的身份/参数冲突失败。不得提供通用 URL/function/SQL 执行入口，App 不直接访问该端点。

还需精确的 result accepted/ACK、response-step usage、continue-authorized canonical 事件；它们保持现有序号、去重和重试不可变正文规则。待结果不是 assistant completed，不能先公开“已完成”再更正。

## 5. Python 最小执行状态机（后续，尚未实现）

```text
response streaming → tool arguments complete → Java proposal accepted
→ waiting tool → authorized result received → function_call_output sent
→ matching provider item ACK → Java continuation authorized
→ explicit response.create → next provider response → final assistant completion
```

同一用户轮次可以有多个 provider response，但只在最终正常答复时完成面向用户的 assistant 逻辑答复。每一步真实 response usage 单独记录；缺报保持 unknown，不能填 0 或推算。多个步骤汇总必须保留不完整状态。

Worker 归并流式参数、核对最终完整参数与片段一致、严格 object JSON（拒绝重复键/NaN/尾随内容），再交给 Java schema/权限校验。call ID/item ID 属不透明标识，采用长度上限和精确匹配，不推导身份。

工具执行只建一个有界异步任务，结果投回现有序列化队列；循环持续处理心跳、输入和取消。禁止无界 task、token 队列或并发业务请求。`busy()` 需覆盖等待工具及结果 ACK，防止文本注入穿越执行中的旧轮次。

发生新 speech、手动取消、转人工、租约失败、来源过期/撤销或 epoch/generation 改变时：取消等待、标记结果丢弃、停止 output ACK/continuation；已完成的只读来源请求保留真实审计，不能因为“查询已完成”继续播报旧答复。`response.cancel` 只在确有活动 response 时发送，保留现有 VAD 不重复 cancel 的规则。

起步预算可复用普通工具当前的严格上限：每用户轮次最多 3 轮、6 调用、4 KiB 参数、32 KiB 结果、30 秒；另需独立整个实时 session 调用/结果/费用上限。具体值由 Java 策略下发、Worker 施加硬上限。预算耗尽以明确未完成状态收尾，不能循环续答或伪造工具数据。

MCP/联网搜索继续禁用。首个实时工具实现仅使用经过 Java 的只读 Function Calling，不把来源服务密钥或管理员凭据交给 provider。

## 6. 验收与推进顺序

当前自动验证包括默认 manual/VAD wire、非空声明在 provider 建连前失败、原生工具/结果回声/MCP 信封明确拒绝、敏感参数不进入 callback/浏览器、失去 authority 后不追加工具错误回调，以及完整原有 realtime suite。

后续先冻结 Java 账本与事件 → 隔离 MySQL 竞态/撤销/新 Bearer 接管测试 → Loopback WSS 完整提议/ACK/续答/打断故障注入 → 授权的真实 provider 技术工具调用 → 真实来源用户/租户/记录验证 → Android 实际音视频与长时会话验收。

真实来源未配置时，技术工具只能返回明确无业务数据的协议证据；业务工具必须 `BUSINESS_NOT_CONFIGURED` 等真实失败。没有真实 Laravel producer、业务 API 或设备证据时，不宣称实时业务工具或 30 分钟实际通话完成。
