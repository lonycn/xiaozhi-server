# Server Agent 与 App 接入能力交付记录

日期：2026-11-01。开发分支：`Dev`。本批继续推进不依赖真实 Laravel API 和 Android 设备的 Server 工作，大模块由 GPT-6.1 Sol 完成，接口、页面状态、运维和 CI 等有界工作由 GPT-6.1 Terra 完成，主任务负责整合、真实模型/浏览器验收及 Dev 部署。

## 本批完成

| 能力 | 最终行为 | 验证边界 |
| --- | --- | --- |
| App 主动结束人工服务 | `POST /api/v1/support/requests/{id}/end`，仅会话所有者可结束自己 ACTIVE 服务；幂等释放分配并触发媒体清理，保留聊天和摘要 | 独立 MySQL，LiveKit 清理使用受控 RPC 响应 |
| 外部邀请发现 | `GET /api/v1/external/media-invitations`，发现归属当前身份/租户的邀请；预览不授予媒体权限，首次接受原子绑定 source 和具体 Bearer | 独立 MySQL 验证跨用户/租户/来源/令牌及转接隔离 |
| App 能力声明 | `GET /api/v1/app/capabilities`，包括工具与续期配置状态、分页限制和错误语义 | 外部 Bearer 和本地登录真实 HTTP 验证；配置开关不等于依赖可用 |
| 普通文字 Agent 工具闭环 | Qwen 提议调用 → Java 检查并执行 → Worker 以 `tool` 角色续答；允许列表含设备、工单、知识只读适配及显式开启的技术工具 | 真实百炼与 Java HTTP 验证技术工具；真实业务 API 尚未接入 |
| 工具审计与用量 | 持久调用、尝试、不可变审计；每轮 provider 请求及汇总 usage；工作区分页查看状态 | 实际两轮模型用量与 SQL 账本一致；未知 usage 不填零 |
| 外部令牌续期 | 签名 `POST /api/v1/external/identity/renew`，只延长仍有效的同一 token，不换 token、不扩大租户/scope、不延长来源会话 | 真实签名 HTTP 与独立 MySQL；真实 Laravel 签发方尚未接通 |
| 管理员只读诊断 | `GET /api/v1/admin/diagnostics/readiness`，数据库及任务账本、过期 lease、最近 60 分钟工具错误统计；只允许当前有效 super_admin | 原 Dev admin 真实 HTTP，外部 Bearer 拒绝；provider/LiveKit 可用性未包含在其范围内 |
| Dev 工具运维 | `agent-tools-enable [--technical-local]`、`agent-tools-disable`、`agent-tools-status`，技术工具必须显式开启 | 运维单测及原 Dev 实际启动 |
| 实时工具边界 | 默认实时音频/VAD 继续工作；未获执行权限的工具声明/事件明确拒绝 | realtime worker 48 项测试；实时工具执行仍未实现 |
| Dev 后端 CI | Java 21、独立 MySQL/Redis、AI/realtime worker 与 Dev 测试，明确开启 Maven tests | 见文末 CI 记录 |

## 授权与执行限制

- 工具两开关默认均关闭：`features.agent-tools-enabled=false`、`agent-tools.technical-local-enabled=false`。当前本机 Dev 为便于测试显式开启二者，发布默认值没有改变。
- Java 是唯一执行方。Worker 不执行任意 URL、Python 或 shell，也不能指定其他用户、来源或租户。来源绑定来自发起请求的可信 Principal；不通过 owner 查询补造授权。
- 每次 dispatch、结果提交、缓存重放及文字发布重新检查 source/token、lease、attempt 和控制版本。续期只允许同一不可变绑定；换令牌不能接管旧任务。撤销、取消、人工接管和旧 lease 的迟到结果被拒绝或记为丢弃。
- 每次 run 最多 3 轮工具、6 次调用、4096 字节参数、32768 字节结果、30 秒工具循环。结果保留可信来源标识及白名单字段，并标明数据不可信；证据中的 `references[].data` 容器也递归过滤，密钥/URL/任意字段不能通过该容器进入模型。
- 图片、音频继续沿用原有模型路径；工具只对明确支持的文字模型开放。终态 `complete` 不允许 `finish_reason=tool_calls`。
- 续期默认关闭，最大时长受 token 配置、续期配置和来源到期共同约束；不能复活已过期/撤销 token，已签发的短媒体票据不随续期延长。
- 原 Dev 没有配置真实外部身份密钥或业务 API。技术工具只报告固定协议能力，不返回或伪造设备、工单、知识答案。

## 真实验收

### 模型与 HTTP

独立全链路 12 项通过。最终独立 schema 为 `xiaozhi_agent_tool_test_http_20261101_100000_d78375`，包含两轮真实 Qwen-flash 请求：

| 请求 | finish_reason | 输入 | 输出 | 总计 |
| --- | --- | ---: | ---: | ---: |
| 第 1 轮 | tool_calls | 206 | 15 | 221 |
| 第 2 轮 | stop | 315 | 17 | 332 |
| 汇总 | | 521 | 32 | 553 |

Java 执行 `server_capabilities` 成功，第 2 轮消息角色为 `user, assistant, tool`；每轮账本和聚合相符。还验证了同 token 续期、来源撤销、logout、同 owner 换 token、旧 lease 重放、非法终态、重复 JSON 键、伪造 actor 参数、超大请求体和精确游标。独立 API/Redis 已停止，新测试库全部保留。早期验收发现的 Shiro 入口、control_epoch 数值类型、Qwen 空 ID 增量兼容问题均已修复并加入回归。

本机证据：`/Users/lengwang/code/esp32/work/server-agent-tools-20261101/acceptance/http-20261101_100000_d78375/proof.json`。未发送客户业务数据，也未配置业务 adapter。

### 自动测试

- Java：完整 549 项通过、零跳过；此后补齐 Shiro 精确入口、管理员诊断及证据过滤后，相关 31 项、最终 13 项定向测试通过并重新打包。不同轮次计数不累加。
- Vue：482 项单元/组件、10 项契约、13 项 snapshot 通过；6 语言每种 1937 key 检查与生产构建通过。
- Python：普通 AI worker 完整 66 项、realtime worker 完整 48 项通过。
- Dev 运维完整 65 项及 CI 数据库准备 3 项通过。
- 外部 App/诊断的额外独立 MySQL 检查 6 项通过；故障/状态数据为合成技术样本，不等于真实业务联调。

### 浏览器

入口：`http://127.0.0.1:18894/#/conversations`，账号沿用 `admin / admin123`。使用 ego-browser；没有切换到其他浏览器驱动。新建“Server Agent工具技术验收-1101”，通过实际页面发送问题，真实模型调用技术工具并回复；展开状态显示 `server_capabilities 已完成`。Dev 的该次调用另记实际 usage：479 输入、59 输出、538 总计，SQL 为 completed/stop。

统一对话桌面、390×844 手机尺寸及专家工作台检查：保留全局蓝色导航；工具记录可展开；消息/状态正常，未出现 Vite overlay。初次加载的 vue-i18n Legacy API 弃用提示是已有警告；稳定页面未观察到应用 console error。手机检查覆盖布局，不代替 Android 设备验收。页面证据位于同一 work 目录的 `tools-desktop.png`、`tools-mobile-final.png`、`support-tools-final.png`。

## 原数据库与部署

Dev 使用原 MySQL 和 Redis。只追加 `agent-tools` 和 `ai-provider-request-usage` 两条 changeset：120 → 122；没有删除、清空或重新初始化任何既有数据库/卷。

| 数据 | 迁移前 | 页面验收后 | 原记录核对 |
| --- | ---: | ---: | --- |
| 用户 | 4 | 4 | 原 4 行逐字节一致 |
| 旧聊天历史 | 25 | 25 | 原 25 行逐字节一致 |
| 统一消息 | 35 | 37 | 原 35 行逐字节一致，新增 2 条技术验收消息 |

变更前 SQL 备份 SHA-256：`6156db534a342d30f63113bce500c179e4a39a1d53bff74bb45dcaa5f4da20a5`。备份及核对记录为私有文件，位于 `work/server-agent-tools-20261101/`；核对结果见 `dev-data-preservation.json`。最终测试 JAR 已原子放入 Dev runtime 并重建 API，AI/realtime worker 使用当前源码，原前端 18894 继续运行。

## 仍需开发或接入

1. **不依赖业务 API 的下一阶段**：实时工具独立 session/response/call 账本、提议/ACK、turn_generation 和打断 fencing，再接实时模型工具循环；不能复用或伪造 `ai_run`。设计见 [实时工具执行设计](./REALTIME_TOOL_EXECUTION_DESIGN_20261101.md)。
2. **Agent 技能与运营**：可审核的技能/流程配置、内部工单草稿与人工确认工作流、统一配额和限流、provider/LiveKit 依赖探测、告警/容量/备份恢复演练。当前诊断是数据库/任务账本范围，不能宣称整个服务健康。
3. **真实业务 API**：Laravel 签发方及续期调用、真实设备/工单/知识只读结果、权限校验，以及后续写入/派单接口。当前无真实 API，不以技术测试代替。
4. **终端与生产**：Kuikly 实际协议切换、Android/ESP32/iOS、真人声学和画面、公网 HTTPS/WSS/TURN、现场网络与生产压力验收。

完整原始 18 节规划保持不变。App 对接细节见 [Server App 契约](./SERVER_APP_CONTRACT_20261101.md)；续期协议见 [外部令牌续期](./EXTERNAL_RENEWAL_CONTRACT_20261101.md)。

## CI 与提交

本批源码和文档在 Dev 整理提交；推送后的实际 CI 结果将在检查完成后补入，不以工作流文件存在代替通过。
