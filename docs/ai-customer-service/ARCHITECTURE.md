# 统一对话升级：源码审计与第一批实现

日期：2026-10-30。基线：`lonycn/xiaozhi-server` 的 `main`，提交 `1a095d5bed23e49a17f817c7f378e2a86f298dfc`。
完整需求保存在仓库根目录 `xiaozhi-server-multimodal-support-plan-v1.md`，保持原文。

## 当前交付范围

本批完成 P0 的源码审计及 P1 的最小持久化：创建/列出/读取对话，保存文字与主动分享的位置，读取消息历史、重放持久事件。支持身份校验、并发排序、发送幂等、事务回滚与增量 SQL。

这是消息账本基础。`accepted` 仅表示已保存用户消息，本批不会调用模型或生成 AI 回答；`AI_ACTIVE` 是为后续状态机保留的初始服务模式。文本流式回复、旧历史桥接、媒体上传、人工客服与音视频仍是后续阶段，不能将本批称为整个 P1 或完整平台已交付。事件接口当前提供 JSON 游标补拉，SSE 在模型执行链路接入时追加。

## 可复用的源码

| 范围 | 路径/结论 |
| --- | --- |
| 用户身份 | `modules/security/oauth2/Oauth2Filter.java`、`Oauth2Realm.java`、`SecurityUser.java`，继续使用现有 Bearer Token |
| 身份字段 | `common/user/UserDetail.java` 只有用户 ID，没有租户/项目授权信息 |
| 智能体所有权 | `modules/agent/dao/AgentDao.java`、`AgentEntity.userId`；新会话关联智能体必须确属当前用户 |
| 旧历史 | `AgentChatHistoryController`、`AgentChatHistoryService`、`ai_agent_chat_history`；保持接口和表原样 |
| 已删除旧表 | `202503141346.sql` 曾建 `ai_chat_history`、`ai_chat_message`，`202505022134.sql` 已将其删除；不能按旧名字复用 |
| 数据源与事务 | 沿用 manager-api 的 MySQL DataSource 和 Spring 事务管理，用 JdbcTemplate 执行参数化 SQL |
| 增量迁移 | 追加 `conversation.changelog.yaml`，由原 `db.changelog-master.yaml` 引入；旧迁移不修改 |
| Python 设备链路 | `app.py`、`core/connection.py`、`core/websocket_server.py`、`core/http_server.py`、`core/api/vision_handler.py` 均不改动 |
| 后续模型/MCP | 复用 `core/providers` 与现有工具系统；本批不触发设备或云模型操作 |
| 网页 | `manager-web/package.json` 为 Vue 2.6 + Element UI 2，保留现有前端技术栈 |

## 数据与事务

- 新表仅有 `ai_conversation`、`ai_message`、`ai_conversation_event`。尚未实现的客服/媒体表不提前建空壳。
- 单条消息的 `parts` 是有序数组，文字与位置共同保存，不能拆成多条消息。附件类型在完成上传授权前返回 415。
- `conversation_id` 是持久对话 ID，与临时音视频连接解耦。
- 发送时按当前用户锁定会话行，再检查幂等键、分配消息序号、保存消息及事件、更新计数；所有步骤在同一事务中提交。
- `(conversation_id, seq)`、`(conversation_id, client_message_id)`、事件序号均有数据库唯一约束。
- 幂等编号以 ASCII 大小写敏感方式比较。同编号同内容返回原消息；同编号不同内容返回 409。
- 消息及事件按递增序号补拉。对话列表按稳定的创建序号倒序分页；不按更新时间分页，避免新消息导致游标跳动。

## 权限边界

owner 和 sender 只从 `SecurityUser` 读取。包括管理员在内，本批只允许访问本人会话；不加入管理员跨用户读取的隐式旁路。
未知会话与他人会话均返回 404。对话、消息和事件使用同一所有权检查。

`tenant_id` 仅为服务端未来扩展保留，当前为 NULL，不接受客户端传入。`project_id`、`equipment_id`、自填 sender、未知消息字段全部拒绝。接入可信 SSO 和项目权限服务前，不能声称已支持多租户或项目授权。

`ConversationOauth2Filter` 仅作用于新增 `/api/v1/ai/**`，沿用登录逻辑但认证失败使用 HTTP 401；原路由的响应语义不变。新增 Controller 的局部异常处理器返回真实 HTTP 400/404/409/415，仍保留 `{code,msg,data}`。

## 下一批任务与提交切分

1. 本批：完整规划、架构和数据契约；最小账本、开关、增量 SQL、并发及权限测试。
2. P1 后续：Python 内部服务鉴权、文本模型执行、SSE、失败状态与用量、旧 ESP32 历史只读映射；先验证现有部署的百炼 Workspace 适配。
3. P2：私有对象存储上传授权、附件验证与媒体任务；附件权限完成后开放图片/音视频 parts。
4. P3/P4：先验证 Omni SDP/事件，再实现 AI 实时；人工请求状态机、原子抢单和 Vue 2 工作台分批提交。
5. P5/P6：静态标注、LiveKit、TURN 和 Android 真机；云服务 PoC 与现场验收分别记录。

规划中的模型名称、百炼 SDP 能力和 LiveKit 配置尚未在当前账号验证。不得将规划描述当作模型开通或实时通信可用的证据。
