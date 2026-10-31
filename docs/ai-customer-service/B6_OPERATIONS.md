# B6 配置、来源接入与隔离验证

本模块默认关闭；独立身份/会话和失败关闭的业务 adapter 契约不改变原有 aimeet v1。外部设备、工单和知识真实网络端点的接入仍需单独确认；新增外部媒体和自动听音的配置与验证边界见 [外部媒体与 VAD 运维](EXTERNAL_MEDIA_VAD_OPERATIONS.md)。

## 启用所需配置

- features.external-identity-enabled=false：默认值。聊天/上传/标注还需要原有conversation、AI、support、attachments、annotations各自开关；本模块不自动开启它们。
- external-identity.keys-json：默认空；可由EXTERNAL_IDENTITY_KEYS_JSON注入。格式为key_id -> {issuer,secret_b64}，两者名称都使用独立xiaozhi-命名空间，最多20个key；Base64必须标准、带正确padding、32..128字节随机密钥。不接受公开签名向量key，不同key_id不能共享同一key字节（包括同issuer，防止更换key_id重放同一nonce）。轮换同issuer新key_id可保留既有身份；删除该issuer全部key后其会话不能继续鉴权。
- external-identity.token-seconds=300：允许30..900秒，且Bearer绝不超过source_expires_at；票据最多60秒。不会沿用本地12小时token，也不提供绕过来源的refresh。
- 所有来源调用和App兑换/业务访问必须通过实际TLS入口；反向代理须保留这两个签名路径，不能重写后还要求原签名生效。canonical使用接收Servlet实际未编码requestURI，包括/xiaozhi等context，不依据客户端X-Forwarded-Prefix猜测或自动换路径。
- 部署需应用新增external-identity.changelog.yaml，包含202610310100.sql的7张追加表；不改已有changeset，不执行初始清库脚本。关闭新功能不删身份/撤销/票据/原会话数据。

密钥配置示意（仅占位符，不可直接启用）：

```json
{"xiaozhi-daleng-v1":{"issuer":"xiaozhi-daleng-dev","secret_b64":"<new private standard Base64 random key>"}}
```

不要把Laravel PAT、aimeet Cookie、LiveKit/worker密钥作为B6密钥，或把票据、Bearer、能力URL、原始body写入日志/终端/错误响应。

## 来源生产者仍需实现

Laravel必须新增独立的小智来源配置和调用，不能只把现有aimeet接口地址改掉。它应在当前Sanctum PAT行锁/有效性复查下，从真实User.id生成subject，从当前PAT派生独立source_session_id，并从成员关系构造tenant和有界scope。tenant类型customer/company参与身份命名空间；数字相同不代表同一租户。项目/设备ID只能由来源服务核对归属，不能原样签客户端自报范围。

issue body仅external_subject、source_session_id、source_expires_at、display_name、tenant、scope；无phone、role、local_user_id或外部URL。source_expires_at为UTC秒的整数/规范十进制字符串，在未来24小时内，来源长期会话需提供有界授权窗口。重复issue只能缩短已有source期限，不能延长或复活已撤销来源；续期应使用新的来源会话ID。

来源还须在账号停用、成员/项目设备授权撤销时可靠撤销受影响的来源会话；本仓动态检查的是本地账号/issuer/已接收的source状态，并未调用Laravel实时账号或成员查询。未完成来源事件/委托接口前，不宣称外部实时撤权已联通。

来源退出需先使自己的PAT失效，再通过持久outbox发送精确revoke并观察失败重试；未知来源revoke会留下墓碑而不创建用户。网络故障下不承诺跨系统瞬时撤销；接收端revoke事务完成后，其票据、Bearer和附件能力都失效。App成功换身份后必须清理当前连接/私人缓存并用新Bearer；失败不能静默回退为旧身份。该客户端改造不在本轮。

当前sys_user_token按用户唯一且可复用，B6不调用createToken或logout(userId)。每个来源Bearer只在external_access_token保存摘要。tenant principal为独立最小权限sys_user；同issuer/subject同tenant多来源会话共owner、不同Bearer；跨issuer/tenant/type用不同owner。Source撤销不删除聊天、不禁用整个人、不撤其他来源或本地密码会话。

## 允许能力和显式关闭项

允许自己的Conversation、消息、事件、AI run、上传、handoff/cancel/resume和静态标注，仍由原owner/feature gate校验。未知/legacy管理/配置/客服管理/worker路径拒绝，不能用external token跳本地认证fallback。

外部客户端读取媒体使用新/api/v1/external/attachments/{id}/access及其content短票；原/api/v1/ai/attachments/{id}/access/content对external Bearer拒绝。代理短票绑定当前Bearer，读取时重验来源和原AttachmentService资源授权，不返回原actor-only短票。SSE在每批写前重验捕获的source，媒体长输出每秒及慢读返回后重验；本地Bearer不增加该guard。

外部媒体默认关闭；开启 `features.external-realtime-media-enabled` 后，精确白名单中的 AI 实时与人工媒体路径受原功能开关和绑定的来源/Bearer 双重约束。客户可操作已绑定转接的指定方法，创建转接及坐席接受等未列出路径仍拒绝；只读 `media-drain` 在媒体开关关闭时仍可由有效外部身份查询本人来源/Bearer 的清理状态。旧 LiveKit JWT 不会瞬时失效；新房代次隔离及清理边界见 [外部媒体与 VAD 运维](EXTERNAL_MEDIA_VAD_OPERATIONS.md)。

设备/工单/知识仅有ExternalBusinessAdapter只读契约，未安装真实网络实现，允许的请求返回BUSINESS_NOT_CONFIGURED。未来adapter必须按真实来源用户/来源会话/租户重新验证具体记录归属，不能使用Workbench服务权限冒充用户。结果须有issuer、tenant、资源类型、真实来源记录ID及观察时间；不接正式工单提交、设备控制或模型自报事实。

## 可重复隔离测试

使用已有34776原生测试MySQL；只传一个全新xiaozhi_external_test_*名字，测试本身检查schema不存在再CREATE DATABASE，已有名字（即使空）直接拒绝。BeforeAll另拒绝非空表集合；全部行、原始fixture和失败现场保留，无teardown删除。测试密码仅通过环境传入，不保存到本文件。

```sh
export EXTERNAL_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:34776/xiaozhi_external_test_NEW_UNIQUE_NAME?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true'
export EXTERNAL_TEST_DB_PASSWORD='<isolated test credential>'
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests=false -Dtest='External*Test,AiStreamFailureTest,AuthenticationOutageTest,ShiroConfigTest' test
```

将NEW_UNIQUE_NAME替换为新的小写字母/数字/下划线随机后缀；不要重用已运行或失败的schema。无数据库时只运行ExternalProtocolTest、ExternalSpringWiringTest和现有纯单元测试，数据库类由环境开关跳过。

真实Spring装配测试使用AnnotationConfigApplicationContext，实际注入Settings、Controller、Boundary Filter和Shiro，并强制Service通过真实CGLIB委托代理执行，防止手动new掩盖跨bean公开字段问题；生产类没有@Repository公开jdbc字段。原生测试另覆盖UTC和driver Asia/Shanghai、每连接SET SESSION time_zone='+08:00'的匹配时区，验证60s ticket、300s Bearer和revoke，不改global/timezone表。统一shared TimestampColumnMapRowMapper读取时间，无LocalDateTime强猜UTC。
