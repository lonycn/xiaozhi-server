# 技术决策

## ADR-001：在 manager-api 中增量增加账本

保留 Spring Boot 3.4.3 / Java 21 / MySQL / Shiro，新增 `modules/conversation`。不新建 FastAPI 身份或数据库中心。不更改 Python 设备协议和 Vue 2 后台。

## ADR-002：先按用户隔离，可信租户/项目授权后置

现有登录上下文不提供可信租户及项目域。本批拒绝客户端声明这些字段，避免制造权限假象。每个读取与写入都检查会话所有者；智能体必须归属于同一用户。未来加入客服访问时采用明确的 assignment/项目授权，而非去掉所有权检查。

## ADR-003：在媒体授权落地之前限制消息类型

当前运行接口仅支持 `text`、`location`。媒体、实时、客服、工具卡片的目标契约在 OpenAPI 中保留，但标记未实现；不能提交任意 URL 或 attachment_id 冒充已授权附件。

## ADR-004：事件持久化优先于实时推送

引入 `ai_conversation_event`，确保序号、消息和事件在同一事务提交，轮询也能可靠补拉。当前 `/events` 返回 JSON 游标分页，不冒充 SSE；模型流式接入后可在相同事件信封上增加 SSE。

## ADR-005：默认关闭新入口，迁移只增不删

`features.conversation-enabled=false` 默认关闭新 Controller。迁移随 manager-api 标准 Liquibase 启动流程应用；关闭入口不会删除新表，回退版本保留数据。禁止自动 DROP、回填或修改旧聊天历史。

## ADR-006：独立 Git 开发目录

现有 Mac 运行部署由源码快照和本地补丁组成，没有 Git 元数据。本批在独立的 fork checkout 开发，不覆盖当前运行目录、数据库或设备配置。首次部署前必须对照原本地的 Qwen Workspace、OTA 代理补丁与私有配置，另行进行备份、构建、兼容整合和实机回归，不能直接替换当前部署。
