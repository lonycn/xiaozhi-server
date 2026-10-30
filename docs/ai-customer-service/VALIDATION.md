# 第一批升级验证记录

日期：2026-10-30。本批只交付统一对话账本基础，未部署到本机正在运行的服务。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| Java 全套测试 | 221 通过，0 失败，0 跳过 | 使用独立 MySQL/Redis，包含 15 项新增测试 |
| Java 打包 | 通过 | Spring Boot 可执行 JAR |
| MySQL 增量迁移 | 通过 | 新增 changeset 重复 update 只执行一次，旧历史哨兵保留 |
| MySQL 完整迁移 | 通过 | 空隔离库从原 master 执行 108 项 changeset，包含本批增量 |
| 并发幂等 | 通过 | 24 路相同请求只有一条用户消息及一条 accepted 事件 |
| 并发消息顺序 | 通过 | 30 路不同请求，消息序号连续、游标补拉完整 |
| 混合消息/分页 | 通过 | 文字与位置保持顺序，消息重试不重复，对话列表游标不重复 |
| 权限与回滚 | 通过 | 跨账号会话/消息/事件/发送拒绝；事件写入失败整体回滚 |
| OpenAPI 3.1 校验 | 通过 | openapi-spec-validator，含相邻 JSON Schema 引用 |
| Web 契约测试 | 10 通过 | `npm run test:contract` |
| Web 单元测试 | 70 通过 | `npm test` 的 Vitest 阶段 |
| Web 快照测试 | 13 通过 | `npm run test:snapshot` |
| Web 构建 | 通过 | 沿用现有 Vue 2 锁定依赖 |
| Python 测试 | 116 通过，5 跳过 | 上游默认第三方服务 mock 模式，不调用真实云模型 |
| Git 补丁检查 | 通过 | `git diff --check` |

## 测试环境与可复现条件

MySQL 8 和 Redis 使用本轮单独创建的临时容器，映射到 loopback 的随机端口。Java Spring 测试通过系统属性覆盖所有数据库及 Redis 地址，未连接现有业务数据库/缓存。新增集成测试另用 `xiaozhi_conversation_test_*` 数据库，连接串在代码中强制检查。

首次独立迁移测试发现 `classpath:` 路径不适用于 Liquibase 的标准 ClassLoaderResourceAccessor；已将本批新增 changelog 引用改为相对路径，随后增量、重复 update 及原 master 全量迁移均通过。

Python 首次运行缺少忽略的测试配置，且未安装 freezegun 时上游依赖 shim 无法模拟其装饰器。仅在新 checkout 写入空的忽略配置 `data/.config.yaml`，并把 freezegun 安装到独立测试依赖目录，通过 `PYTHONPATH` 使用；未修改原服务虚拟环境。修正测试环境后重跑结果如上。

Python 5 项跳过来自上游：两个测试模块在源码中无条件 skip，三个 LLM 测试因默认占位 API Key skip。它们不计作通过，不作为真实模型或设备验收。

## 尚未验收

- 当前实际服务未切换，没有对设备 Flash、NVS、OTA 地址或运行数据库做修改。
- 未完成 ESP32 真人唤醒/麦克风/喇叭/打断的实机回归。
- 未实现本批范围以外的 AI 回复、SSE、旧历史映射、媒体上传、Omni、人工客服、标注和 LiveKit。
- 当前认证及错误路径由过滤器测试和 MockMvc 验证；尚未进行新 API 的浏览器/Android 端验收。
- 本地已有 Workspace ASR、TTS 配置及 OTA 兼容补丁还在原运行快照中；后续部署前须整合并回归。

关闭 `features.conversation-enabled` 可禁用新入口，新增数据表保留，原 ESP32 路由不更改。
