# 开发与部署

本批代码默认关闭新增入口，不会启动额外服务，不需要新云密钥。

## 构建

```sh
cd main/manager-api
mvn -B -DskipTests=false test
mvn -B -DskipTests package
```

使用 JDK 21；原 pom 默认跳过测试，因此测试命令必须显式设置 `-DskipTests=false`。

## 开发环境启用

在部署自己的配置覆盖中设置，或用 `FEATURES_CONVERSATION_ENABLED=true`：

```yaml
features:
  conversation-enabled: true
```

沿用已有登录的 Bearer Token。默认 context path 为 `/xiaozhi`，所以本模块完整路径为 `/xiaozhi/api/v1/ai/conversations`。反向代理可能再映射路径，客户端以实际部署为准。

```sh
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"压缩机故障"}' \
  http://127.0.0.1:8002/xiaozhi/api/v1/ai/conversations
```

创建返回的 `data.id` 用作后续路径 ID：

```json
{"client_message_id":"phone-uuid-001","parts":[{"type":"text","text":"运行时有异响"}]}
```

将上述请求 POST 到 `.../conversations/{id}/messages`。从 `.../{id}/messages?after=0&limit=50` 或 `.../{id}/events?after=0&limit=50` 补拉数据。`next_cursor` 为最后一个返回条目的序号，空页保持原游标；对话列表使用 `before` 游标。

## 数据迁移与回退

原 Liquibase master 引入新增 changelog，首次启动创建三张新表。即使新入口关闭，标准数据库升级仍会应用增量表结构；关闭开关不会删除数据。旧数据库迁移和历史不修改。上线前备份自己的数据库，并在副本验证完整迁移。

回退时关闭开关并回退应用版本，保留三张新表，禁止 DROP 或清空历史。

## 当前本机部署边界

此 Git checkout 与本机正在运行的源码快照分离。本批不切换当前服务。部署前要合并本地 Workspace ASR、TTS 配置与 OTA 兼容补丁，核对配置及服务间鉴权，备份后再构建和发布。HTTP 本机开发验收不能替代公网 HTTPS/WSS、ESP32 实机或 Android 验收。
