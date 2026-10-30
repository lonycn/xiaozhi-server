# 内部模型交接摘要：验证记录

日期：2026-10-31。接口、进程和操作边界见 [运维说明](./HANDOFF_ASSISTANCE_OPERATIONS.md)。本记录区分源码测试、原 Dev 部署、浏览器技术样本和真实业务验收，不将它们混为完成状态。

## 已完成的源码验证

| 验证集 | 结果及范围 | 证据 |
| --- | --- | --- |
| Java 全量 | 502 项，0 失败/错误/跳过；全量 package 成功。 | `/Users/lengwang/code/esp32/work/integrated-20261031-java.log` |
| 最后边界修改后的摘要定向 RR 复验 | 29 项，0 失败/错误/跳过：持久化 20 项（新辅助任务 10 项、继承原摘要 10 项），新增边界 3 项、原控制器 3 项、原装配 3 项。 | `/Users/lengwang/code/esp32/work/summary-assistance-test-latest.json` 及其 log |
| 最终 JAR | 复验后再次 package 成功；部署协调者核对 JAR 中迁移与源码逐字匹配。该打包步骤不另计测试数。 | `/Users/lengwang/code/esp32/work/summary-final-package-20261031.log` |
| 普通 AI 与独立摘要 worker | 最终 unittest 48 项通过，其中既有 39 项、摘要 9 项；包含真实失败结构不被 worker 静默映射或抹掉核验说明的回归，没有改普通 worker 的核心循环。 | `/Users/lengwang/code/esp32/work/summary-python-promptfix-20261031.log` |
| Dev 管理 | 43 项通过，包含独立 summary 启停与进程身份边界。 | `/Users/lengwang/code/esp32/work/summary-dev-all-20261031.log` |
| Vue 单元/组件 | 最终 406 项通过，覆盖初始化请求恢复与历史过期文案修复；早期 402 项是修复前基线，不另累加。 | `/Users/lengwang/code/esp32/work/summary-vue-final-20261031.log` |
| Vue 其他检查 | 10 项 contract、13 项 snapshot 通过；6 种语言各 1840 key 检查通过；最终生产构建通过。 | 最终 Vue log，以及 `/Users/lengwang/code/esp32/work/summary-vue-snapshot-final-20261031.log`、`summary-vue-i18n-final-20261031.log`、`summary-vue-build-final-20261031.log` |

上述集合分别报告，不把定向 RR 的 29 项再次加到全量 Java 的 502 项中，也不将技术样本当作真人或设备验收。

RR 测试只使用 `127.0.0.1:34776` 的新建唯一 schema，创建前验证不存在；未使用、清理或重置原 Dev schema。最终复验为 `xiaozhi_summary_test_assistance_20261031002532_12cc84`，隔离级别 `REPEATABLE READ`，JDBC 时区 `Asia/Shanghai`，退出码 0。成功与早期失败 schema、行和日志均保留。首次真实 MySQL 运行发现 DATETIME 返回 `LocalDateTime` 的强转错误；修复使用项目 `TimestampColumnMapRowMapper` 后复验，不采用本地 UTC 假设。

完整 Java suite 中原摘要和辅助摘要测试分别使用 `SUMMARY_TEST_JDBC_URL/DB_PASSWORD` 与 `SUMMARY_ASSISTANCE_TEST_JDBC_URL/DB_PASSWORD` 的两个新 schema，避免父子测试重复建表。父子继承的原摘要断言重复运行属于测试覆盖，不是新增业务案例。

## 针对性边界

- 真实数据库验证幂等键、不同请求的并发触发只有一个 pending 任务；基础摘要版本冲突不会覆盖更新。
- 任务期间新消息、客服编辑、转派、同客服重新领取、profile 撤权会阻断旧租约和迟到结果；到期租约可重新领取，旧 token 不能完成。
- 完成建议在后续上下文变化后标为 stale，保留结果 revision、建议和审计信息；重试限于 failed、当前快照和最多三个 attempt。
- 客户 owner、无关客服、旧 assignment 及外部身份不能创建或读取辅助任务；默认 feature 关闭时不装配辅助服务。普通 AI 开启不能隐式开启辅助服务。
- 伪造消息/seq/引用、模型擅自核验事实及来源归属错误不能保存。客服可编辑同时原样保留模型推测，但不能编造新的 AI 来源。
- provider 请求的真实 SDK 序列化验证配置模型、JSON 模式、6000-token 输出上限、不可信 source envelope 和不提供工具；该测试使用受控 HTTP transport，不是实际百炼调用。
- Python 验证缺少 usage、截断输出、畸形/重复键 JSON、模型不匹配等明确失败；已返回 usage 的畸形 JSON 仍回报消耗审计。心跳撤权取消生成，不发送完成结果。
- 持久化断言生成只增加内部 revision/job/attempt，不新增客户消息，不调用业务写入或设备控制。

## 原 Dev 部署与数据基线

部署前备份证据：`/Users/lengwang/code/esp32/work/summary-assistance-before-deploy-20261031.json`。SQL 备份保留在 `dev/.runtime/backups/before-summary-assistance-20261030T162141Z.sql`，483779 bytes，SHA256 为 `35524fb2a8012482af1202785d2491e22b7f80f2b4ed2812c2902af3c1f2ab16`。基线为用户 4、旧设备历史 25、消息 27、迁移 116，active media/realtime 均为 0。

部署协调者已在原 Dev 仅 recreate API，应用新增迁移到 117；没有清库、重建数据库、改已有用户或删除原卷。源码默认开关仍为 false，本次 Dev 辅助功能为明确启用。进程或开关状态不代替后续实际 provider 结果。

部署后的 HTTP 权限检查证据：`/Users/lengwang/code/esp32/work/summary-real-boundaries-20261031.json`：

| 身份与操作 | 结果 |
| --- | --- |
| 客户 p2 读取辅助能力 | HTTP 404，业务 code 404。 |
| 已转出的旧客服 A（p3）读取辅助能力 | HTTP 404，业务 code 404。 |
| 当前客服 B（p4）读取辅助能力 | HTTP 200，`enabled=true,latest_job=null`。 |

该次检查时，用户仍为 4、旧设备历史仍为 25，消息为 28、迁移为 117、summary job 为 0。消息从 27 到 28 的增加对应下一节明确声明的技术样本；这是生成触发之前的检查点，不是后续最终计数。

## 浏览器与实际百炼技术样本

技术输入证据：`/Users/lengwang/code/esp32/work/summary-real-input-20261031.json`。使用已有 QA 会话 `3236f34f9a294c3aa44afbdc986b02db` 新增 seq 5，message ID `75e6ff82b6414c7dadc4c10c70517873`。内容明确以“技术验收样本，无真实设备”开头，描述温度显示 -12℃、设定 -18℃、10:20 报警、已检查库门但未核验传感器/实际库温/压力，并要求追问信息、不执行设备启停或生成实际工单。该文本不是实际遥测、真实客户故障或现场证据。

浏览器初始加载消息时，原 capability 请求被 abort 后未恢复；前端修复该竞态；部署协调者报告修复后定向 25 项检查与 build 通过，之后最终全量 406 项及其他检查再次通过，最终结果以表中的 full log 为准。

部署协调者从真实浏览器明确点击生成和重试，任务 `34c235eaf19e48dab07e0a66a1eab153` 使用实际百炼 `qwen-flash`。三次 attempt 均保留在账本，证据为 `/Users/lengwang/code/esp32/work/summary-real-provider-proof-20261031.json`：

| attempt | 结果 | input / output / total tokens | provider request ID |
| --- | --- | --- | --- |
| 1 | `failed / INVALID_MODEL_OUTPUT` | 1550 / 2278 / 3828 | `chatcmpl-0d988346-93af-9c7e-9246-f6650cbea92e` |
| 2 | `failed / INVALID_MODEL_OUTPUT` | 1550 / 2143 / 3693 | `chatcmpl-43e395e9-e437-9321-b93b-fa3ec7a06941` |
| 3 | `completed` | 2229 / 2470 / 4699 | `chatcmpl-21fc9f22-fc58-999f-a60a-349022d55f05` |

第二轮私有诊断 payload 明确出现：附件 Item 将 `attribution` 错填为来源角色 `expert`，两条假设把“AI推测，待客服核验”写入应为 null 的 `verification_note`。其 `verified_facts` 与基础数组相等，引用的 ID、seq、quote 和附件引用与快照匹配。完整诊断保留于 `/Users/lengwang/code/esp32/work/summary-real-complete-debug-20261031.json` 和 `summary-real-source-debug-20261031.json`（私有、无 lease token）；第一轮失败未捕获完整输出，不断言它具有完全相同的逐项错误。

仅增强 SYSTEM 的闭合枚举、逐字段规则、生成核验说明必须为 null、最新相关诉求和技术样本限定后，第三轮通过原有严格 Java 验证。没有放宽服务端引用/归属校验，没有自动映射、静默降级或二次模型修复。第三轮得到 v2、`origin=model_assisted`、九字段及四条内部建议；原 `verified_facts` 原样保留。生成后该会话仍只有 seq 1 至 5 的五条消息，内部整理没有新增客户对话消息。

证据引用匹配不等于每个语义判断正确。成功结果仍出现“暂无直接安全风险”等超出软件样本证据的表述，也再次建议确认已经声明的技术样本属性；不能把结构验证通过写成诊断或安全结论正确。后续人工 v3 正是对此作明确修订。

## 真实草稿、过期、人工修订与 cutoff

当前客服 B 在真实 UI 明确选择第二条建议加入已有回复草稿，原草稿内容保留，没有发送。随后新增客户技术消息 seq 6，自动 GET 将任务标为 `stale / SOURCE_CHANGED`；UI 当前建议数变为 0，已加入的草稿保持，原 v2 历史不变。模型请求仍为三次，没有因新消息自动再次生成。证据：`/Users/lengwang/code/esp32/work/summary-real-stale-proof-20261031.json`。

服务端保留 stale job 的四条历史建议及 usage/result_version 供内部审计，UI 不再将其作为当前可选建议；`result_version` 已存在时提示“历史整理已保留”，与从未成功生成的过期任务区分。

B 再通过真实摘要编辑 UI，将安全风险改为 `unknown`：“待确认：本次只有软件技术样本，没有真实设备安全核验。”保存成功生成 v3、`origin=staff_edit`，并原样保留模型推测。这验证模型草稿能被客服明确修订，不会要求把 AI 推测升级为已证实事实。

最终历史 HTTP 授权证据 `/Users/lengwang/code/esp32/work/summary-real-edited-proof-20261031.json`：客户 p2 读取内部摘要 HTTP 404；已转出的 A（p3）读取 HTTP 200，但仅得 v1；当前 B（p4）得 `[3,2,1]`。此前辅助任务接口对客户和旧 A 都为 404，不能将原摘要的历史 cutoff 访问误解为读取新辅助任务的权限。

## 最终 UI 健康与备份

390×844 移动视口无横向溢出，AI 区右边界为 334、小于视口 390；截图 `/Users/lengwang/code/esp32/work/summary-assistance-mobile-final-20261031.png` 已视觉核对。桌面 UI 健康证据 `/Users/lengwang/code/esp32/work/summary-ui-health-20261031.json`：`http://127.0.0.1:18898/#/support`，选中 v3 客服修订，0 exceptions、0 error logs、无 Vite overlay。

该健康检查在显式 reload 后由测试工具重新填入原技术草稿，**不能据此宣称草稿跨 reload 自动持久化**。生成、新消息和内部编辑期间的草稿保持已有前述独立证据。18898 是本次 UI 验收路径；结束检查时原 Dev 18894/18893 以及 AI/media/realtime/summary workers 均运行，此处进程状态由部署协调者核验，不替代真实媒体回归。

部署后 SQL 备份为 `dev/.runtime/backups/after-summary-assistance-20261030T164626Z.sql`，506315 bytes，SHA256 `42610ce81185af50523403971a7ae972f7a6f4de47b58eaa35967890c7cfa54c`。最终数据库计数：用户 4、旧设备历史 25、消息 29、迁移 117、辅助任务 1、attempt 3、摘要 revision 3。消息从部署前 27 增至 29 对应明确新增的 seq 5/6 两条技术输入；生成、加入草稿和内部修订没有增加公开消息。证明：`/Users/lengwang/code/esp32/work/summary-assistance-after-deploy-20261031.json`。本轮前后 SQL 备份、原卷、测试 schema 及失败记录均保留。此前恢复阶段无法定位的 APFS 克隆/快照仍按恢复记录列为待核查项，不能把本轮 SQL 备份校验视为该问题已解决。

## 验收限制

本模块处理锁定读取的有界文字快照和附件引用，不声称读取附件实际声音或图像，也不接入真实业务只读来源、工单提交或设备控制。来源验证证明引用存在及归属匹配，不证明设备诊断正确。

技术输入、真实浏览器和实际 provider 路径即便完成，也不替代真实客服、多客户/租户、真实业务源、真实设备、长时间运行和现场验收。已有 Dev、数据库、备份、卷和其他服务的边界继续保留；不能以清库或生成虚构业务数据替代验收。
