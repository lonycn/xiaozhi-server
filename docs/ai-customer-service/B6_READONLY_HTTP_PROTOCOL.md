# B6 可信来源只读 HTTP 协议 v1

2026-10-31。本批新增的是本仓可配置的只读委托客户端，不是 Laravel 来源生产者，也不是设备、工单或知识已经接通的证明。`features.external-identity-enabled` 和 `external-business.enabled` 均保持默认关闭；未修改原 Dev18894/18893 运行配置、aimeet/Laravel/Kuikly、旧 SSO、已有数据库或卷。外部身份 RTC/P3 禁止边界不变，`identity/me.business_connected` 继续为 `false`。

## 已确认的来源源码和未确认的运行入口

只读检查的实际 Laravel 仓库为 `/Users/lengwang/code/ui_aichat/aichat_laravel`；Kuikly 的 `Config.kt` 仍区分 iOS 开发默认 `http://aichat_plus.test` 与 Android 模拟器默认 `http://10.0.2.2:8000`。这些客户端默认值不是本批确认可用的业务来源地址。

| 资源 | 已有源码与授权方式 | 来源生产者必须保留的检查 |
| --- | --- | --- |
| 工单 | `routes/api.php` 的认证 `GET work-orders/{workOrderId}`；`app/Http/Controllers/Api/WorkOrderController.php::show` 使用 `accessibleWorkOrderQuery`，当前客户存在时按 `customer_id`，否则按 `user_id` | 从委托 subject 找真实 User，确认仍有效的原登录会话及选中 tenant 成员资格，再核工单归属。公司租户不能直接套用客户/个人查询。 |
| 工单项目 | `WorkOrder` 没有 `project_id`；`WorkOrder::serviceOrders`、`ServiceOrder::coldChainContext` 和 `ColdChainServiceOrderContext.project_id` 提供可核实的关联 | 需要来源逐条核实真实项目和 signed scope 交集，不能把 `asset_id`、`system_id` 或客户端自报 ID 当项目。当前协议不返回无法证明项目归属的工单。 |
| 设备档案 | `routes/api_customer.php` 的客户 `cold-chain/equipments/{id}`；`Api/Customer/ColdChainController::equipmentShow` 先 `accessibleCustomerIdForEquipment`，再 `currentCustomerCanAccessEquipment(...PERMISSION_VIEW_EQUIPMENTS)` | 同时核设备所属 customer、真实项目成员权限与设备范围；公司/工程师入口各有自己的业务权限，不按相同数值 ID 混用。 |
| 项目设备范围 | `ColdChainEquipment::scopeForProject`、`Services/ColdChain/ColdChainEquipmentScopeResolver` 核 customer/project、资产、系统和 selected 设备范围 | 已签 project/device scope 是上限；来源仍须实时按这些真实关系重验具体设备。缺省 scope、空 project 参数或单独查设备 ID 都不能替代授权。 |
| 知识 | `Services/Knowledge/KnowledgePrincipalFactory::authenticated` 从真实 User、有效角色与成员关系解析 tenant；`KnowledgeSearchService` 包含 authorized knowledge-base 命中复核 | 用真实用户 principal 调来源知识授权/检索链并验证每个引用，不能使用 Workbench 服务权限冒充该用户。`routes/internal_knowledge_authority.php` 的 `retrieval-experiments` 不是本协议来源端点。 |

本轮未发现 Laravel 的小智独立签名 issue/revoke 或本协议只读 delegate 路由。运行只读检查中，本机无 8000 监听；8005 PHP 的实际 cwd 是 `/Users/lengwang/code/compass/compass_backend`，不是该 Laravel；本机 nginx 用 `Host: aichat_plus.test` 请求工单路径返回 404。这些仅定位当前入口，不证明正确业务服务故障，也没有据此修改服务或猜测端点。没有取得新的可信来源 URL、业务签名密钥或真实身份测试授权，故没有真实业务网络验收。

## 配置与默认关闭

三个独立配置由 `ExternalBusinessSettings` 读取，不自动启用原有聊天、附件、AI 或任何媒体开关：

```properties
external-business.enabled=false
external-business.sources-json=
external-business.stale-seconds=300
```

安装 HTTP adapter 需要 `features.external-identity-enabled=true` 和 `external-business.enabled=true` 同时成立。空来源配置或当前 issuer 没有来源映射时仍返回 `503 BUSINESS_NOT_CONFIGURED`，不会发出网络请求。错误来源配置返回 `503 BUSINESS_CONFIGURATION_INVALID`。

`sources-json` 结构如下，所有占位符都必须由来源接入方确认后替换；这里没有可直接启用的来源端点或密钥：

```json
{
  "xiaozhi-<identity-issuer>": {
    "endpoint": "<confirmed complete HTTPS read-delegation URL>",
    "key_id": "xiaozhi-business-<independent-key-name>",
    "secret_b64": "<new private standard Base64 key>"
  }
}
```

- issuer 必须与已认证 B6 来源 issuer 完全相同；最多 20 个来源。配置 JSON 拒绝重复、未知字段及尾随对象。
- 端点必须是管理员明确配置的完整 HTTPS URL；禁止 userinfo、query、fragment、编码路径、点路径和 0/越界端口。路径仅允许由 `/` 分隔的字母、数字、下划线、连字符段。不会从客户端参数或来源响应读取 URL，也不猜 Laravel 路由。
- key_id 格式为 `xiaozhi-business-[A-Za-z0-9_-]{1,64}`。来源密钥为新生成的 32..128 字节、标准规范 Base64；不同来源不能共享相同字节，也不能与任何当前 identity key 共享字节。不能使用 Laravel PAT、aimeet Cookie、LiveKit/worker 密钥或公开测试向量。
- stale-seconds 允许 1..86400，默认 300。它控制显式陈旧标记，不会把旧采样时间改写为当前查询时间。
- 本仓使用标准 TLS 验证；没有关闭证书或主机名验证的运行选项。私有 CA 必须由部署方在实际 Java TLS 信任链中正确配置。本批没有修改系统/Java 信任库。
- 应在日志存储策略中保护现有应用审计日志。新日志不记录 query、原始请求/响应、subject、来源 session、Bearer 或密钥。

## 请求：签名的固定只读 POST

来源协议仅允许 `device`、`work_order`、`knowledge` 三种查询。POST 仅用于传递私有委托上下文，不授权来源写工单、控制设备或执行任意工具。公开调用方仍使用本仓已有 GET 设备/工单/知识接口与 B6 Bearer。

来源必须从 request 中的 issuer + subject + source_session_id 找到真实来源用户和原登录会话，重验账号、会话、租户成员、项目/设备范围和具体资源权限。来源必须拒绝未知/停用/过期/撤销的会话；不能把“签名正确”直接视作业务授权。signed scope 只提供上限，不能替代实时来源授权。

请求 Content-Type 与 Accept 均为 `application/json`，Accept-Encoding 为 `identity`。以下 JSON 仅为合成协议示例：

```json
{
  "version": 1,
  "issuer": "xiaozhi-source-example",
  "external_subject": "user:7",
  "source_session_id": "source-owned-session-id",
  "tenant": {"type": "customer", "id": "8"},
  "scope": {"project_ids": ["9"], "device_ids": ["10"]},
  "operation": {"kind": "device", "resource_id": "10", "query": null},
  "requested_at": 1793404800,
  "expires_at": 1793404830
}
```

`work_order` 同样使用 resource_id、query=null；`knowledge` 使用 resource_id=null 和非空、最多 1000 字符 query。资源 ID 为 `[A-Za-z0-9_.:-]{1,100}`。device 必须在 signed device_ids 中；work_order 必须有非空 signed project_ids。source_session_id 从本仓原来源会话行按 source_id + issuer + subject 只读查询，不用本仓数值 source_id 冒充来源 PAT/session ID，不发送本仓 token 摘要。

requested_at/expires_at 为 UTC Unix 秒整数；expires_at 为 `min(本仓Bearer到期, requested_at+30秒)`。来源除校验签名时间窗外，还必须检查这个委托有效期。请求体最多 64KiB。

每个请求恰有一个下列签名头：

```text
X-Xiaozhi-Business-Key-Id: <configured independent key_id>
X-Xiaozhi-Business-Timestamp: <UTC Unix seconds decimal>
X-Xiaozhi-Business-Nonce: <32 lowercase hex>
X-Xiaozhi-Business-Signature: <64 lowercase hex HMAC-SHA256>
```

来源接收方必须严格拒绝重复头、超出 60 秒时间窗、无效签名和原子占用失败的 `(key_id,nonce)`；防重放状态须支持实际多实例部署。错误签名不得先占 nonce。来源 producer 尚未实现，所以本批没有宣称该来源防重放逻辑已部署。

canonical 为以下字段的 UTF-8 字节，用单个 LF 连接，末尾没有 LF：

```text
XIAOZHI-BUSINESS-READ-V1
POST
<exact configured endpoint raw path, including any real server context>
<X-Xiaozhi-Business-Timestamp>
<X-Xiaozhi-Business-Nonce>
<lowercase hex SHA256(raw request body bytes)>
```

signature = lowercase hex HMAC-SHA256(decoded independent secret, canonical)。不排序或重新编码签名 body，不信任 X-Forwarded-Prefix，不接受改写/编码路径别名。来源部署必须保留双方约定的实际签名路径。

## 成功响应：与该次请求绑定的来源证据

只有 HTTP 200 可以形成 Evidence；成功响应 Content-Type 必须是 `application/json`，可带 UTF-8 charset，禁止压缩。最大响应 256KiB，流式接收时即限制分配；不重定向。连接超时 3 秒，整个请求加完整响应体最多 5 秒，超时取消异步请求。这里特别覆盖 JDK21 收到 headers 后原 HttpRequest.timeout 不再约束慢 body 的情况。

成功响应恰有一个下列头：

```text
X-Xiaozhi-Business-Timestamp: <UTC Unix seconds decimal>
X-Xiaozhi-Business-Signature: <64 lowercase hex HMAC-SHA256>
```

响应签名 canonical 同样以 LF 连接、末尾无 LF：

```text
XIAOZHI-BUSINESS-RESPONSE-V1
<the request X-Xiaozhi-Business-Signature>
<response X-Xiaozhi-Business-Timestamp>
200
<lowercase hex SHA256(raw response body bytes)>
```

响应时间窗为 60 秒；比较使用恒时字节比较。绑定的是原请求签名，因而同一响应不能移用到不同 nonce、查询或身份上下文。TLS 验证与响应签名都必须通过。

设备响应合成示例，JSON 恰含以下字段：

```json
{
  "version": 1,
  "issuer": "xiaozhi-source-example",
  "external_subject": "user:7",
  "source_session_id": "source-owned-session-id",
  "tenant": {"type": "customer", "id": "8"},
  "project_ids": ["9"],
  "resource_type": "device",
  "source_record_id": "10",
  "observed_at": "2026-10-31T00:00:00Z",
  "data": {"id": "10", "name": "合成协议示例，非真实设备"},
  "references": []
}
```

issuer、subject、source_session_id、tenant.type/id、resource_type 必须与本次可信上下文/查询完全匹配；device/work_order 的 source_record_id 必须与请求 ID 一致。source_record_id 只能是真实来源记录 ID，不由 adapter 或模型生成。data 必须为 JSON object 或 array，不能为 null。响应拒绝重复字段、未知字段、尾随对象和无效 JSON。

project_ids 为去重字符串集合、最多 128 项，必须是 signed project_ids 的子集。work_order 必须至少有一个已核实项目；device 在 signed project_ids 非空时也必须提供非空交集。知识可以属于租户全局而 project_ids 为空，但这必须由来源的真实知识 ACL 允许，不能以空数组绕过来源授权。

observed_at 必须为可解析的 ISO Instant，不早于 Unix epoch，不能超过本仓当前时间 5 秒。它表示真实业务记录/采样的观察时间；传感器数据不能把本次读取时间冒充真实采样时间。adapter 单独输出 received_at，并按 stale-seconds 标 stale；陈旧值仍保留原时间，不能充当当前温度/报警事实。

知识响应使用同样 envelope，resource_type=`knowledge`，references 必须 1..20 项，每条恰含 source_record_id、observed_at、project_ids、data(object)。root source_record_id 必须等于首个真实引用 ID；每个引用项目必须是 root 项目的子集。示例中的记录 ID 与内容均是合成演示：

```json
{
  "version": 1,
  "issuer": "xiaozhi-source-example",
  "external_subject": "user:7",
  "source_session_id": "source-owned-session-id",
  "tenant": {"type": "customer", "id": "8"},
  "project_ids": ["9"],
  "resource_type": "knowledge",
  "source_record_id": "chunk:123",
  "observed_at": "2026-10-31T00:00:00Z",
  "data": {"summary": "合成示例；实际内容须由已授权真实来源提供"},
  "references": [
    {
      "source_record_id": "chunk:123",
      "observed_at": "2026-10-31T00:00:00Z",
      "project_ids": ["9"],
      "data": {
        "knowledge_base_id": "3",
        "document_id": "45",
        "chunk_id": "123",
        "excerpt": "合成示例引用，非真实知识检索结果"
      }
    }
  ]
}
```

空知识结果来源返回 HTTP 404；不能制造 search-result ID 当真实知识记录。每个引用保留自身观察时间和独立 stale 标记。来源负责所有返回 payload 与引用都来自其真实授权记录，不能在 data 中混入未授权记录或模型推测的“事实”。

对外 Evidence 保留现有 issuer/tenantType/tenantId/resourceType/sourceRecordId/observedAt 字段；data 规范封装为 `{record,project_ids,references,stale,received_at}`。record 为来源 data，references 逐项追加 stale；不能把 received_at 当 observedAt。adapter 等待网络期间及交付前重验本仓 Bearer/source，配置关闭/旋转或身份上下文变化会丢弃迟到结果。

## 明确失败与审计

没有凭据 fallback、模型猜测 fallback、静态设备/温度/工单/知识结果、来源重试或替代 Workbench 查询。失败响应不回传来源原始 body、URL、凭据或异常细节。

| HTTP | 本仓 code | 含义 |
| --- | --- | --- |
| 400 | BUSINESS_OPERATION_INVALID | 非白名单只读操作。 |
| 400 | INVALID_RESOURCE_ID / INVALID_KNOWLEDGE_QUERY | 本仓请求格式无效。 |
| 404 | RESOURCE_NOT_FOUND | 请求设备不在 signed scope、工单缺项目 scope，或来源返回 404。 |
| 403 | BUSINESS_SOURCE_ACCESS_DENIED | 来源返回 401/403；不回退旧会话。 |
| 503 | BUSINESS_NOT_CONFIGURED | 默认关闭、空配置或当前 issuer 未配置来源。 |
| 503 | BUSINESS_CONFIGURATION_INVALID | URL/key/JSON/陈旧阈值无效，密钥重复或与 identity key 复用。 |
| 503 | BUSINESS_CONFIGURATION_CHANGED | 等待来源期间 endpoint/key_id/key 字节改变。 |
| 503 | BUSINESS_CONTEXT_INVALID | 来源 session 无法按真实身份唯一解析或可信上下文变化。 |
| 503 | BUSINESS_SOURCE_SIGNATURE_INVALID | 完整响应签名无效/过期/绑定了其他请求。 |
| 503 | BUSINESS_SOURCE_RESPONSE_INVALID | 响应头/类型/编码无效，或完整响应交付后的大小检查不通过。 |
| 503 | BUSINESS_PROVENANCE_INVALID | JSON、真实 ID、身份/租户/项目、观察时间、逐引用等不满足契约。 |
| 503 | BUSINESS_SOURCE_RATE_LIMITED | 来源返回 429。 |
| 503 | BUSINESS_SOURCE_UNAVAILABLE | 网络/TLS/连接/完整 body 超时、流式大小限制中止、依赖异常、重定向、其他状态等；不泄漏来源细节。 |

本仓 identity 的账号、issuer、来源或 Bearer 撤销/过期错误继续按原身份错误码返回，不转换为业务成功。收到来源错误不会将任意错误 body 当 Evidence。成功审计包含本仓 audit_id、数值 actor_id、issuer、tenant、project_ids、kind、真实记录 ID、观察时间与 stale；失败审计只包含 audit_id 和规范 code。审计不等于已部署持久来源授权检查，未新增或修改业务数据库。

## 验证与剩余接入条件

本轮最终定向 36 项通过、0 failure/error/skipped：`ExternalBusinessHttpAdapterTest` 22 项、既有 `ExternalProtocolTest` 13 项、`ExternalSpringWiringTest` 1 项。日志为 `/tmp/xiaozhi-b6-business-unit-final.log`，JUnit 结果在 manager-api/target/surefire-reports。该组不需要数据库，也没有启动业务服务。

覆盖默认/双 feature Spring 装配、严格配置与独立密钥、独立 Python 算出的请求/响应 HMAC 固定向量、nonce 绑定响应不可移用、设备/工单/知识范围与逐引用、陈旧和未来时间、等待期间 source 撤销和配置旋转、来源拒绝/故障无 fallback、重复/无效头与 JSON、压缩/大小限制、不重定向。真实 loopback HTTP 传输检验原始签名字节和委托字段；生产 transport 另对“快速 headers 后永久不完整 body”实测 5 秒 deadline 并 cancel。仅测试 transport 将配置的 HTTPS 测试 origin 映射到合成 loopback HTTP；生产代码没有 HTTP/跳过 TLS 的配置。

初次专属测试中的 Mockito/Spring 生命周期断言失败已修正；后续一轮共享 target 并发编译失败未计通过，协调互斥后最终 36 项通过。没有 clean 数据库、删除行或清空任何卷，也没有以单元/合成 HTTP 测试宣称真实来源业务连通。

真正联调仍需：来源方提供完整 TLS origin/path 与独立业务密钥；新增并部署本协议来源 producer；来源 issue/revoke/PAT 失效及成员撤权可靠投递；subject/session/tenant/project/device/知识 ACL 的实时映射和授权；独立真实用户、真实记录、跨租户拒绝与撤权/来源故障验证。温度、历史报警、位置、专家检索未确认专属真实 API，不能从设备档案接口推定已具备。本批没有修改源仓配置或 SSO，也没有替用户猜测这些信息。
