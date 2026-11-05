# ESP32 局域网候选 2.5.3：开发交付与发布边界

日期：2026-11-02。Server 与固件均在 `Dev` 分支开发。用户选择先完成局域网开发候选，正式 HTTPS/WSS 域名稍后配置。ESP32 全程采用 AI 交互。

本批交付可审查的 Server/网关源码、独立启动与发布工具、实际编译的 ESP32-S3 候选，以及自动验收证据。没有连接串口、刷写主板或将现机切换到新服务。真人唤醒、实际喇叭连续播放、物理 Wi-Fi 与 OTA 断电回退仍待实机验收。

## 实际分工

| 责任 | 执行 |
| --- | --- |
| 网关配置、ASR/TTS 接线、MCP 回执、真实语音链及 30 分钟模拟 | `esp32_gateway`，GPT-6.1 Sol |
| 固件打断、轮次隔离、异步连接、凭据恢复、受控 OTA 与 NVS 身份迁移 | `esp32_sessions`，GPT-6.1 Sol |
| 独立正式/LAN 变体、可复现打包、实际候选构建及 HTTP/CLI 验收 | `esp32_protocol`，GPT-6.1 Terra |
| 受控固件注册与指派服务、设备入口代理、管理 CLI、共享协议边界及最终整合 | 主任务 |

工作区另有 AI 全交互任务同步修改 Server。保留其所有已有更改，没有把整份共享工作区盲目提交或推送。

## 已实现

### 网关与 AI 语音

- 修复 API 配置加载时丢失本地 `server.esp32`；远端和设备输入不能覆盖可信网关地址与密钥。
- 增加独立网关启动、配置预检、明确灰度设备清单和只停止自有进程的入口。原生 18880/18883 端口受保护。
- 正式部署默认 HTTPS；局域网候选需显式 `lan_candidate` 配置。可显式使用私有本地 VAD/ASR/TTS 配置，归属、AI 任务与工具授权仍由 Java 管理。
- 迁入百炼 workspace ASR；canonical 模式只初始化 VAD/ASR/TTS/MCP，停用旧 LLM、记忆、意图及天气插件初始化，保持唯一 AI 执行者。
- MCP 按工具校验实际结果；明确成功、明确失败和执行状态未知分别处理。超时不再伪报失败或成功，不盲目重发控制命令。
- 固定 TTS 合成任务的 sentence 身份并贯穿编码、队列和转发，避免迟到音频借用新轮次标签。
- v1 TTS JSON 增加可信 `turn_id` 与整数 `control_version`，二进制仍为当前原始 Opus 协议。
- 增加条件 `abort-turn`：Java 按当前设备、会话和轮次行锁判断后才取消，旧打断返回 `STALE_ABORT`，不会误伤新任务。

### 固件声音与恢复

- 打断立即清理本地解码与播放队列，作废旧输入、旧音频和已排队回调，保留新一轮数据。
- 标记后的 TTS 生命周期、回调代次与音频接收窗口统一校验；旧轮次不重新进入播放。
- 网络连接与 hello、打断和凭据恢复在后台执行；主循环不等待网络连接或慢解码结束。
- 失效凭据重新经过 OTA 取新 token，采用有界退避覆盖撤权后的 31 秒窗口；恢复时不回放未确认录音。
- 固定受控 v1；保留路径的 v2/v3 读取补最小头长、载荷长度与上限检查。
- 新增丢帧、欠载、解码错误与耗时诊断。已有三短语本地唤醒及播放中唤醒能力继续保留。
- 新受控固件只读恢复 NVS 中合法的 `board.dev_mac` 和 `board.uuid`；新空板使用独立出厂身份，非法已有身份明确失败，不清空 NVS 或写 eFuse。

### 受控 OTA

- 新增独立的不可变固件注册表、设备指派及升级事件账本；不同版本保留，不覆盖旧发布元数据。
- 管理员先上传校验产物、创建 draft，再显式发布和指派。设备必须匹配精确变体、芯片、Flash、当前绑定与有效指派。
- 10 分钟用途受限下载票据，暂停、重指派和撤权后旧票据失效；正式与 LAN 变体隔离。
- 已纳入受控管理的设备没有匹配发布时，不回退到旧的通用板型固件。
- 同版本仍可返回完整 manifest，用于新固件启动后的资源与健康确认。
- 回执使用设备凭据认证，成功返回 HTTP 204，严格按 `offered → downloading → verified → booted → healthy`；幂等、终态与事件数量均有保护。
- 明确管理员回退指派可选择旧版本；不是模型可调用的升级工具。
- 固件核对版本、精确板型、芯片、大小和应用 SHA256。单资源分区本期只接受与已安装资源哈希相同的 app OTA，资源不匹配或要求非原子替换时失败并保留原状态。
- 下载和 verified 回执确认后才设置启动分区；新启动经资源/音频初始化、协议 hello 和 30 秒稳定健康后确认。pending 启动有界 180 秒未健康可回退，避免重复安装同一失败版本。
- 受控固件使用编译时明确注入的 OTA 根，忽略旧 `wifi.ota_url` 但不覆盖它，使旧固件回退后仍可使用原配置。
- 正式传输要求 HTTPS/WSS；LAN HTTP/WS 仅在显式开发配置和受限私有地址下允许。下载/回执保持与可信 OTA 同源。

## 实际候选产物

- 版本：**2.5.3**；变体：`bread-compact-wifi-128x32-ai-lan`。
- ESP32-S3、16 MiB Flash、Quad PSRAM、128×32 OLED；大冷总管、你好总管、总管总管。
- 固定 OTA：`http://192.168.3.152:19183/xiaozhi/ota/`；标记 `development_only=true`。
- ESP-IDF：v6.0.1；干净私有源码提交：`e14ec7e635fc53226dad1935ce5f0cd20042bb64`。
- [Manifest](/Users/lengwang/code/esp32/work/esp32-lan-candidate-20261102_150156_0dd2f4/release/manifest.json)
- [app.bin](/Users/lengwang/code/esp32/work/esp32-lan-candidate-20261102_150156_0dd2f4/release/app.bin)：2,553,984 字节，SHA256 `a7c860a0942926ff337d6c0d1e474a8f298a10eca0df789b491d6548339985c0`。
- [assets.bin](/Users/lengwang/code/esp32/work/esp32-lan-candidate-20261102_150156_0dd2f4/release/assets.bin)：2,951,901 字节，SHA256 `4006035a353c7a2d96723724c367d8e09c672fc0ec0e04eac72f88aa6a2e7426`。
- [构建日志](/Users/lengwang/code/esp32/work/esp32-lan-candidate-20261102_150156_0dd2f4/build-attempt2.log)
- [升级说明](/Users/lengwang/code/esp32/work/esp32-lan-candidate-20261102_150156_0dd2f4/UPGRADE_GUIDE.md)

候选还包含 16 MiB `first-flash.bin`，其 NVS 区域为空白，**只用于新空板；禁止直接刷到需要保留配置的现有板**。现有板当前实际固件变体、分区、资源哈希和引导状态尚未核验。旧开发变体的自动升级被禁用，跨变体受控升级也会被拒绝，必须先设计并验证保留 NVS/回退能力的首板引导步骤。

这些文件是局域网开发候选，没有发布到 GitHub Release、云端 OTA 或现有主板。来源提交与 SHA256 提供追溯和完整性信息；它们本身不是固件来源签名或硬件身份认证。

## 操作入口

- [独立网关启动器](../../dev/esp32_gateway.py)：`preflight / start / stop`，配置必须包含独立端口、密钥和 `server.esp32.allowed_devices`。
- [受限 LAN OTA 入口](../../dev/esp32_ota_proxy.py)：只转发设备 OTA、下载和回执，管理和配置路由不外露；关闭访问日志以保护票据。
- [固件管理 CLI](../../dev/esp32_firmware.py)：`settings / import-package / list / publish / assign / history / pause / pause-assignment`。导入前实际校验候选包及本机部署 OTA origin；创建 draft，发布和指派为独立明确操作。
- [固件打包工具](/Users/lengwang/code/esp32/firmware/xiaozhi/scripts/package_bread_ai.py) 在另一个 Git 仓库中；本项目同级固件位置为 `/Users/lengwang/code/esp32/firmware/xiaozhi`。

启用 Java OTA 服务需 `features.esp32-ota-enabled=true`、私有 artifact 目录、独立下载密钥和可信 public base URL。开发 HTTP 还需显式 `esp32.ota.allow-lan-http=true` 与 development 模式。网关、设备 HMAC、AI Worker、固件下载四类凭据分开；不为启用网关而轮换既有设备密钥。

公开设备路径：`POST /ota/`、`GET /ota/esp32/releases/{id}/{kind}`、`POST /ota/esp32/receipts`。管理员路径：`/api/v1/esp32/firmware/**`。manifest schema=1，含 release、版本、URL、整数大小、SHA256、板型、芯片、Flash、策略、development 标记和资源依赖。

运行服务需要私有配置、可用模型和已批准设备指派。单纯配置预检不代表模型/设备就绪。验收用 Manager、Redis 和 19183 代理均已关闭；不能据候选 URL 假定 OTA 正在运行。

## 验证证据

### 固件

- 当前冻结源码的 host suite **127 项通过**，日志：[最终主机测试](/Users/lengwang/code/esp32/work/esp32-lan-host-final-20261102.log)。
- ESP-IDF 6.0.1 私有 S3 LAN 全链接构建、正式候选重新构建与离线包校验均通过。
- 另对未受控 MQTT/WS、旧开发身份和 USB 路径进行 7 个对象条件编译，不能据此宣称真实 MQTT 网络验收通过。
- [固件开发证据](/Users/lengwang/code/esp32/work/firmware-release-safety-20261102-proof.json)。原工作区生成的 sdkconfig/build 未用于冒充新候选，也未被手工替换。

### Server 与 HTTP

- Java 最终全量 **711 项、105 个套件、0 失败/错误/跳过**。原测试已通过，打包阶段受磁盘不足影响；释放可重建缓存后仅在同一冻结快照重试 package，保留原日志和报告。
- 最终 JAR：`/Users/lengwang/code/esp32/work/esp32-full-matrix-20261102_151133_615655/repo/main/manager-api/target/xiaozhi-esp32-api.jar`，SHA256 `27e570b90b945ca7543c148593a59aacb4ef226d3493ef013716e780c64cb884`。
- [打包恢复与 CRC 证明](/Users/lengwang/code/esp32/work/esp32-full-matrix-20261102_151133_615655/repo/main/manager-api/packaging-retry-proof.json)。定向重跑和中间矩阵不累加测试数。
- 新 OTA 的独立真实 MySQL 用例覆盖版本不可变、板型/Flash/同版健康 manifest、开发/正式隔离、撤权与回执顺序。
- 真实候选 HTTP/CLI 验收覆盖两次导入幂等、19183 同源设备入口、真实 app/assets 下载哈希、票据撤销、严格回执及旧通用 OTA 禁止回退。第二版本仅为明确标记的合成描述头测试；没有伪称两版都是实际编译固件。

### 模型与稳定性

- 实际 CosyVoice 输入音频经 Opus → WebSocketServer/ConnectionHandler → workspace ASR → Java/AI Worker → MCP → 实际 TTS Opus 返回，状态、音量 30、丢 ACK 未知及同连接双轮均完成技术验证。
- 最终 JAR 的双轮实际 usage=4，72 个 Opus 包，轮次/控制版本隔离；真实 HTTP 旧 abort 返回 409，新 run 保持 queued、新 turn 保持 accepted。[最终完整语音与条件打断证据](/Users/lengwang/code/esp32/work/esp32-full-voice-final/http-20261102_072148_6781b1/full-voice-proof.json)。
- [30 分钟模拟](/Users/lengwang/code/esp32/work/esp32-gateway-soak-30min-20261102.json)：1800.2 秒、1784 轮、118 次 MCP、14 次未知 ACK、6 次断网重连；最大 TTS 队列 3、异步任务 5，tracemalloc 增长约 1.49 MiB。
- 该长测是 Runtime+真实 HTTP 模拟器，provider=0，不是 30 分钟真实 ASR/TTS 或物理喇叭连续播放。完整语音输入是明确标记的合成语音，MCP 端为模拟板，不替代真人或物理执行确认。

最终 JAR 与真实候选的 [HTTP/CLI 10 项验收](/Users/lengwang/code/esp32/work/esp32-firmware-http-20261102/http-20261102_072748_dab362/proof.json) 全部通过，含临时 19183 同源代理、真实下载与重复导入。

最终 [独立启动器实际全链验收](/Users/lengwang/code/esp32/work/esp32-full-voice-final/http-20261102_072445_5ceb69/full-voice-proof.json) 已通过：实际启动独立子进程、WS hello/MCP、Java 会话、真实 ASR/模型/TTS、条件打断，再停止自有网关；确认 Manager 仍活跃、原生 PID 52583 保留。随后隔离 Manager/Redis 也已停止，测试库保留。

## 数据与运行边界

所有自动化仅新建唯一命名的隔离库并保留；没有通过删库、清表、重置迁移历史处理问题。本任务未向原 Dev 数据库执行写入。只读看到同期并行任务已追加 OTA changeset，其 checksum `8:b0ce39e2da572cfa625bdbd2ebc6d452` 与本批测试一致；核对时原 Dev 为 4 个用户、25 条旧历史、44 条消息、138 个 changeset。

磁盘不足时只清理本任务已结束、无进程占用的可重建 Java 解压缓存及旧编译对象。所有数据库/卷、SQL 备份、源码、验收 JSON、音频、日志与关键 JAR/固件均保留。清理清单和产物哈希核对保存在 `work/esp32-*-cleanup-*.json` 及 `work/firmware-release-safety-20261102-cleanup.json`。

原设备仍可继续使用原生网关；本任务没有接管其端口、改写实体主板配置、刷机或执行设备危险维护命令。

## 实机及正式发布仍需完成

1. 首板备份/身份、真实分区与当前 assets 核对，验证保留 NVS 的引导升级与旧版本恢复；不能直接应用新空板整片镜像。
2. 三个唤醒词的多人、距离、噪声、误唤醒；真实麦克风/扬声器、30 分钟播放、反复立即打断、回声与卡顿测量。
3. 真实 Wi-Fi 断开/重连、网关故障、重启、token 恢复，以及 OTA 断电、损坏下载、启动未健康与回退后的可用性。
4. 按用户后续提供的正式 HTTPS/WSS 地址配置证书与网络，再构建正式 `ai-release` 变体；LAN 候选不能冒充正式包。跨变体迁移须单独审核和验收。
5. 公网产品发布前另行完成设备配网身份、防 MAC 冒用、制造凭据/密钥托管、容量与告警。现有 HMAC 不能替代硬件身份认证。

真实 Laravel 订单/工单等业务 API 不阻挡本期基础 AI 固件开发；这些业务仍单独接入。真人客服、专家后台和媒体转接不进入 ESP32 固件范围。
