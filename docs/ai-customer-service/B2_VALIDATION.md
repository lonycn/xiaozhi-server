# B2 AI 文字执行验证记录

日期：2026-10-30。分支：`Dev`。本批在 B1 和 Vue 3 基础上增加文字 AI，不改变原 ESP32 WebSocket、OTA 或 aimeet 服务。

## 实现范围

- manager-api 在用户消息事务内创建唯一 AI run 与固定 assistant 消息，Python worker 只通过专用内部 HTTP 接口 claim/续租/回调，不连接数据库。
- 百炼使用现有默认 qwen-flash 配置，保留业务空间 URL；请求流式文本并保存提供方实际报告的 usage，缺失值保持 null。
- 同一条 assistant 消息接收增量、完成、失败、停止及重试状态；支持 SSE 游标重连与 JSON 事件补拉，前端使用十进制字符串处理 64 位游标。
- 新输入、停止、转人工与控制代次变更使旧 worker 输出失效；续租、回调、手动重试和最多三次 claim 均由 Java 校验。
- 新增旧 `ai_agent_chat_history` 的所有者只读投影，保留来源 ID、角色、中文原文及音频存在标记。不会自动导入统一 Conversation，也不会把旧历史自动发给模型。
- 发布开关默认关闭，Dev 使用 `dev/manage.py ai-enable/ai-disable/ai-status` 显式管理。

## 自动化结果

| 验证 | 结果 | 实际边界 |
| --- | --- | --- |
| Java 完整 Maven package | 281 项通过，零失败、零错误、零跳过 | 含真实 Spring 装配与所有隔离 MySQL 持久化测试 |
| SSE 故障恢复追加验证 | 6 项新测试通过；与认证/协议合计 15 项定向回归通过并重新打包 | 开流前数据库异常返回 JSON 503，开流后断开保留游标；并发名额释放 |
| Liquibase | 完整主迁移在新库执行两次，第二次无新增变更 | 旧历史测试使用与 Boot 相同的 Spring 资源访问器；不修改历史 SQL/checksum |
| AI 持久化/并发 | 8 项通过 | 重复输入、并发 claim、事务回滚、过期租约、有限恢复、跨账号、停用账号、人工接管 |
| 真实 HTTP 协议 | 49 项通过，模型调用 0 次 | 专用密钥、用户 Bearer 隔离、SSE、顺序/幂等、停止/重试、人工模式拒绝旧回调 |
| Vue 3 单测 | 181 项通过 | 增量与快照去重、迟到响应、会话切换、重试/停止和 64 位重连 |
| 前端契约/快照 | 10 / 13 项通过 | 原 B1 契约及现有管理功能回归 |
| 多语言/生产构建 | 6 种语言各 1619 key；构建通过 | 已有 router 静态/动态导入提示保留，不影响构建 |
| 独立 Python worker | 14 项通过 | SDK 异步流解析、真实 usage、失败和首包前撤销，使用 HTTP 替身 |
| Dev 运行脚本 | 19 项通过 | 配置导出、私有文件权限、进程管理、默认关闭、启停顺序与运行包原子暂存 |

本次发现并修复真实 Spring 装配缺陷：Shiro 工厂作为 BeanPostProcessor 创建过早，`@Value` 尚未解析。改用基础设施 Environment 读取专用 worker key，并加入实际 context.refresh 的回归测试，错误/空密钥仍拒绝访问。

## 真实提供方验收

独立 API + 测试 MySQL + 实际 Python worker + 现有百炼 qwen-flash 已完整跑通：发送合成测试文字“请只回复四个汉字：联调成功”，实际回复“联调成功”。真实 usage 为输入 **18**、输出 **3**、合计 **21** tokens。

SSE 回放实际包含 conversation.created、message.accepted、ai.run.queued、assistant.message.created、ai.run.started、三次 assistant.text.delta 和 assistant.message.completed。最终消息 ID 与 run.output_message_id 相同；重新读取消息后文本及 completed 状态一致。未向模型发送实际业务对话。

## 数据与运行边界

测试仅使用本轮新建、带 `xiaozhi_*_test_*` 前缀的独立库，失败重跑也创建新库，不清空前一轮测试库。Dev 升级前完成完整数据库快照并保留私有清单。新迁移只增加 `ai_run` 和 `ai_model_usage`，不删除、改写旧聊天记录。

Dev 浏览器已通过发送实际百炼消息、停止部分输出、重试同一消息并完成回复；实际回复为“后端联调正常”，第二次 attempt 的实际 usage 为输入 20、输出 5、合计 25 tokens。浏览器测试遇到过 MySQL 间歇通信超时，原消息和部分输出保留，显式停止/重试可恢复。追加 SSE 故障测试后重新部署；未将一次成功描述为消除了底层环境的间歇通信问题。

同时修复 Dev 构建直接覆盖运行中 JAR 的问题：Compose 改挂载私有暂存包，复制到临时文件后原子替换，运行容器保留旧 inode 至重建。启动等待由 90 秒调至 180 秒，覆盖本机实际出现的约 110 秒启动。

最终 Dev 浏览器验证使用 Ego Lite：桌面 CSS 视口 1706×1125；手机 390×844（减少动态效果模式）。刷新后回复保持完整；手机 document.clientWidth 与 scrollWidth 均为 390，导航收起后未遮挡内容。页面标题和 URL 正确，非空，无 Vite 错误覆盖层；最后一次恢复 AI 的观察窗口中 Runtime 异常与 console.error 均为 0。

真实操作路径为：创建独立验收会话 → 发送 → 停止部分输出 → 重试同一消息 → completed → 刷新 → 转人工排队 → 保存人工留言 → 取消排队 → 明确恢复 AI。最终状态 AI_ACTIVE、消息 3 条、run 仍只有 1 条且 completed/attempt=2；人工留言与恢复 AI 均未自动触发额外模型任务。`admin / admin123` 已重新登录验证。

Dev 与原智控台的 `sys_user`、`ai_model_config`、`ai_agent_chat_history` 数据行导出 SHA-256 在升级前后完全一致，原有 25 条旧聊天记录保留。API 已实际挂载 `dev/.runtime/manager-api.jar` 只读副本，副本与最终构建包摘要相同。22 个独立测试库在停止临时容器前完整归档到私有压缩备份；Dev、原智控台、aimeet 及其数据库/卷不做删除、清空或重新初始化。

## 尚未完成

B3 私有附件/语音转写/视频帧处理、B4 截图箭头圈选标注、B5 LiveKit 真人音视频与专家转接、B6 外部 App 可信身份及业务接入、Omni 实时技术验证、真实终端和网络试点仍待后续开发。本批不等同于完整多模态交付。

接口、worker 私有协议及开关说明见 [B2_IMPLEMENTATION_CONTRACT.md](B2_IMPLEMENTATION_CONTRACT.md)、[OPENAPI.yaml](OPENAPI.yaml) 和 [Dev 运行说明](../../dev/README.md)。
