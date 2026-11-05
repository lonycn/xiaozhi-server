# ESP32 AI 网关

这是可选的统一 AI 接入适配层。ESP32 仅使用 AI，不接转人工、专家邀请或真人媒体。Java 管理归属、连接代次、会话、任务与工具权限；Python 持有设备 WebSocket，处理 ASR、MCP 和 TTS。

默认关闭。现有原生设备服务不会因增加本目录自动切换。

## 配置

Java Manager API 需要在受信任的配置中设置：

```properties
features.esp32-enabled=true
features.ai-text-enabled=true
features.agent-tools-enabled=true
esp32.gateway.key=<独立生成的32至512字符密钥>
```

AI Worker 使用自己的认证密钥；不要与 ESP32 Gateway 密钥复用。Java 中的设备 OTA HMAC 校验使用既有 `server.secret`，不要为启用网关而轮换全局设备密钥。

Python 最终生效的服务端配置示例：

```yaml
server:
  esp32:
    enabled: true
    base_url: http://127.0.0.1:18893/xiaozhi
    gateway_key: <与Java一致的独立密钥>
    gateway_id: esp32-dev-canary-01
    execution_mode: ai_worker
```

这是配置格式示例；Docker 内的地址应使用可信网络中的 Manager API 地址。不要直接改动当前原生设备服务来验证示例。模型、ASR、TTS 和 Opus 配置仍由各自服务提供，开启 feature 不代表这些依赖已就绪。

统一模式只提交一个真实 `ai_run`，不执行旧 Python LLM/意图工具链。`legacy_record_only` 仅保留可信账本协议；Python 适配器不会通过不可取消的旧流式执行器生成答案或播放声音。迁移时应直接使用 `ai_worker`。

## 安全与恢复

- 当前支持 WebSocket 原始 Opus 协议 v1，16 kHz 单声道、20/40/60 ms；v2/v3 在握手时明确拒绝。
- 网关内部调用必须使用 `X-ESP32-Gateway-Key`，不接受用户 Bearer 或 AI Worker 密钥替代。网关地址、密钥和实例 ID 来自服务端配置，设备消息不能指定它们。
- Java 从现有设备绑定读取 owner 和 agent；连接 ID、代次、授权版本与 30 秒 lease 共同限制输入、工具回执和输出。
- 心跳每 8 秒，命令轮询每 1 秒。API 不可达时停止处理并关闭该连接，重新接入必须重新认证，不回放不确定的旧录音或命令。
- 解绑、停用、归属变化或显式撤权后，旧连接及旧 token 均失效。由于既有 OTA token 格式允许未来 30 秒时间偏差，撤权 cutoff 向前推进 31 秒。恢复授权后等待至 cutoff，再获取新的 OTA token；重试旧 token 不会恢复权限。
- HMAC 校验只证明既有凭据有效，不证明实体硬件身份。生产配网、防 MAC 冒用、硬件证明及密钥托管仍需单独设计与验收。
- 首批允许 `self.get_device_status` 与 `self.audio_speaker.set_volume`。音量范围 0–100，AI 仅在整句用户输入明确指定数值时获得该工具。无确认回执时结果保持未知，不盲目重发控制命令。
- TTS 实际发送前重新检查轮次及权限，新输入、打断或撤权清理旧播放队列。这增加了内部 HTTP/SQL 开销；真实设备的声音连续性及并发性能尚待验收。

## 接口

内部 POST：`/internal/esp32/{register,heartbeat,poll,result,disconnect}`，以及 `/internal/esp32/sessions/{connect,input,output,event,abort,abort-turn,reply}`。

用户只读入口：`GET /api/v1/esp32/capabilities`、`GET /api/v1/esp32/devices/{id}/{status,capabilities,sessions}`、`GET /api/v1/esp32/sessions/{id}/turns`。设备命令入口为 `POST /api/v1/esp32/devices/{id}/commands` 和 `GET /api/v1/esp32/commands/{id}`；Vue 面板本批只读。

全局 capabilities 仅声明配置，返回 `readiness_scope=configuration_only`。在线状态来自当前 lease，不能将接口存在当作设备或模型已通过验收。

## 测试

Python 3.11，先安装 `esp32_gateway/requirements.txt`，系统另需 libopus。完整设备语音服务继续使用项目原有依赖；此文件仅是协议与网关 CI 的最小依赖集合。

```sh
python -m unittest discover -s tests -t . -p 'test_esp32*.py'
```

保留 `-t .`，避免 `tests/core` 遮蔽实际 `core` 包。Java 使用独立的 ESP32_GATEWAY、ESP32_SESSION、ESP32_AI 测试库；HTTP/故障验收脚本位于 `main/manager-api/src/test/esp32/`，只允许创建并保留全新、唯一命名的隔离库。

本批交付、证据和未完成范围见 [ESP32 AI 交付记录](../../../docs/ai-customer-service/ESP32_AI_ONLY_DELIVERY_20261102.md)。

## 独立 LAN 开发候选

先复制 [候选配置模板](candidate.example.yaml) 到独立私有目录并设权限 `600`，填入已授权配置。`local_providers=true` 仅在新网关明确启用时让 VAD/ASR/TTS 使用该私有配置；Java 仍独占归属、AI 与工具权限。默认继续使用 Manager API 的 provider 配置。`qwen_workspace` 已迁入，保留显式 HTTPS workspace 地址。

```sh
python dev/esp32_gateway.py preflight --config /private/canary.yaml
python dev/esp32_gateway.py start --config /private/canary.yaml --runtime /private/new-unique-runtime --python /path/to/prepared/python
python dev/esp32_gateway.py stop --runtime /private/new-unique-runtime
```

启动器复制服务源码与指定的 VAD 模型到新私有目录，通过 `XIAOZHI_CONFIG_FILE` 读取配置，不重写 repo 或原生服务配置。它拒绝原生/Dev 保留端口、已有监听和旧 auth-bypass 名单，只停止自己创建且命令行仍匹配的 PID。灰度名单位于 `server.esp32.allowed_devices`；不在名单中的设备不会注册。启动器不改 OTA、数据库或原有服务；设备路由切换/回退由另行审查的 OTA 方案执行。

`deployment_profile=lan_candidate` 显式允许 RFC1918 LAN HTTP Manager；默认 `formal` 对非 loopback 地址要求 HTTPS。TCP 监听通过不等于语音依赖通过。正式公网 WSS/TLS 仍属于后续部署验收。

## 新回执与播放控制

网关 Result 新增 `status=succeeded|failed|unknown`，兼容既有 `success`。旧布尔字段与新状态矛盾会被拒绝。MCP 丢回执、超时或不可识别结构保持 `unknown`，不盲重试；音量 `true` 只确认固件软件 setter 返回，不证明实际声压。状态仅发布经过验证的 `audio_speaker.volume`。

Canonical TTS `start/sentence_start/stop` 附带该句不可变的 `turn_id` 和整数 `control_version`；旧合成任务保留旧 sentence 身份、旧 PCM 缓冲不会并入新流。设备带标签的 abort 只取消匹配的当前轮次；无标签旧客户端保留原兼容行为。Canonical 模式不初始化旧 Python LLM、Memory、Intent、增强提示或旧历史上报线程。

完整真实 provider 验收脚本为 `tests/esp32_full_voice_acceptance.py`：仅创建新的隔离库，以模拟 ESP32 运行真实 WS/Opus、ASR、AI、MCP 和 TTS；需要显式传入已审查的独立 JAR。`--scenario unknown` 验证丢回执持久未知并由模型解释，`--scenario volume` 仅对模拟软件音量发出显式设置。`tests/esp32_gateway_soak.py --seconds 1800 --output /private/proof.json` 是 Runtime/真实 HTTP 的墙钟模拟长测，TTS 回调/MCP 为合成，不替代真人声学验收。

Canonical网关使用条件取消 `POST /internal/esp32/sessions/abort-turn`：`fence/sessionId/turnId/controlVersion`，最后一项必须为真正JSON整数。Java在device→session→voice-turn锁序下确认仍为同一accepted轮次；旧tag返回409 `STALE_ABORT`，不改变新run。Python只忽略这个精确错误，连接代次/授权失效的其他409仍关闭旧连接。旧无tag abort入口保留兼容。

独立启动器的HTTP端口仅提供 `/health`，不暴露OTA/配网/视觉路由。模型、凭据、固件目标、灰度设备身份都需由受信任配置核对；模板不是可直接发给真实设备的生产配置。
