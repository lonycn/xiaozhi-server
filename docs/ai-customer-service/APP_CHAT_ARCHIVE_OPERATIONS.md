# 外部业务聊天归档（可选）

归档用于把授权会话的消息发送到接入方维护的业务系统。仓库不附带客户专用回调地址、issuer、Key ID、签名密钥、账号或设备身份；默认不开启客户回调。

## 私有配置

实际配置仅写入本机 Git 忽略的 `dev/.runtime/xiaozhi-app.env`，该文件不随代码提交。对接方自行配置端点、来源和独立签名凭据。

| 变量 | 用途 |
| --- | --- |
| `XIAOZHI_ARCHIVE_ENABLED` | 是否显式开启归档 |
| `XIAOZHI_ARCHIVE_URL` | 对接方归档端点的完整 HTTPS 地址 |
| `XIAOZHI_ARCHIVE_SOURCE_ISSUER` | 已授权的外部身份来源 |
| `XIAOZHI_ARCHIVE_ISSUER` | 归档消息发送方标识 |
| `XIAOZHI_ARCHIVE_KEY_ID` | 对接方分配的独立 Key ID |
| `XIAOZHI_ARCHIVE_KEY_B64` | 私有签名密钥，不写入文档或日志 |
| `XIAOZHI_ARCHIVE_ALLOW_INSECURE_LAN` | 仅用于显式启用的隔离开发环境 |
| `XIAOZHI_CA_BUNDLE` | 可选的本地证书信任配置 |

示例仅展示占位结构，不能作为真实连接配置：

```dotenv
XIAOZHI_ARCHIVE_ENABLED=false
XIAOZHI_ARCHIVE_URL=https://archive.example.invalid/v1/chat-archive
XIAOZHI_ARCHIVE_SOURCE_ISSUER=example-app
XIAOZHI_ARCHIVE_ISSUER=example-server
XIAOZHI_ARCHIVE_KEY_ID=example-key-id
# 签名密钥通过接入方私有配置提供，不附示例密钥。
```

## 投递与身份

归档使用 outbox 和重试机制，与会话消息事务、消息顺序及幂等约束配合。来源身份应通过正式授权交换建立，不能用客户端提交的手机号或显示名称替代资源授权。

签名、去重、失败重试和所有权检查以当前 Java 归档模块及其测试为准。调试使用隔离测试库和合成数据；不复制真实会话、账号、回调签名或认证头到公开日志。

原有客户联调配置和验收记录保存在本地私有档案，公开文档仅保留协议与配置方式。
