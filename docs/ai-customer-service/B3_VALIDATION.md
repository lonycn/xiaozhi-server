# B3 私有附件与媒体验证记录

日期：2026-10-30。此记录区分当前 Dev 验证、隔离测试和待验收事项；所有媒体均为仓库技术样本，未使用客户材料。

## 已验证

- 主仓当前 Java 全量测试 334 项通过、0 失败、0 跳过，见 `work/b4-java-full-native-r4.log`；此次覆盖 B3 和 B4 等现有模块。Dev 配置测试 29 项、媒体 worker 92 项；AI worker 最新 30 项通过，见 `work/b3-ai-video-wire-tests-r2.log`。Vue 全量此前 282 项通过，随后 B4 busy 状态修复的定向 70 项通过，见 `work/b4-browser-busy-regression-web.log`；两次范围和时间不同，不能相加当成一次全量运行。
- 隔离新建 native 测试库的真实 HTTP 测试：协议 33 项、解码 64 项、标注 30 项，共 127 项；对应 `work/b3-http-native-protocol.log`、`work/b3-http-native-decoder.log`、`work/b4-http-native-annotation-r2.log`。这些测试未调用模型提供方，不等同于生产或现场验收。
- Dev 显式开启 S3 私有媒体、ASR、视觉模型和 B4 标注。真实百炼链路使用技术样本图片、TXT 与录音经 S3、检查、ASR 和 `qwen3.8-flash` 完成 AI run，SSE 29 个事件，模型实际用量输入 337、输出 60、合计 397，消息历史保存。录音转写任务完成，使用 `qwen3-asr-flash`；证据为 `work/b3-dev-media-e2e-proof.json`。这是一条受控真实服务路径，不是客户数据或全媒体类型验收。
- Dev 已使用管理员账号通过真实 UI 登录。B3 前备份 266091 字节并已校验哈希；Dev 旧 legacy 25 条和用户 1 条保留，changelog 110 增至 112，旧消息 7 条及新增技术测试消息 2 条共 9 条；原控制台未改。备份和记录不意味着可省略后续部署前保护。

## 联调修复与后续验收

视频首次附加到 AI 的联调发现 `time_ms` 经 Java long 字符串传输时 Python worker 处理错误；修复后 AI worker 定向 30 项通过。同一 run 对原消息以 attempt 2 真实重试完成：浏览器上传并发送的 2 秒仓库技术视频经私有 S3 检查和音频转写均 completed，提取 1 帧后由百炼 `qwen3.8-flash` 完成回答，实际用量输入 825、输出 35、合计 860。B3 最多提取 6 帧，不保证每个视频生成 6 帧。证据见 `work/b3-dev-video-e2e-proof.json` 和 `work/b3-dev-video-proof-r2.log`；该技术样本通过不代表客户视频或现场验收。

真实 Dev 还发现 AI run 日期映射把 JDBC 返回的 LocalDateTime 当成 UTC，导致北京时间环境中的公开时间偏移。改用连接时区驱动转换的 Timestamp 行映射；新的独立 `+08:00` 会话与同区 JDBC 回归 16 项通过，含 AI 实库、媒体 lease 与类型映射检查，见 `work/b3-ai-cst-regression-r2.log`。AI lease 的有效期仍由 SQL 比较，不把此日期展示问题扩大描述为已证实的 AI lease 延长。该后续修复尚须随下一次 Dev JAR 部署生效。

隔离测试期间 Docker VM/端口转发出现间歇性长延迟：TCP 建连与服务响应、Docker exec 均出现不同程度等待，恢复后延迟下降；这不足以确定单一根因或把所有故障归因于业务代码。native 测试库一次运行使用了错误的默认时区导致 r3 一项失败，保留失败库；采用 UTC 配置新建测试库后的 r4 为上述 334/334。不得抹去失败或把环境配置问题笼统当作业务回归。

真实客户附件、iOS/Android 终端、跨网络恢复、真实设备和现场验收仍待执行。功能与模型能力依赖实际配置和授权，默认发布开关仍关闭；Dev 显式启用不改变其他环境。
