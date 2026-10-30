# 验证方法

## 默认 Java 测试

`mvn -B -DskipTests=false test`。新测试包含严格请求解析、伪造 owner/tenant/project/sender 拒绝、越权智能体、输入类型/长度/位置验证、分页参数、默认关闭开关、HTTP 错误状态和登录身份来源。

## 隔离 MySQL 集成测试

`ConversationPersistenceTest` 仅在显式设置 `CONVERSATION_TEST_JDBC_URL` 后启用；连接串必须指向 loopback 且数据库名以 `xiaozhi_conversation_test_` 开头，测试不会读应用的数据库配置。

用独立 MySQL 8 容器和空测试数据库：

```sh
CONVERSATION_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:测试端口/xiaozhi_conversation_test_run?allowPublicKeyRetrieval=true&useSSL=false' \
CONVERSATION_TEST_DB_PASSWORD='隔离测试容器密码' \
mvn -B -DskipTests=false -Dtest='Conversation*Test' test
```

测试验证新增 Liquibase changeset 重复 update 只执行一次、旧表哨兵保留、真实落库与重建 Service 后查询、跨账号所有入口拒绝、24 路重复请求只产生一条消息与事件、30 路不同消息序号连续、大小写敏感幂等键、消息/事件分页补拉、事件写入失败时消息和计数整体回滚。

## 兼容基线

- Python：使用现有环境运行 `main/xiaozhi-server/tests`，不调用付费模型或设备控制；区分缺失依赖、既有失败和本批回归。
- Java：运行全套现有单测，记录原测试对 live MySQL/Redis 的依赖；不能把跳过当作通过。
- Web：使用锁定依赖运行 `npm test`、`npm run test:snapshot` 与 `npm run build`。
- 旧 ESP32 实机：部署新版本前再验证 OTA、鉴权 WebSocket、Opus、MCP、语音及历史。当前代码测试不代表完成实体麦克风/喇叭或新平台现场验收。

本轮实际执行结果见 `VALIDATION.md`。

## B2 定向验证方法（结果另行记录）

协议定义见 [OpenAPI](./OPENAPI.yaml)、[消息请求与响应子结构](./MESSAGE_SCHEMA.json)、
[B2 实施协作契约](./B2_IMPLEMENTATION_CONTRACT.md)；独立 worker 的私有配置和运行命令见
[ai_worker/README.md](../../main/xiaozhi-server/ai_worker/README.md)。这些入口和方法的存在不代表已部署或通过真实服务验收。

- 默认关闭：验证 `features.ai-text-enabled=false` 时原账本/人工能力仍按各自开关工作；公开 AI run/SSE 和私有 worker 路由须同时启用 conversation/AI-text。Dev 使用显式 opt-in，不把默认值改为开启。
- 权威状态：用户消息落库与排队同事务；重复 `client_message_id` 不重复排队；每会话最多一个活动 run。新用户输入、人工接管及 epoch 变化取消旧 run；部分输出保留且旧回调返回 409。
- 资源边界：owner/当前 assignee 读 run；历史已释放人员不能读可变 run 元数据。SSE 每批重验账号、权限及释放 cutoff；初始错误是实际 HTTP 状态，发出 headers 后撤权应关闭连接。
- 租约与恢复：45 秒默认 lease、10–15 秒心跳；进程崩溃后至多三次领取。旧 lease 永久失效，下一 attempt 在输出前 reset；手动 retry 仅限最新输入、失败/取消态且无其他活动任务。验证 queued 保留旧 attempt/部分输出，claim 后递增并清空。
- 流式持久化：同一 output_message_id 更新 parts/status，不按 token 建消息；delta ACK 丢失时同 sequence/body 重试幂等，乱序和同 sequence 不同正文 409。complete 必须匹配 last_sequence；真实 usage 可 null，禁止估算。
- SSE 与轮询：用超过 `2^53` 的十进制字符串游标验证 `after`、`Last-Event-ID`（header 优先）、事件去重与有限连接重连；初始历史回放不重复拼接已有 snapshot。覆盖 snapshot 晚于/早于流、轮询 events 补漏、attempt reset、迟到旧 chunk、首包早于消息占位、消息序号排序。
- 界面清理：停止/重试连续点击只有一个请求；人工模式不提供 AI 操作；切换会话和卸载中止 reader/请求/重连等待，迟到 send/stream/snapshot 不写入新会话或清除新草稿。
- 只读旧历史：两个 `/api/v1/ai/legacy/agents/{agentId}/sessions...` GET 仅 exact agent owner 可读；分页保留 source/session ID、read_only=true、has_audio 标志，跨账号/agent 拒绝。读取前后源表行数/内容不变，且不自动产生 ai_conversation、ai_message、ai_run 或模型请求。

Python worker 离线测试使用独立依赖，直接运行 unittest，避免设备 tests/conftest.py 的全局 SDK shim：

```sh
cd main/xiaozhi-server
/path/to/isolated-worker-venv/bin/python -m unittest discover -s tests -p 'test_ai_worker*.py' -v
```

Vue 3 定向单 worker 验证（在 `main/manager-web-next` 下）：

```sh
npx vitest run tests/aiStream.test.mjs tests/conversationClient.test.mjs tests/conversationWorkspace.test.mjs --maxWorkers=1 --no-isolate
```

真实百炼小请求、模拟 provider/控制面、隔离数据库并发测试、Dev 部署和 ESP32 实机验收分别记录；
不要用 mock 成功代替真实云请求，也不要用新代码测试替代设备麦克风、喇叭、唤醒和打断验收。
不连接现有业务库执行初始化、重建或清理；隔离数据库验证遵循 B2 数据保护契约。


## B3 验证计划（非验收结果）

- 关闭开关时检查原会话功能；Dev 显式 opt-in 使用 `python dev/manage.py media-enable --storage s3 --storage-env <私有配置文件> --vision-model <已验证视觉模型>`，并用 `media-status` 查看、`media-disable` 关闭。配置不写入公开文档；关闭保留对象与行。实际云存储、模型与端到端验收须另行记录。
- 覆盖 init 幂等和参数冲突、Bearer PUT 的大小/哈希/MIME/扩展名、原件不可变、complete 幂等及存储失败；图片/PDF/文本 20 MiB、视频 100 MiB、音频 50 MiB，检查 24MP/8192 边、PDF 200 页、视频 180 秒/3840×2160、音频 600 秒以及真实解码。
- 验证发送的顺序、每消息最多 10 附件/1 视频/1 音频、同会话同发送者 ready、幂等重发、并发绑定回滚；Java long 最大值与超出值，以及 32 位小写十六进制 conversation ID。
- 验证未关联仅上传者、已关联会话授权与离任 cutoff、账号撤权后旧票据拒绝；精确 content 路由可不带 Bearer 但必须票据和动态授权；覆盖过期、变造、跨资源票据、Range 206/416、安全头与私有缓存。
- 验证任务租约、失效回调、3 次 claim 上限、可重试错误与无活动任务约束，inspect 和转写状态独立；补偿只回收本模块记录且未引用的对象，不改业务数据。检查未关联草稿无公开事件。
- 验证 AI run 在 ASR queued/running 时不领取，成功后使用真实派生物，失败或缺失明确失败；图片和视频选择显式视觉模型，未配置或不可用明确失败。对象存储和提供方故障不得降级伪造成功或用量。
