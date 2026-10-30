# 验证方法

## 默认 Java 测试

`mvn -B -DskipTests=false test`。新测试包含严格请求解析、伪造 owner/tenant/project/sender 拒绝、越权智能体、输入类型/长度/位置验证、分页参数、默认关闭开关、HTTP 错误状态和登录身份来源。

## 隔离 MySQL 集成测试

`ConversationPersistenceTest` 仅在显式设置 `CONVERSATION_TEST_JDBC_URL` 后启用；连接串必须指向 loopback 且数据库名以 `xiaozhi_conversation_test_` 开头，测试不会读应用的数据库配置。

用独立 MySQL 8 容器和空测试数据库：

```sh
CONVERSATION_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:测试端口/xiaozhi_conversation_test_run?allowPublicKeyRetrieval=true&useSSL=false' \
CONVERSATION_TEST_DB_PASSWORD='隔离测试容器密码' \
mvn -B -DskipTests=false -Dtest='Conversation*Test' test
```

测试验证新增 Liquibase changeset 重复 update 只执行一次、旧表哨兵保留、真实落库与重建 Service 后查询、跨账号所有入口拒绝、24 路重复请求只产生一条消息与事件、30 路不同消息序号连续、大小写敏感幂等键、消息/事件分页补拉、事件写入失败时消息和计数整体回滚。

## 兼容基线

- Python：使用现有环境运行 `main/xiaozhi-server/tests`，不调用付费模型或设备控制；区分缺失依赖、既有失败和本批回归。
- Java：运行全套现有单测，记录原测试对 live MySQL/Redis 的依赖；不能把跳过当作通过。
- Web：使用锁定依赖运行 `npm test`、`npm run test:snapshot` 与 `npm run build`。
- 旧 ESP32 实机：部署新版本前再验证 OTA、鉴权 WebSocket、Opus、MCP、语音及历史。当前代码测试不代表完成实体麦克风/喇叭或新平台现场验收。

本轮实际执行结果见 `VALIDATION.md`。
