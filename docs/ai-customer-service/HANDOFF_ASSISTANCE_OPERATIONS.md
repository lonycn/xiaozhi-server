# 内部模型交接摘要：接口与运维

日期：2026-10-31。运行证据与未完成边界见 [验证记录](./HANDOFF_ASSISTANCE_VALIDATION.md)。原九字段确定性摘录仍保留；本模块增加客服明确触发的模型辅助摘要与内部建议。

## 开关与进程

源码默认 `features.support-summary-assistance-enabled: false`。服务与接口同时要求 `features.conversation-enabled`、`features.support-handoff-enabled`、`features.support-summary-assistance-enabled` 为 `true`。普通 AI 文字开关不会隐式开启本模块。

独立 worker 复用已有私有 AI 配置及已配置百炼文字模型，不增加另一份 provider 密钥，不修改普通聊天 worker 的循环：

```sh
cd main/xiaozhi-server
python -m ai_worker.summary --config /absolute/path/to/private-ai-worker.json
```

配置继续使用现有 `Config` 的 `manager_url`、`worker_key`、`worker_id`、`base_url`、`api_key`、`model` 等字段。Java 从 `ai.execution.model` 选择模型；worker 要求任务模型与配置模型一致。请求使用非流式 JSON 模式，输出上限 6000 tokens，关闭思考输出；不使用普通文字聊天的 500-token 默认上限。provider 地址沿用既有百炼 HTTPS 白名单。完整配置、密钥和含私有输入的内容不应复制到公开文档。

当前实际验证模型为 `qwen-flash`，使用 `response_format=json_object`；该模式不保证 schema 合规，不把未验证的 `json_schema` 能力套用到现有模型。固定 prompt 明确 attribution 闭合枚举、逐字段允许值，以及所有新生成 Item 的 `verification_note=null`；服务端验证仍是保存前的强制边界，不自动映射错误枚举、删掉违规字段或调用第二个模型修复。

在仓库根目录使用 Dev 管理入口：

```sh
python3 dev/manage.py summary-status
python3 dev/manage.py summary-enable
python3 dev/manage.py summary-disable
```

`summary-enable --build` 可先构建。启用前检查已构建 JAR 包含真实 `SupportAssistanceService`，普通 AI 已显式启用，并导出已有私有 `dev/.runtime/ai-worker.json`。启动检查配置权限和 Python 的 `openai/httpx/ai_worker.summary` 依赖。管理入口记录独立 summary 设置、日志和 PID/进程身份；停止前核对 PID 身份、模块及配置路径。

启用、禁用都会更新 API 配置并 recreate API；这不是纯前端开关，运行前应安排已有请求的中断窗口。它们不初始化数据库，不清理已有数据或原卷。禁用停止独立 summary worker，保留摘要版本、任务、attempt 和失败记录；关闭后接口不发布。`summary-status` 只说明本地设置及进程身份，不能替代 API、provider 和结果持久化验收。

## 客服接口

以下路径相对于应用 context path，均使用已有登录身份，不接收客户端指定的客服身份。

| 方法与路径 | 请求与结果 |
| --- | --- |
| `GET /api/v1/support/conversations/{id}/summary/assistance` | 返回 `{enabled:true,latest_job:Job或null}`，恢复当前客服、当前 assignment 的最近任务。 |
| `POST /api/v1/support/conversations/{id}/summary/assistance` | `{client_request_id,base_version}`，明确触发生成，返回 Job。 |
| `GET /api/v1/support/conversations/{id}/summary/assistance/{jobId}` | 读取任务、审计信息及内部建议，返回 Job。 |
| `POST /api/v1/support/conversations/{id}/summary/assistance/{jobId}/retry` | 空请求体或 `{}`，显式重试失败任务，返回 Job。 |
| `GET /api/v1/support/conversations/{id}/summary` | 沿用原接口读取摘要历史；成功的模型版本 `origin=model_assisted`。 |
| `PUT /api/v1/support/conversations/{id}/summary` | 沿用原编辑接口及 `base_version`，客服明确保存内部修改。 |

响应沿用 `{code,msg,data}` 包装。Job 使用 snake_case：`id`、`conversation_id`、`status`、`base_version`、`source_through_seq`、`attempt`、`model`、`error_code`、`result_version`、`created_at`、`updated_at`、`usage`、`provider_request_id`、`suggestions`。`usage` 为 `input_tokens/output_tokens/total_tokens`。无结果、usage 或 provider ID 时相应值为空，不能据此补造模型消耗。

建议沿用 Item：`{text,attribution,evidence,verification_note}`。引用为 `{message_id,seq,attachment_id,quote}`；文本引用的 `quote` 必须为消息原文片段，附件引用使用绑定的 `attachment_id` 并令 `quote=null`。建议的归属固定 `ai_inference`，没有客服核验说明。

仅当前、有效客服账户及启用的客服 profile 可以触发和读取辅助任务；会话 owner 即使有管理员身份也不能通过此接口读取。外部身份凭据不允许路由到这些内部接口。任务还绑定发起客服及具体 assignment，转派后的客服不能读取原客服任务；旧客服重新领取同会话也不能借新 assignment 读取旧任务。原摘要历史继续按其独立的 assignment/cutoff 规则授权。

同一会话、客服及大小写精确的 `client_request_id` 幂等；复用该键但更换基础版本或 assignment 返回 409。基础摘要版本已变化返回 `SUMMARY_VERSION_CONFLICT`；存在仍有效的 queued/running 任务时返回 `ASSISTANCE_ALREADY_PENDING`。这些冲突应刷新状态或摘要，不应悄悄生成并覆盖新版本。

## 生成、证据与公开边界

输入是锁定读取的有界快照：最近最多 80 条消息、每条文字部分最多 2000 字，附件只提供已绑定的引用而不声称读取其内容；截断由 `source_truncated` 明示。快照含基础九字段、消息 ID/seq/发送者和截止 seq，并保存 SHA256。这里的附件引用验证不等于模型已理解图片、视频或录音。

模型将用户陈述、客服陈述、附件引用、AI 推测和未知项分开。`verified_facts` 必须原样保留基础版本，模型不能新增或修改客服核验；其他模型条目不能填写客服核验说明。生成的推测只放 `hypotheses`；引用逐项回查会话、消息、seq、发送者、原文和附件绑定，并再次限制为本次模型实际收到的快照。客服编辑可原样保留已生成的推测，不能以手工改文伪造新的 AI 来源。

全部历史文字、历史 AI 输出和附件引用都作为不可信数据提供。固定 system 规则要求忽略其中的指令；请求不提供工具，也没有业务写入、设备控制或客户消息发送调用。模型输出通过服务端结构及证据验证后才保存。该机制验证来源和归属，不保证推断在设备或业务上正确，仍需人工核验。

生成后仅保存内部摘要 revision 与建议。前端“加入回复草稿”是客服明确操作；它不能自动发送，仍需客服检查并通过正常发送动作公开。即使建议引用合法，也不能据此自动启停设备或提交实际工单。

本次真实浏览器已验证：客服明确选择一条建议加入已有回复草稿，未发送；后续新消息使建议过期并从当前建议区移除，但已有草稿保持。客服仍可通过原摘要编辑流程保存新的内部 revision。这里的草稿保持指生成、新消息和摘要编辑期间的状态；显式页面 reload 后由测试工具重新填回草稿，不据此声称跨 reload 自动持久化。

## 异步状态、重试与审计

状态为 `queued/running/completed/failed/stale`。worker 通过既有 `X-AI-Worker-Key` 专用 filter 访问 `/internal/ai-worker/summary/claim` 及 `/{jobId}/heartbeat|complete|fail`。该 key 不创建用户登录身份，也不授予客户或客服 API 权限。

租约为 120 秒，心跳沿用 worker 配置。过期租约可由其他 worker 重新领取；旧 token 被拒绝。claim ACK 不确定时不立即重试 claim；后续以数据库租约到期恢复。回调 ACK 丢失可幂等重试。总计最多三个 attempt，provider 明确失败后须客服显式重试；不会静默切模型、返回模板答案或把故障视作成功。

claim、心跳和完成回调都重新检查当前客服权限、assignment、截止 seq 和预期摘要版本；转派、撤权或任务期间的新消息/摘要编辑会阻断旧结果。无效任务标为 `stale`，不能对原快照重试；应在最新摘要与上下文上明确触发新任务。已完成建议在读取时发现新消息或新摘要版本，也会标为 `stale`；保留其输出与原 revision，不能把历史建议当作当前诊断。

迁移 `support-summary-assistance.changelog.yaml` 新增 `support_summary_job`、`support_summary_attempt`，并以新的 origin CHECK 接纳 `model_assisted`；原 CHECK 保留但设为不执行。没有删除、清空、重建业务表或初始化已有数据库的步骤。

job 保存发起者、assignment、幂等键、基础版本、来源 seq/JSON/SHA256、配置模型、结果版本、建议及 usage/request ID。attempt 逐次保存模型、worker、状态、开始/结束时间与失败码。已得到 provider usage 的畸形 JSON 或截断输出失败也可审计消耗；未返回 usage、请求超时或被撤权取消时保留实际已知信息，不推算用量。

故障排查应区分 feature 未启用、进程存在、任务排队、租约丢失、provider 故障、输出验证失败和 source 已变化。保留 `summary-worker.log`、数据库行和原始失败证据；重建 schema、清库或改已有客户/客服身份不是恢复步骤。

原 Dev 的本次真实任务共三个 attempt：前两次结构违规被拒绝并保留 usage，第三次仅增强 prompt 后通过严格服务端验证。之后客户新增 seq 6 使任务过期，未自动发起第四次模型请求；历史 v2 保留，客服明确修订得到 v3。详情、失败原因、用量、备份与技术边界见 [验证记录](./HANDOFF_ASSISTANCE_VALIDATION.md)。
