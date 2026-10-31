# 外部 App 媒体授权与自动听音运维

当前 Dev 已部署并启用 VAD，外部媒体开关保持关闭。独立技术环境已验证 Java、Python 与百炼两轮 VAD，以及 logout 和来源撤销；另使用真实 LiveKit 双向正弦音 RTP 验证了撤销后旧 JWT 只能进入旧空房的场景，所测断流耗时为 2.299 秒。这是该次测试结果，不是断流时限承诺。真实 App、物理设备和弱网等验收仍未完成，具体验证证据另见交付验证记录；本文中的源码能力不代表已在 Dev 开启或完成生产部署。

不得清空、删除或重新初始化任何现有数据库；启用前应按现有增量迁移流程评审、备份和应用相关 changeset。原有 App 的 SSO v1、Cookie、PAT、用户数据和签名协议不变；小智外部身份使用独立密钥与接口。来源签名细节见 [B6 实施契约](B6_IMPLEMENTATION_CONTRACT.md) 和 [B6 运维](B6_OPERATIONS.md)。

## 开关和前置条件

`application.yml` 中以下开关均默认为 `false`：

| 配置 | 作用 |
| --- | --- |
| `features.external-identity-enabled` | 开启独立外部身份入口及认证。 |
| `features.external-realtime-media-enabled` | 允许外部 Bearer 使用下面列出的媒体路由，并持续检查媒体绑定。 |
| `features.realtime-ai-enabled` | 开启 AI 实时会话；依赖已配置的 worker、模型凭据和可访问的连接地址。 |
| `features.realtime-server-vad-enabled` | 允许新建 `server_vad` 会话；不改变手动模式默认值。 |
| `features.support-media-enabled` | 开启人工支持媒体；另需 LiveKit 专用配置、支持服务及对应会话/人工服务开关。 |

会话、AI、人工服务及相关能力仍受各自 feature gate 和现有 owner、assignment、状态检查约束。启用媒体开关本身不授予客服角色或业务资源权限。配置来源密钥、TLS、worker 和 LiveKit 的操作不要将密钥、Bearer、单次票据或完整能力 URL 写入日志。模型接入方式、地区和可用模型应核对 [阿里云 Qwen-Omni-Realtime 官方文档](https://www.alibabacloud.com/help/en/model-studio/realtime) 与实际工作空间；文档中的协议能力不等于本项目已实现或已验收的能力。

## 来源身份、期限和撤销

1. 来源服务从已认证用户、有效来源会话和可信成员关系构造 subject、tenant、scope，经独立密钥签名调用 `POST /api/v1/external/identity/issue`。来源需真实校验设备和项目归属；客户端不能自报本地用户、角色或授权范围。签名原文、重放保护和请求格式沿用 B6 契约。
2. App 在 HTTPS 下以单次短票调用 `POST /api/v1/external/identity/exchange`，获得独立的 `xext_` Bearer；`GET /api/v1/external/identity/me` 查看上下文，`POST /api/v1/external/identity/logout` 只撤销当前 Bearer。来源服务使用签名 `POST /api/v1/external/identity/revoke` 精确撤销来源会话及其 Bearer。来源系统负责可靠投递撤销事件，本仓不实时查询来源账号或成员关系。
3. `external-identity.token-seconds` 默认 300 秒，可配置范围 30–900 秒，且 Bearer 不超过来源会话到期时间。兑换票据最多 60 秒并只能消费一次；不存在绕过来源认证的 refresh。重复 issue 不能延长或复活原来源会话。
4. 媒体会话由服务端捕获并绑定来源会话与具体 Bearer。即使同一来源或同一 tenant principal 下有多个 Bearer，它们也不能互相接管对方已绑定的媒体会话；不同 issuer、tenant 类型和 tenant 的身份隔离沿用 B6。撤销某个 Bearer、来源会话、issuer 或禁用账号，只影响相关授权范围；媒体结束不删除聊天或人工 assignment。
5. 有效期内重连仍须使用原绑定 Bearer 和当前 epoch／房间代次。Bearer 到期、logout 或撤销后，不用新的 Bearer 接管旧媒体会话。先关闭本地采集和播放；在来源会话仍有效且经来源重新认证时，重新 issue、exchange，等待旧媒体结束，再用新 Bearer 发起新媒体会话。来源已撤销或过期时先完成来源重新登录和新来源会话授权。失败时不静默回退旧身份。凭据默认 300 秒意味着外部媒体通常受此时间约束，即使 AI 实时会话的系统上限为 30 分钟；二者取更早限制。

请求和 worker 回调重验绑定，失效或依赖故障按失败关闭处理；P3 与 B5 的后台维护默认分别每 2 秒扫描最多 64 条、每 3 秒扫描最多 50 条，按授权检查时间轮转并执行终止/清理。实际延迟受队列、数据库、网络和 LiveKit 可用性影响，不能承诺精确秒级或跨系统瞬时撤销。LiveKit 客户端 JWT 的有效期还受配置的 token TTL（15–120 秒）和绑定 Bearer 到期限制；已经签出的 JWT 不能被宣称瞬时失效。新 JWT 请求在失权后拒绝，但现有 LiveKit 媒体停止依赖 outbox 移除成员或删除房间成功，故故障期间不能声称旧房已被清空；失败持续补偿，退避最长 60 秒，不保证固定断流时间。清理完成后旧 JWT 最多进入旧空房；后续会话使用独立随机房间，转接的新代次也使用独立房间，旧 JWT 不能进入新房接触后续内容。

## 外部媒体精确路由

以下是外部 Bearer 可进入的精确媒体方法和路径，除 `media-drain` 外均需 `features.external-realtime-media-enabled=true`，仍须通过业务权限与相应 feature gate；并非开放整个前缀。此处 `{sid}` 为 32 位小写十六进制，`{mid}` 为正十进制 ID。路径不含部署时 servlet context，实际请求需按部署补上例如 `/xiaozhi`。

| 方法 | 路径 | 允许的查询参数 |
| --- | --- | --- |
| GET | `/api/v1/ai/realtime/capabilities` | 无 |
| GET | `/api/v1/ai/realtime/sessions/current` | `conversation_id` |
| GET | `/api/v1/ai/realtime/sessions/{sid}` | 无 |
| GET | `/api/v1/ai/realtime/sessions/{sid}/injections` | `after`, `limit` |
| POST | `/api/v1/ai/realtime/sessions` | 无 |
| POST | `/api/v1/ai/realtime/sessions/{sid}/connect-ticket`、`messages`、`interrupt`、`end` | 无 |
| GET | `/api/v1/support/media-sessions/current` | `conversation_id` |
| GET | `/api/v1/support/media-sessions/{mid}` | 无 |
| POST | `/api/v1/support/media-sessions` | 无 |
| POST | `/api/v1/support/media-sessions/{mid}/accept`、`reject`、`token`、`ready`、`end` | 无 |
| GET | `/api/v1/support/media-transfers/{mid}` | 无 |
| POST | `/api/v1/support/media-transfers/{mid}/approve`、`reject`、`cancel` | 无 |
| GET | `/api/v1/support/media-drain` | `conversation_id`（外部用户必需） |

客户只能对当前来源和 Bearer 绑定的转接执行上列操作；外部用户不能创建转接或执行坐席接受转接。坐席发起但客户尚未同意的 `invited` 会话允许拥有该会话的外部客户查询和拒绝，首次明确 `accept` 时才绑定来源和 Bearer；本地客户已同意或已激活的未绑定会话不能由外部身份继承。未知路径、其他转接操作、专家/客服管理和 worker 接口不因此开放。聊天、静态标注、附件代理等原有白名单仍按 B6 与 `ExternalPathPolicy` 执行，原有资源检查继续生效；外部来源不会因为开放媒体而获得人工坐席或任意资源权限。路由检查会拒绝未允许的查询字段和编码路径别名。AI connect-ticket 最多 30 秒且不超过绑定 Bearer 到期；不应持久保存或转交其他身份。

`media-drain` 是只读人工媒体清理状态：外部用户须提供本人会话 `conversation_id`，统计限定当前来源和 Bearer；外部身份仍需有效。关闭外部媒体开关后仍可查询该状态，以确认人工媒体会话及 outbox 清理；它不是 AI VAD 会话的完成状态。关闭 VAD 开关后，既有 `server_vad` 会话由实时服务维护流程终止并释放，关闭外部媒体开关也会使相关外部实时媒体失权并进入清理；实际完成受维护积压及依赖状态影响。

## AI 实时听音操作

`GET /api/v1/ai/realtime/capabilities` 返回 `turn_detection_modes` 和默认 `default_turn_detection_mode`。默认仅 `manual`；开启 VAD 后列表含 `server_vad`。创建会话的 `turn_detection_mode` 可为 `manual` 或 `server_vad`，省略时为 `manual`；创建后不可更改。切换模式须结束旧会话，再新建。功能未启用时不可选择自动模式。

- **手动**：同意麦克风后连接，模型就绪后点击开始录音，说完点击结束本段；每段最多 60 秒。摄像头另行授权，停止采集后不再取帧。
- **自动**：选择并同意后连接，仍需主动点击开始听音；服务识别说话结束后自动提交和生成回复，无需逐轮手动提交。静音等待不计入单次 60 秒发言上限；长段达到上限时停止或终止，需按界面状态处理。暂停立即关闭本地采集并等待服务器清空尚未提交的缓冲确认；再次开始必须再次点击。连接、发现已有会话和刷新不会自动打开麦克风。
- **回复与打断**：界面显示听音、说话、AI 回复及等待确认状态。自动模式检测到新语音时立即停止本地旧回复播放；模型确认完成取消后才记录取消结果。也可显式点击打断；未收到确认时不能假称模型已取消。
- **文字与画面**：开启画面需另行同意并遵守界面采样限制。自动听音期间追加文字会排队，暂停完成后再派发；保存消息与模型确认收到是不同状态。
- **权限或弱网故障**：浏览器拒绝权限、采集失败、连接中断、缓冲超限或确认超时会停止相应本地资源并显示错误。检查设备权限、HTTPS、网络及服务后按提示重试；不能把本地静音、命令已发送或连接按钮点击当作远端确认。服务端开始/暂停确认限时 10 秒，设备权限弹窗的等待不受该计时限制；等待授权时可点击暂停取消，结束、身份切换或会话变化也应释放本地媒体。

P3 AI 会话还有 30 分钟总上限、连接阶段 60 秒上限及租约/媒体缓冲限制；这些是安全上限而非可用性 SLA。模型输出质量、说话检测、打断体验、弱网恢复、旧房清理和外部撤销时延都需要在真实网络、设备、来源系统与服务环境分别验收，不能以单元测试、合成事件或功能开关状态替代。
