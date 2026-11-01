# 外部身份同一 Bearer 的受信来源续期契约

日期：2026-11-01。范围：新增受签名来源控制的身份续期机制；不代表 Laravel producer、真实 30 分钟通话或 Android 音视频已验收。

## 1. 权威与默认边界

来源系统仍负责判断自己的登录会话、租户成员关系、项目/设备授权是否有效。App 不能凭现有 Bearer 自行刷新，也不能把新的小智 Bearer 接管到已有 AI run、P3 实时会话或 B5 媒体会话。

续期只更新 **同一条 `external_access_token` 记录的 `expires_at`**。token ID、token hash、明文 Bearer、source ID、principal、user、tenant 和 scope 都不变；响应不返回新 Bearer。已经过期或撤销的 token、source、禁用账号或权限冲突账号不能复活。既有 issue / exchange / revoke / logout 行为保留。

配置默认关闭：

```properties
external-identity.renewal-enabled=false
external-identity.renewal-max-seconds=300
```

还需既有 `features.external-identity-enabled=true` 和有效来源签名配置。`renewal-max-seconds` 只接受 30～900 秒；实际单次上限取它与既有 `external-identity.token-seconds` 的较小值。来源 session 的既有 `expires_at` 是最终上限，续期 API 不接受或延长来源生命周期。

## 2. 来源请求

`POST /api/v1/external/identity/renew` 仅接受既有 B6 来源签名认证，不是 Bearer 刷新接口。请求无 query，原始 JSON body 最大 32 KiB，拒绝重复字段、尾随 JSON、未知字段和额外身份参数。

Body **精确字段**：

```json
{
  "external_subject": "source-user-subject",
  "source_session_id": "current-source-login-session",
  "token_id": "123",
  "tenant": { "type": "company", "id": "1" },
  "scope": { "project_ids": ["project-1"], "device_ids": ["device-1"] },
  "requested_lifetime_seconds": 300
}
```

`token_id` 为非秘密的十进制字符串引用，不是 token hash；必须属于该签名 issuer、subject 和 source session。tenant、scope 必须与现存 token 完全一致，scope 数组顺序也保持原样。来源需重新确认这些授权仍有效，不能为 scope 已缩小或租户已切换的用户沿用旧授权。该接口不接受新的 scope、来源到期时间、用户角色、目标 URL 或 token hash。

`requested_lifetime_seconds` 在 30～有效单次上限内；支持 JSON 整数或 B6 既有十进制整数字符串，不接受 bool、浮点数、指数形式或负数。新期限按下式计算：

```text
min(max(existing_token_expiry, current_time + requested_lifetime_seconds), stored_source_expiry)
```

正常情况下不缩短 token；来源生命周期本来更短时，以来源为准。

签名头沿用 `X-External-Key-Id`、`X-External-Timestamp`、`X-External-Nonce`、`X-External-Signature`。规范串仍为 `POST\n实际路径\n时间戳\nnonce\nSHA256(原始 body)`，HMAC-SHA256 十六进制签名；实际部署有 context path 时必须原样签入。时间窗 ±60 秒，nonce 是 32 位小写十六进制并持久去重。具体编码与密钥要求沿用 B6，不把来源密钥发给 App。

签名在进入续期事务前校验，并在获得行锁后和更新前重新校验当前配置，防止排队期间使用已轮换/撤除的签名凭据。相同 nonce 不能重放；重试先读取 `/me` 判断期限，再由来源签发新 nonce，不依赖重复提交同一签名作为幂等响应。

## 3. 响应与 App 配合

标准 Result 的 `data`：

```json
{
  "token_id": "123",
  "expires_at": "2026-11-01T01:05:00Z",
  "expires_in": 300
}
```

日期使用项目既有 Instant JSON 序列化，`expires_in` 为真实剩余整秒。响应使用 `Cache-Control: no-store` 和 `Referrer-Policy: no-referrer`，不含 Bearer、token hash 或签名密钥。

已认证 `GET /api/v1/external/identity/me` 追加 `token_id` 与 `renewal_enabled`，其余字段保留。App 可以把此非秘密引用交给当前已登录的 Laravel 会话；Laravel 必须依据自己的登录上下文恢复 subject/session/tenant/scope，不能照抄 App 自报身份签名。

建议流程：App 在旧 Bearer 到期前请求 Laravel producer 续期 → producer 重新鉴权并签名调用小智 `/renew` → App 沿用原 Bearer，再读 `/me` 确认期限。来源未配置或续期失败时，客户端按既有到期/撤销机制停止旧链路，不暗中交换新票据接管旧会话，也不自动打开摄像头或麦克风。

## 4. 并发、会话绑定与媒体

续期按 source → token → principal/account 获取行锁，与来源 revoke 的 source → token 顺序一致。撤销先完成则续期拒绝；续期先完成则之后的 revoke 仍撤销同一 token。等待行锁跨过 token/source 到期时再次检查，不能把过期 token 续回来。logout 只撤销所选 Bearer，后续续期同样拒绝。

既有 `ExternalMediaAuthority` 通过 source ID、token ID、actor 逐次读取当前数据库期限；P3/B5 存储同一组 ID，因此合法同 ID 续期不需要替换媒体绑定。P3 既有 1800 秒会话上限、连接超时、控制 epoch、worker lease、设备同意和 B5 成员/room generation 规则均继续独立生效。续期不延长已经签发的短连接 ticket 或媒体读取 capability，也不使已终止会话恢复。

跨较长操作保留的 Principal 可以保存旧 `expiresAt`。重新鉴权后比较绑定使用 `ExternalIdentityService.sameBinding`，比较所有不可变身份字段并排除期限；是否仍有效始终以当前 `authenticateHash` 或媒体授权读为准。绝不能通过 owner 相同、tenant 相同或新 token 来替代旧 token ID/hash。

## 5. 失败与验收边界

新增失败包括 `RENEWAL_NOT_CONFIGURED`（503）、`INVALID_RENEWAL_LIFETIME`（400）、`RENEWAL_BINDING_MISMATCH`（403）；签名、nonce 重放、source 撤销/到期、token 撤销/到期、账号状态等沿用既有错误。未知 token/source 引用不给出业务数据。来源故障、密钥失效或配置不可用时失败关闭。

本机制复用现有表，无 schema 迁移，不删除或清空任何数据。新增测试只创建全新的 `xiaozhi_renewal_test_*` schema，并拒绝已存在 schema；保留测试证据，不执行清理。

验收覆盖同一 ID/hash 续期、来源期限上限、scope/tenant 不变、签名/nonce/轮换、禁用/撤销/过期、MySQL 续期与撤销竞态、真实行锁等待跨过 token 到期、以及媒体授权动态读取期限。可控时钟测试和隔离来源签名证明机制，不能代替实际 Laravel producer、用户撤权投递、真实长时通话或物理设备验收。
