# 大冷总管首页文字联调

## LAN 环境

2026-11-03 联调使用 `https://192.168.3.66:18443/xiaozhi`。网关转发 Dev API 的 `/xiaozhi/api/v1/`，SSE 响应关闭缓冲，代理读取超时 120 秒。客户端可在断线后用 `Last-Event-ID` 或 `/events?after=<sequence>` 补拉。服务端每条 SSE 连接最多持续约 25 秒。

Laravel 使用同一 HTTPS 基址，并通过 `XIAOZHI_CA_BUNDLE` 校验 LAN CA。华为联调用 Android 调试包只为 `192.168.3.66` 加入小智 LAN CA。正式包需要受其信任的证书链。

| 用途 | 联调值 |
| --- | --- |
| Laravel 换票 Key ID | `xiaozhi-daleng-lan-v1` |
| Laravel 来源 issuer | `xiaozhi-daleng-lan` |
| 归档 Key ID | `xiaozhi-dev-archive-lan-v1` |
| 归档来源 issuer | `xiaozhi-dev-lan` |
| 归档接收 URL | `http://192.168.3.66:8000/api/integrations/xiaozhi/archive` |
| Laravel 撤销队列 | `xiaozhi-dev-lan` |
| 文字模型 | `qwen-flash` |

同机联调密钥由进程分别从 `dev/.runtime/xiaozhi-app.env` 和 `dev/.runtime/app-archive-lan/laravel.env` 加载。两份文件权限为 600。Laravel 继续用当前登录会话和客户单位签发身份。

## 配置

Dev Compose 会加载可选的 `dev/.runtime/xiaozhi-app.env`。部署环境可以用等效的私有环境变量注入。

| Server 变量 | 用途 |
| --- | --- |
| `FEATURES_EXTERNAL_IDENTITY_ENABLED` | 启用外部身份端点 |
| `EXTERNAL_IDENTITY_RENEWAL_ENABLED` | 启用原 Bearer 续期 |
| `EXTERNAL_IDENTITY_KEYS_JSON` | 换票 Key ID 到 `issuer` 与 `secret_b64` 的映射 |
| `XIAOZHI_ARCHIVE_ENABLED` | 采集并异步投递归档任务 |
| `XIAOZHI_ARCHIVE_SOURCE_ISSUER` | 有权产生归档任务的 Laravel 来源 issuer |
| `XIAOZHI_ARCHIVE_URL` | Laravel 归档端点完整地址 |
| `XIAOZHI_ARCHIVE_ISSUER` | 对应 Laravel 接收配置的稳定来源标识 |
| `XIAOZHI_ARCHIVE_KEY_ID`、`XIAOZHI_ARCHIVE_KEY_B64` | 独立的归档签名凭证 |
| `XIAOZHI_ARCHIVE_ALLOW_INSECURE_LAN` | 仅联调用。允许发往 RFC 1918 IPv4 地址的 HTTP 归档请求 |

默认使用 HTTPS 投递归档。本次显式开启 LAN HTTP，以匹配已有 Laravel 联调地址。归档密钥至少 32 字节，且不得与任何换票密钥相同。轮换归档密钥时，两边同步 Key ID 和密钥，保留 issuer。

Dev 通过 `python3 dev/manage.py ai-status` 检查已启用的模型和 worker。Laravel LAN 服务进程加载其私有环境文件，包含 `QUEUE_CONNECTION=database` 和 `DB_QUEUE=xiaozhi-dev-lan`。独立队列进程使用相同环境及数据库，运行 `php artisan queue:work database --queue=xiaozhi-dev-lan --timeout=20`。

## 投递与重放

`message.accepted` 和文字 AI 终态事件在各自的会话事务内写入 `xiaozhi_chat_archive_outbox`。用户和客户单位 ID 来自已验签的来源身份。后台使用入库时保存的 JSON 原始字节和事件版本发送，每次尝试重新生成时间戳、nonce 和签名。附件只传持久引用。手动重试 AI 生成后，新的终态保留原消息 ID 和顺序，并产生更高的事件版本。

网络错误、429、500、502、503、504 自动退避。200 只有在 `data.result` 为 `applied`、`duplicate` 或 `stale` 时才确认送达。401 先检查归档密钥与时钟，409 先核对身份、顺序及版本。超过 512 KiB 的完整快照保留为 `PAYLOAD_LIMIT`，需要先处理源消息大小。停用归档开关会停止采集和投递，重新启用后可继续处理已保存的待投递任务。

运维人员可以按 ID 查看失败和回放指定任务。确认归档目标及失败原因后，用预编译参数执行以下语句。允许重放已送达行以验证接收端去重。

```sql
SELECT id, conversation_id, message_id, event_version, status, last_error, attempts
FROM xiaozhi_chat_archive_outbox
WHERE conversation_id = ?
ORDER BY event_version;

UPDATE xiaozhi_chat_archive_outbox
SET status = 'pending', available_at = UTC_TIMESTAMP(3), last_error = NULL
WHERE id = ? AND archive_issuer = ? AND lease_token IS NULL
  AND status IN ('failed', 'delivered');
```

## 真机验收记录

华为手机以现有大冷总管登录态完成换票、读取能力、新建会话、发送文字和接收 SSE。文字 worker 使用 `qwen-flash`，持久化了 96 条增量和一条完成事件。用手机原会话绑定的客户单位和账号查询 Laravel，双方的消息 ID、顺序、状态和正文一致。

会话 ID 为 `3d06b47dc5954df09beb2badc3656e6f`。

| 消息 | 原消息 ID | 顺序 | 事件版本 |
| --- | --- | ---: | ---: |
| 用户 | `ae70b5cc84ee4e08919f625f40ce6fbd` | 1 | 2 |
| AI | `649470ad1a2b48e7af6f9c836ea65cf1` | 2 | 104 |

两条消息按原内容重签名，Laravel 均返回 `duplicate`。随后将两条 Server 任务重新入队，均再次确认送达，Laravel 仍只有两条消息。

用同一账号和客户单位创建单独的验收来源会话，验证了 `issue`、`exchange`、`me` 和 `renew`。用 `Last-Event-ID: 103` 恢复 SSE，在约 0.02 秒内收到版本 104 的完成事件，与 `/events?after=103` 一致。Laravel 独立数据库队列执行撤销后，验收 Bearer 请求 `me` 返回 401。

## 回归测试

为 `ChatArchivePersistenceTest` 提供一套新建的回环 MySQL 库，库名以 `xiaozhi_archive_test_` 开头。通过私有环境设置 `CHAT_ARCHIVE_TEST_JDBC_URL` 和 `CHAT_ARCHIVE_TEST_DB_PASSWORD`。测试覆盖消息事务回滚、重复提交、AI 失败后重试、503 重签名重发与 409 停止自动投递。

### Account AI tenant compatibility (2026-11-05)

External identity issue and renewal accept `tenant.type=account` only when its numeric
`tenant.id` equals signed `external_subject`; both scope arrays must be empty.
Account, customer and company principals remain separate namespaces, while the
same source session revokes all its principals. Account identity does not grant
business connectivity or staff privileges.

Archive accepted snapshots capture `tenant_type` in the outbox, and terminal
callbacks inherit that persisted owner. New account bodies include
`"tenant_type":"account"`; customer bodies retain the previous shape. Missing
archive wire type means customer for backward compatibility. Stored retry bytes,
message IDs and versions are never rewritten.

Apply additive Liquibase change set `202611051000-chat-archive-tenant-type` from
`chat-archive.changelog.yaml` before running the updated API. It only adds nullable
`tenant_type VARCHAR(16) DEFAULT 'customer'` to `xiaozhi_chat_archive_outbox`;
existing payload blobs are untouched. No business schema reset is required.
