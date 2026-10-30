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
  support-handoff-enabled: true
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

原 Liquibase master 包含统一对话账本及 B1 人工文字 changelog。B1 为 `ai_conversation` 增加 `control_epoch`，新增客服档案、人工请求、指派历史及请求幂等键四张表。即使新入口关闭，标准数据库升级仍会应用增量结构；关闭开关不会删除数据。旧数据库迁移和历史不修改。上线前备份自己的数据库，并在副本验证完整迁移。

回退时关闭两个开关并回退应用版本，保留新增结构及历史。`support-handoff-enabled=false` 禁用人工生命周期入口与客服菜单权限；已有指派授权的账本读取仍可用，避免关闭入口后丢失历史访问。若统一对话开关也关闭，则账本 HTTP 入口一并关闭。

## 当前本机部署边界

此 Git checkout 使用独立的 Dev Compose 服务（API 18893、Vue 3 预览 18894），启停方式见 [Dev 说明](../../dev/README.md)。原智控台与 ESP32 运行服务保持独立。部署到设备实际使用的服务前，要合并本地 Workspace ASR、TTS 配置与 OTA 兼容补丁，核对配置及服务间鉴权，备份后再构建和发布。HTTP 本机开发验收不能替代公网 HTTPS/WSS、ESP32 实机或 Android 验收。

默认 `Dockerfile-web` 已改为从 `main/manager-web-next` 使用 Node 22 和独立锁文件构建 Vue 3。生产构建默认关闭新增入口，开放时添加 `--build-arg VITE_CONVERSATION_ENABLED=true` 并启用后端两项功能开关。静态前端独立镜像仍可使用 `Dockerfile-web-next`；本次没有发布生产镜像。
