# B6 验证记录

2026-10-30，独立worktree /Users/lengwang/code/esp32/work/b6-identity，codex/b6-identity，基线0efcb91。本记录区分本仓接收面与外部来源/业务连通；没有启动HTTP服务、调用真实业务网络、读取.env、提交或推送。

## 已通过

验证集合共75项：ExternalIdentityPersistenceTest 12，ExternalProtocolTest 13，ExternalSpringWiringTest 1，以及原Conversation/鉴权/认证存储故障/Shiro权限/AI协议与SSE/附件规则49项。实库R6完整集合74项通过后，收严重复secret的key_id别名配置并补无关JWT包含xext_时的隔离测试，最终协议+真实Spring装配14项定向回归通过；后续没有改变业务状态机/SQL。

- HMAC公开新向量、原始中文/<>&/空白字节、路径/时间窗/重复头/尾随JSON/未知role与租户字段检查。全局XSS保持启用，实际XssFilter链验签通过，32KiB超界在XSS读取前拒绝。
- 实际/xiaozhi servlet context的完整requestURI参与签名；MockMvc签发→原子exchange→重复exchange 409通过，不把手动new方法调用当部署联通。
- 8路相同nonce仅一次签发、同ticket仅一次exchange。不同nonce/不同source首次并发创建同identity+tenant只留下一个sys_user/principal，无孤立账号。
- issue/exchange/revoke共源锁；revoke/exchange竞态结束后没有可用来源token；先revoke未知身份再晚到issue明确拒绝且不创建用户。
- 同subject同tenant A/B不同Bearer且共owner；撤A保留B、本地密码token和sentinel。company:1/customer:1及不同tenant/issuer映射不同owner，跨owner读取404；同名用户不合并。
- 动态本地账号停用/特权冲突、issuer关闭、来源与Bearer过期、单Bearer logout；所有票据/会话/nonce/撤销行保留。
- Source-bound附件cap真实私有filesystem+JPEG fixture读取后撤源拒绝，其他来源可继续；SSE每批source guard关闭被撤连接，原始503认证存储故障与游标/容量语义回归通过。
- driver Asia/Shanghai + 每连接session time_zone='+08:00'验证真实60秒票据、300秒Bearer到期和撤销，无额外8小时。shared TimestampColumnMapRowMapper按getTimestamp读取，未修改global时区或导入timezone表。
- AnnotationConfigApplicationContext实际装配Settings、Controller、Filter和Shiro，Service通过实际CGLIB委托代理执行HMAC/鉴权/controller流程。生产模块无@Repository公开jdbc字段，跨bean仅方法调用；不是仅手动构造的代理外测试。
- 未配置业务adapter明确BUSINESS_NOT_CONFIGURED；非法设备scope、跨tenant结果provenance拒绝。RTC路径明确SOURCE_SESSION_RTC_UNAVAILABLE。无关LiveKit JWT即便内部包含xext_子串也不被来源过滤器误接管。

测试输出：/tmp/xiaozhi-b6-native-r6.log，/tmp/xiaozhi-b6-final-unit-r3.log；target/surefire-reports包含具体JUnit结果。diff --check通过。测试均使用假身份/公开测试向量/合成图片，不是现场数据。

## 保留的隔离数据库

所有数据库仅位于127.0.0.1:34776，前缀xiaozhi_external_test_；没有清理或操作Dev/其他业务schema。

| 后缀 | 状态 |
|---|---|
| cb81e07e57454a7d | 初次新changeset的sqlFile classpath路径失败现场，保留；已改为相对路径 |
| bbf2d5ba2c3445d9 | 第一轮实库通过，保留 |
| d911ed2936dc46a5 | 扩大鉴权回归通过，保留 |
| 14ae9d4d7979478d | 新并发测试捕获principal/account FK锁顺序死锁，失败现场保留 |
| af577aaf9cef430d | exchange统一principal→account锁顺序后通过，保留 |
| 26dc019276e74b84 | 最终R6实库+鉴权回归通过，保留 |

最终测试本身先核schema不存在再CREATE DATABASE；已有名字（即使空）直接拒绝。额外用本次schema验证再次初始化被拒绝且行数/sentinel不变。所有失败现场均未重置、删除或复用。

## 实施边界

此批完成manager-api可信接收、映射、独立Bearer及精确来源撤销基础，并非Laravel/Kuikly已经切到新平台。来源新增issuer/key、PAT锁定签发、可信tenant/scope解析、账号/成员撤权的可靠revoke投递、客户端身份/缓存切换和实际TLS入口仍需单独配置与真实联调。没有读取原aimeet或Laravel凭据，也没有改变旧SSO接口/会话。

真实device/workorder/knowledge delegate端点与用户授权契约尚未确认，未实现网络adapter，不返回假数据、不宣称IoT/WeKnora已连通。本分支不含B5；source-bound RTC身份未完成之前维持拒绝。SSE/附件撤销单测不等于已签LiveKit JWT可撤销。

合并时需保留B5与B6在XssFilter/ShiroConfig上的双方精确规则、对应新changelog includes，以及父分支统一时间mapper；不能用一方覆盖另一方。没有Vue/Python/Dev运行脚本变更。
