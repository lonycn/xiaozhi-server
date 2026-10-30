# B6 外部身份与只读业务契约

日期2026-10-30；独立codex/b6-identity，基线0efcb91。默认features.external-identity-enabled=false。不修改aimeet/Laravel/Kuikly及其原SSO v1、Cookie、用户表、共享配置。只追加本仓增量表，所有身份、票据、nonce、撤销、会话行保留；不删除历史或业务数据。

## 身份及租户

来源key_id由受保护配置映射到固定issuer；新增issuer与key_id均使用xiaozhi-独立命名空间，密钥至少32字节标准Base64，不与原SSO/用户/worker/LiveKit共享。拒绝公开测试向量密钥部署。身份键(issuer,external_subject)，绝不按电话、姓名、username或相同数值ID合并；客户端不能指定本地user_id、角色、owner、租户或映射。

来源服务必须从当前认证用户、当前仍有效的登录会话和数据库成员关系构造签名上下文。tenant={type:customer|company,id:正long十进制字符串}必需，scope={project_ids:[],device_ids:[]}为已授权的有界字符串集合。来源会话不是IoT设备ID，扫码/位置/手机device_id/ESP32 MAC不产生资源授权。scope是来源认证声明，不是本仓已验证外部业务连通的证明。

当前Conversation无tenant列，故采用identity->tenant principal映射：(issuer,subject,tenant.type,tenant.id)对应独立最小权限sys_user，不同tenant绝不共用本地owner；同一可信tenant下同subject的A/B来源会话共用owner但使用独立Bearer。tenant类型包含在命名空间中。映射只能服务端创建，不自动关联现有本地或aimeet账户；不授予客服/专家/管理权限。

## 原始体签名、票据与精确撤销

新端点（与原Daleng v1分离）：
- POST /api/v1/external/identity/issue：来源服务调用，body严格为external_subject/source_session_id/source_expires_at/display_name/tenant/scope。source_expires_at为UTC Unix秒（整数或规范十进制字符串），须在当前未来24小时内；有效来源更长期时，来源应签发有界授权窗口。
- POST /api/v1/external/identity/exchange：原生客户端HTTPS提交{ticket}，不需要也不接收Laravel PAT/aimeet Cookie；返回本仓来源绑定Bearer和到期/本人上下文。
- POST /api/v1/external/identity/revoke：来源服务调用{external_subject,source_session_id}；幂等204，无匹配时只保留撤销墓碑，不创建用户。
- GET /api/v1/external/identity/me 与 POST /api/v1/external/identity/logout：本仓Bearer，logout仅撤当前Bearer，不撤其他来源/本地登录。

签名头X-External-Key-Id/X-External-Timestamp/X-External-Nonce/X-External-Signature各恰一个；timestamp误差<=60秒，nonce为32小写hex，signature为64小写hex。原文为POST、精确固定path（真实未编码requestURI，含配置的servlet context如/xiaozhi）、timestamp、nonce、SHA256(raw body)用LF连接且末尾无LF；HMAC-SHA256使用严格Base64解码后的key字节，恒时比较。禁止query、编码路径别名和重复头；body<=32KiB，验签后才解析严格未知字段JSON。nonce原子唯一占用（记录120秒有效窗，行保留），错误签名不占nonce；重试必须新nonce。

票据256bit随机，仅存SHA256摘要，60秒、一次消费；与nonce防重放不同。exchange原子检查来源/账号/租户principal/过期/消费并创建随机独立Bearer，只存摘要。Bearer有效期默认300秒且不超过来源到期；没有沿用local createToken(userId)的用户级共享token，也没有来源绕过的refresh。新票据需要来源再次认证签发。

issue/exchange/revoke首先锁同一(issuer,subject,source_session_id)行。revoke为持久墓碑，拒绝之后到达的旧issue以及已签未消费ticket，撤销该来源的全部外部Bearer及其附件能力；其他来源、其他issuer/tenant用户及local密码token不变。重复issue不能延长既有source到期。每次请求动态重验feature/issuer信任、source、Bearer及sys_user状态/最小权限；依赖失败返回503，不能当作有效授权。来源必须先撤自身PAT、可靠投递撤销并观察失败；不声称网络故障下跨系统瞬时撤销。

## 路由及长期能力

外部用户可正常创建/读取自己的Conversation、发消息、上传附件、调用本人handoff/cancel/resume、AI run停止/重试、截图标注；仍受现有feature gate、owner/assignment及事务验证。只读限制专指外部设备/工单/知识业务，不限制自己的聊天。

外部Bearer使用精确方法+路径白名单，未知路径及所有legacy管理/配置/用户刷新/worker/客服管理/抢单接口拒绝；不能通过本地认证fallback绕过。source Bearer不能使用原匿名附件access/content票。新增GET /api/v1/external/attachments/{id}/access?variant=...与content?ticket=...代理：随机摘要能力绑定当前Bearer、source、资源、variant、60秒；每次content重新验source/Bearer及现有AttachmentService动态资源授权，原存储key/长期凭据不返回。

同步SSE在初始取数和每批写前重新检查捕获的外部Bearer/source，撤销或依赖失败即停止，local请求guard为空操作，保留现有503/游标恢复语义。附件长输出同样逐周期重查source。仅actor检查不构成来源会话授权。

本轮不含B5，external的media-session/transfer/RTC/token路径明确拒绝SOURCE_SESSION_RTC_UNAVAILABLE。未来必须将member/token/ready绑定external source session并解决generation fencing与来源撤销；当前不签发RTC token，不能假称旧LiveKit JWT即时撤销。

## 只读业务适配

提供严格TrustedContext与只读adapter契约，默认不可用，缺配置返回BUSINESS_NOT_CONFIGURED，不返回虚构设备/温度/工单/知识结果。拟公开GET /api/v1/external/business/devices/{id}、work-orders/{id}及knowledge?q=...；设备先检查已签scope，所有资源还必须由真实来源按用户/租户验证归属。实际来源端点、凭据、字段和TLS未确认前不启用网络实现。adapter结果须标来源、真实记录ID、观察时间和租户，不能把模型推测当已确认业务事实。写工单/控制设备不在本批。

源码候选是Laravel认证工单详情/设备档案权限服务及KnowledgeSearchService；现有knowledge retrieval-experiments是Workbench服务权限，不能直接代替本仓用户的tenant授权。WeKnora、IoT温度/报警未确认可用端点。

## 验证与交付边界

只在127.0.0.1:34776新建xiaozhi_external_test_*数据库，已有schema拒绝，所有测试行/失败现场保留；不DELETE/TRUNCATE/DROP或操作Dev。覆盖公开向量、raw体篡改、nonce/ticket并发重放、revoke并发/墓碑、撤A保留B及local、跨issuer/tenant owner隔离、未知路径、动态账号/issuer撤销、SSE及source-bound附件能力。测试和构建不是Laravel/Kuikly/外部业务连通验收。实际联调需独立签名key、TLS origin、来源新增issue/revoke调用和持久投递、可信成员/scope解析、真实只读资源授权端点；默认不复用原aimeet配置或业务会话。

## 时间与raw-body实现补充

时间列统一用shared TimestampColumnMapRowMapper的ResultSet.getTimestamp遵循JDBC连接时区，再取Instant；不把DATETIME的LocalDateTime强当UTC。隔离测试另覆盖driver Asia/Shanghai且每个测试连接仅SET SESSION time_zone='+08:00'，不改global/timezone表。所有Map日期读法均使用该mapper。

全局XSS过滤保留。B6最前置BoundaryFilter在两个签名入口和exchange读取前先执行32KiB上限（含未知Content-Length），缓存有界body；XssFilter仅精确排除issue/revoke POST且无query，验签后才严格解析JSON（拒绝重复/尾随对象）。其他路径仍走原XSS，不按整个external前缀排除。与B5后续exact webhook排除合并时必须保留双方精确规则。

公开JSON测试向量保存在[B6_SIGNING_VECTOR.fixture](./B6_SIGNING_VECTOR.fixture)（仓库全局忽略*.json，故使用.fixture后缀；内容仍是严格JSON），该已知key禁止用于部署。
