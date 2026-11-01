# B5 Native SDK 技术联调记录（R3）

日期：2026-10-30。下述R3记录限于独立环境的真实 LiveKit/native SDK、HTTP、媒体 worker与只读SQL链路；**不等于真人通话或现场设备验收**。原Dev恢复后的浏览器技术媒体进度另列文末，不用后续结果改写R3当时边界。

## 环境与证据边界

- 独立 API：`127.0.0.1:19897`，运行预先准备的 `manager-snapshot-fixed.jar`；测试库 `xiaozhi_media_test_b5_http_r1`。本轮没有修改应用源码。
- Python native LiveKit SDK `1.1.20`。音频为合成正弦波；视频为 native `VideoSource` 发布的移动色块/编号技术画面，由另一身份的真实 `VideoStream` 接收和解码。没有使用物理麦克风、摄像头或扬声器，也没有操作浏览器。
- B3/B4 使用真实媒体 worker 处理实际接收的 JPEG；不是手工填写 ready、伪造解码结果或替换成未经过网络接收的图片。
- 本机证据目录：`/Users/lengwang/code/esp32/work/`。摘要为 `b5-native-r3-summary.json`；音频/转接为 `b5-native-rtc-proof-r3.json`；视频完整链路为 `b5-native-video-proof-r3-47a400e7.json`。

## 双向音频、转接与隔离

会话 `2116147226362220546`，转接 `2116147241679818754`：

| 阶段 | 接收方 | 实收 int16 样本数 | 对应 PCM 字节数 | 非零音频帧数 |
| --- | --- | ---: | ---: | ---: |
| 原房 generation 1 | 客户 | 73440 | 146880 | 153 |
| 原房 generation 1 | 原专家 | 72480 | 144960 | 151 |
| 新房 generation 2 | 客户 | 293280 | 586560 | 610 |
| 新房 generation 2 | 目标专家 | 294240 | 588480 | 613 |

计量更正：既有 R3 proof 的 `rx_bytes` 字段实际上累加 `len(AudioFrame.data)`；该 SDK 属性是 int16 memoryview，长度单位是样本，字节数为其两倍。上表保留原数值并明确换算，不改写既有 proof。双向非零媒体实收结论不受影响；这些是解码 PCM 计数，不是网络 RTP 包字节统计。

实际验证：

- 原房双向媒体就绪后，客户同意、目标专家接听，再接入独立新代次；新房双方真实收流后服务端切换当前代次与负责人。
- 转接后原专家读取当前媒体会话返回 404，继续发送返回 403；原专家历史看不到目标专家在切换后发送的新消息，历史截止生效。
- 对比旧、新 JWT 房间 grant，二者房间不同。旧 JWT 实际重入结果为**仅旧空房**：对端数 0、实收 0。此结果说明新房准入隔离；不能声称踢人或删旧房撤销了自托管 LiveKit 已签发的 JWT。
- 媒体挂断后 `media-drain.cleanup_complete=true`，人工 request 仍为 `active`；挂断没有 resolve 人工服务，也没有恢复 AI。

## 实际视频解码、抓帧与挂断后标注发送

视频会话 `2116148688597258242` 沿用转接后的同一 conversation `30823214c177486eae28b3474688c5b8`、request `31c18f968b8643c7970518f4aaeda07c`。

1. 客户明确同意 microphone、camera、allow_snapshots；目标专家只同意 microphone。客户发布 camera 技术轨，目标专家实际订阅并解码。
2. 目标专家最终收到 **22 个不同解码帧**，尺寸 **320×180**，累计 **3801600 字节 RGB 解码像素**；这是解码后的像素字节，不是网络压缩字节。抓帧时至少已有 3 个不同帧，双方也有非零音频实收。
3. 从实际 `VideoStream` 解码帧编码 JPEG，5833 字节；B3 上传、检查并达到 ready 后，调用 B5 frames。相同请求重试返回同一 frame。
4. 随即挂断媒体并等 drain 完成，确认人工 request 仍 active；之后才等待 frame 完成、创建 B4 标注、取服务端私有渲染 JPEG，并通过现有人工消息接口发送。发送重试返回同一 message。
5. 最终回复 `c178a4d2f4b94492aebf0c0c5184eb61`、消息序号 3。只读 SQL 确认原始抓帧附件、frame 附件、标注 render 附件三者都绑定到该消息及同一序号。

| 证据 | 附件 ID | SHA-256 |
| --- | --- | --- |
| 实际解码 JPEG 原件，5833 字节 | `2116148710348918786` | `00747e71bdcbbdce7828d81f20bd73c4424431900562efbac561bc1e4ea0f47f` |
| B5/B4 frame，5916 字节 | `2116148714954264577` | `7028a852e54b89fa9af9ead0969a769fe9a7061af07d705379aa171d8d89b7e6` |
| 标注 render，7707 字节 | `2116148727247769601` | `1302da85804484cbe5830568d47df4dbea43e67aeb442f4c5a3c52a61f2695df` |

三项哈希已通过真实附件 HTTP 读取补核；原件的 original 变体与最初上传 SHA 一致。B3 的 source display 是单独归一化变体：5718 字节，SHA-256 `69ca27c7035c86bd03a558f1af86b27b29d0673f923ce4b355b925338848e2ff`；它与原件哈希不同不代表原件被改写。

Frame ID：`2116148714945875970`；annotation ID：`2116148727239380994`。本机技术图片为 `b5-native-decoded-frame-r3-47a400e7.jpg` 和 `b5-native-annotation-r3-47a400e7.jpg`，已目视核对实际接收画面与服务器渲染的箭头/文字。

服务器生成的来源元数据包含 `source_type=client_live_capture`、`verification=client_declared`、media session、generation、captured_at、source attachment、frame ID。**该元数据记录客户端声明及授权关系，不代表后端核验像素确实来自摄像头。**

ACL 实测：发送前客户读取未发送 frame 返回 404；发送后客户可读三份证据、frame 与完整 annotation scene；陌生人及已离任原专家读取三附件均返回 404。挂断后 SDK 后续 ready 请求被 403 拒绝，没有恢复已结束的媒体授权。

## 失败尝试、修正和警告

- 既有 R2 失败证据 `b5-native-rtc-proof-r2.json` 保留。R3 使用已准备的 fixed jar 重新创建会话，未覆盖该失败记录。
- 视频首试 `b5-native-video-proof-r3-717dbc28.json` 为接收超时；第二次 `b5-native-video-proof-r3-29bee88d.json` 明确定位为验收脚本错误：`VideoStream(format=RGB24)` 已返回 RGB24，又调用 `.convert(RGB24)`，native SDK 报 `rgb24 to Rgb24 is not supported`。camera 轨已实际订阅，不能把当时尚未完成的解码写成成功。
- 脚本改为对已 RGB24 帧直接读取像素，仅对其他格式转换，随后得到上述完整成功 proof。没有为此修改 B5/其他应用源码。
- SDK 在解释器退出时出现 ignored `FfiHandle.__del__` `AssertionError`。功能断言及成功脚本退出状态均通过；退出清理警告仍保留在日志，不表述成“无警告运行”。
- 日志：`b5-native-rtc-check-r3.log`、`b5-native-video-check-r3.log`、`b5-native-video-check-r3-attempt2.log`、`b5-native-video-check-r3-attempt3.log`、`b5-native-video-media-worker-r3.log`。脚本为 `b5-native-rtc-check-r3.py` 和 `b5-native-video-check-r3.py`。

## 数据保留与尚未验收

本轮没有 DELETE、DROP、RESET 或清空业务数据。只读 SQL 确认 R2、R3 音频、两次失败视频和成功视频共 5 个 media session 行仍保留，并通过正常 end 收口；所有 proof、失败日志和媒体证据保留。只启动并停止本轮面向 19897 的测试媒体 worker，未修改原服务配置或停止原媒体 worker。本文不把临时测试进程/前端的后续启停写成持续在线承诺。

截至R3当时，浏览器权限与UI/WebRTC链路未验收；恢复后的部分浏览器结果见下节。两名真人的麦克风/摄像头/扬声器、移动端及现场终端、30分钟持续通话、Wi-Fi/蜂窝切换、弱网与断线恢复仍未验收。本记录也不证明浏览器双房切换时的扬声器防回声、权限提示或前端草稿交互；这些需独立浏览器与设备证据。

## 原 Dev 恢复后的双浏览器补验

原Dev18894/API18893及原S3恢复后，独立浏览器身份已完成首轮实际双端WebRTC音视频接收（视频192帧），实际截图上传S3并生成B5 frame。媒体为明确的本地合成语音与canvas技术画面，经实际MediaStream/RTP传输；没有物理麦克风、摄像头或扬声器。浏览器证据 `work/browser-dual-media-proof.json`。

抓帧后的标注编辑器被工作台后台poll关闭，不能记作浏览器编辑发送成功。对应poll/invite修复已完成定向检查；第二通实际active，frame `2116191817604976642` 后p2正常挂断complete且人工仍active；编辑器跨多轮poll/挂断保持，箭头和circle共2元素保存annotation `2116191926321336322` 为ready，可视预览及加入回复草稿通过，客服文字草稿保留。实际发送已完成，staff消息 `e0cb32f2e6ca491084e1f518f51bf2ae` 的文字和image附件在客户UI可见；source/frame/render三引用同条消息，scene2元素，三个实际S3 GET SHA匹配，证据 `work/browser-backend-proof.json`。浏览器换房转接现已完成：客户同意、目标B接听后独立新房generation2达服务端active；B实收audio409773bytes/1659packets、video100392bytes/147packets/105frames，客户audio408378bytes/1653packets。原A旧PC断开、UI禁用回复，原身份POST写403 CURRENT_ASSIGNMENT_REQUIRED；B采纳原request并可见AI/标注历史，UI回复客户可见。证据 `work/browser-transfer-proof.json`、`work/browser-stale-expert-proof.json`。45秒旧邀请过期的尝试保留；最终媒体 `2116193127137988610` ended/complete、gen2，transfer `2116193234969350145` completed；gen1/2均cleaned、12member reservation released且busy_slot为空。request仍active/HUMAN_ACTIVE、assignmentB，B消息 `a4fe6ed8394549229aab3bc193cfcc41` completed。5项delete_room queued无running lease，是cleaned墓碑15分钟补偿窗口，不代表房间未清理，也不声称全部outbox done。空video最小修复定向6/6和build通过，实际新通话active时仅1个video（320/ready4），挂断complete后video/audio均0且所有技术track ended，证据 `work/browser-dom-cleanup-proof.json`。390×844手机历史最小200px、纵向滚动后发送可达、无横向溢出；移动CSS改动build通过。这里媒体挂断后人工仍active，不把另一AI会话的文字恢复结果当成人工服务已结束。详见 [恢复与补充验收](./DEV_RECOVERY_VALIDATION_20261030.md)。

## 2026-11-01：31 分钟、受控弱网与恢复补验

本节补充原先未完成的持续通话与恢复边界，不改写前述历史记录。以下均为独立 Dev 技术验收：新 API `127.0.0.1:18902`、新库 `xiaozhi_media_test_resilience_8e4702642371`、专属 LiveKit `17980/17981/17982`；真实人工工作台使用独立前端 origin `18907`。Python LiveKit SDK `1.1.20` 只读复用原虚拟环境，未修改 aimeet 依赖或服务。音频为 440/660 Hz 合成信号；视频为 320×180、15 fps 动态技术画面；浏览器麦克风使用 AudioContext 技术源，没有请求物理麦克风或摄像头。

证据基目录为 `/Users/lengwang/code/esp32/work/media-resilience-20261101/`。本轮只向明确的新测试 schema 追加 fixture 和正常 API 记录；没有删除、清空、重初始化任何已有数据库或卷。故障注入只作用于专属 LiveKit 容器网络命名空间 `net:[4026534437]`，由独立 NET_ADMIN 工具容器执行；没有修改原 `17880` 共享服务、本机全局网络或原 Dev 服务的网络规则。

### 持续双向 RTP 与真实损伤

主会话 `2116753435029012481`，generation 1，正常进入服务端 active 后持续 **1860.872 秒**。双方最终各输出音频解码帧 **186170**；客户、客服分别收到视频解码帧 **27639 / 27547**。这些是解码帧计数，不是 RTP 包数。双方实际接收的技术图像保留为 `customer-decoded.jpg`、`staff-decoded.jpg`，已目视检查。

期间按相互独立的阶段注入并保留了内核与客户端证据：

| 阶段 | 实际作用范围 | 实际观测 |
| --- | --- | --- |
| 60 秒 moderate | 专属 namespace egress：120±40 ms 延迟、3% 丢包、1500 kbit/s | `tc` 累计 dropped 321；Native inbound RTP 的 loss/jitter/RTT 增加，视频持续解码 |
| 8 秒全 egress 黑洞 | 专属 namespace 所有 egress | `tc` dropped 1215；存在一次 B5 ready `503 LIVEKIT_TIMEOUT`，后续恢复，未被写成全程无错误 |
| 12 秒 UDP-only 黑洞 | IPv4 protocol 17，保留默认 TCP | Native peers 的选中媒体路径为 UDP，实际视频停止后恢复；HTTP 17980 保持 200 |
| 12 秒 RTC TCP 黑洞 | IPv4 TCP source port 17981，保留 signaling/RPC TCP 17980 | 单独验证浏览器 TCP 媒体；这一阶段不计作 Native UDP 长测的媒体损伤 |

不能把“配置了 UDP 丢包”直接等同于浏览器受损：本轮 Chrome 实际选择 TCP，Native SDK 选择 UDP，分别用 ICE/RTP 统计核实。浏览器 RTC TCP 阶段有真实内核丢包及 inbound bytes 停止；没有用 CDP HTTP offline 代替 WebRTC 媒体传输层损伤。

303 个周期样本记录了去重后的原始 RTP 统计、解码帧、RSS、线程与 asyncio 任务。Native 进程 RSS 为 **75.84–101.07 MB**、末次 **81.49 MB**；asyncio 任务始终 **15**。最后采样的客户音频/视频 RTP `packets_lost` 为 94/386，客服为 82/389；丢包、重传、FEC/PLC 与传输协议会影响这些累计值，不能把它们当成内核所有被丢包的精确总数。最终最长视频解码间隔为 **15.467 / 15.064 秒**，包含 12 秒故障及恢复；这不是无损通话结果。

结束后 `media-drain` 返回 `cleanup_complete=true`、`pending_sessions=0`、`pending_operations=0`；原人工 request 仍为 active。证据目录 `b5-long-7e311492c9/`，含 `proof.json`、`stats.ndjson`、`rtp-summary.json`、`rtp-series.json` 和 `fault-markers.json`；根目录另保留 `netem-events.jsonl`、`netem-udp-events.jsonl`、`netem-tcp-media-events.jsonl` 及主进程 `b5-long.stdout.log`。

### 短掉线、超时、转接失败与旧 JWT

| 场景 | 结果 | 证据目录 |
| --- | --- | --- |
| SDK signal/full reconnect | 真实 RTP 恢复；full reconnect 的 SDK reconnected 事件约 0.12 秒后出现，双方重新订阅媒体。此项是 SDK 受控故障，不冒称 Wi-Fi/蜂窝现场切换 | `b5-sdk-recovery-e7c3c67dfc/` |
| 客户主动离线超过默认 60 秒 | 客户于 13.493 秒关闭，73.835 秒观察到 ended / `MEDIA_CONNECTION_TIMEOUT`；旧 epoch token 请求 409，原人工文字消息仍可发送，request active，drain 完成 | `b5-long-offline-dc322d8ee3/` |
| 转接目标在准备房真实收流后离线 | 新房 generation 2 双方已真实解码；目标未提交 ready 即断线。transfer 到期后原 generation 1、原专家和原服务保留，原媒体继续接收；重复 create-transfer 返回同一 transfer | `b5-transfer-target-failure-0e99e4e6a6/` |
| 未过期旧 JWT 与新会话隔离 | 旧 JWT 尚余 48 秒时能加入旧空房；新会话使用不同房名并真实双向收流，旧 peer 音频/视频实收均 0。旧 epoch token API 返回 409，最终 drain 完成 | `b5-old-jwt-isolation-017beb5db0/` |

旧 JWT 结果证明的是旧、新命名空间隔离，**不是旧 LiveKit JWT 被即时撤销**。本轮 SDK reconnect 和新会话恢复分别记录，未把服务端已终止会话的旧能力恢复成有效能力。

### 真实浏览器状态与两个前端修复

正常验证码登录后的客服工作台完成了真实合成音媒体通话，服务端核实了双方 SID 与媒体证据。浏览器使用 Ego TaskSpace 36 的独立客服页 p2 / origin 18907；没有在浏览器注入 Bearer，也没有操作客户 p1 的认证状态。

1. 修复同一目标再次转接时 `transferId` 被清空而 `transferSignature` 残留的问题。真实 UI 发起、取消后再次选择同一专家，两次 POST 均为 200，`client_transfer_id` 为不同的非空 UUID。丢失响应后保留原请求 ID 的路径另有定向单元测试。实际证据为 `b5-browser-transfer-proof.json`、`b5-browser-second-transfer.png`。
2. 补充本机媒体连接仍在、但预期接收轨连续无新 RTP 时的 `media.waitingForMedia` 提示；RTP 返回后自动清除。远端主动 mute、取消订阅、stream paused、publication disabled、后台/准备房 adaptive video 暂停均排除，SSRC/计数回退重新开始宽限。浏览器 RTC TCP 故障期间，DOM observer 于 **05:14:07.355Z** 记录“媒体接收暂时中断·正在等待恢复”，于 **05:14:12.354Z** 自动恢复已连接文案；媒体连接保持 connected，没有重新采集设备或新建房间。500 ms 原始样本测得最长约 **13.497 秒无新 inbound RTP bytes**，与 12 秒 TCP 黑洞及恢复对应。提示瞬间未拍到截图，不能把恢复截图写成该瞬间截图；持续 DOM observer 和 RTP 样本保存在 `b5-browser-rtp-tcp-proof.json`、`b5-browser-rtp-all-proof.json`，恢复图为 `b5-browser-rtp-recovered.png`。

另对**本次 end HTTP 请求不可用**单独注入故障，不将它计入 RTP 丢包。点击真实“挂断媒体”按钮后，实际 `MediaStreamTrack.stop()` 将音频从 live 变为 ended，发生在 HTTP 尝试之前约 0.1 ms；本机 joined/microphone 立即变为 false，UI 显示本机媒体已断开并要求明确重连。服务端随后按超时收口为 ended / `MEDIA_CONNECTION_TIMEOUT`、cleanup complete，人工 request 仍 active。证据 `b5-browser-offline-hangup-proof.json`、`b5-browser-offline-hangup.png`。

定向前端检查为 **44 项通过**，这是最终全量检查的子集，不能重复相加；i18n 六个 locale 对齐。没有在此填写尚未完成的 CI/统一构建结果。浏览器结束时已恢复 getUserMedia/fetch/RTCPeerConnection/track-stop instrumentation，清除 observer/timer，停止 oscillator、关闭 AudioContext 并 reload；技术 audio DOM 为 0、无 Vite overlay，重载后的 Runtime/Log error 检查为空。页面整体 QA 摘要为 `b5-browser-qa-proof.json`，最终清理及恢复截图均留在证据基目录。

### 严格 API 重启与持久 outbox

重启验证使用额外 API **18904**、新库 `xiaozhi_media_test_restart_caa21bfb8abb`、独立 Redis 34779、独立 room prefix；LiveKit 管理 RPC 经 loopback proxy 18919。没有重启运行长测的 API 18902，也没有重启 LiveKit。

第二轮 probe `30ae917ab6` 先建立真实双向 AV，再同时要求匹配的 fault marker、新 fault epoch，以及 RPC 实际不可达，才发起 end。此时 `delete_room` 已持久为 queued。根代理正常终止并重启仅 API 18904，PID **67646→69644**；脚本另核对同 probe 的重启 marker、不同 PID 与晚于 fault 的重启 epoch。RPC 恢复后第 6 次 delete 尝试使旧房参与者被服务端断开，随后 `cleanup_complete=true`，原人工 assignment/request 保留。

成功证据为 `b5-restart-outbox-30ae917ab6/`，并关联 `rpc-outage-trigger.json`、`b5-restart-api-marker.json`、`rpc-proxy-outage.jsonl`、`rpc-proxy-restored-r2.jsonl`。清理完成后的历史 `delete_room` 可能被 15 分钟墓碑巡检幂等重新排为 queued；room 已 cleaned、session cleanup complete、drain pending operations 0 时，不把该历史队列状态误判为未完成断流。

第一轮 `b5-restart-outbox-cbe9bdbeb5/` 因 end/清理早于 RPC 故障触发，**保留并明确标为 inconclusive**，不计作重启验收成功。没有覆盖或删除这轮日志和数据库记录。

### 本轮限制与保留事项

- AudioStream 会输出 PLC/掩盖帧，因此音频解码连续、`max_audio_gap=0` 不能证明没有 RTP 中断；以原始 bytes/packets、视频解码和内核 dropped 共同判断。
- Python SDK 在解释器退出时仍有 ignored `FfiHandle.__del__` `AssertionError`；日志保留。主长测 exit 0、业务与清理断言通过，但不表述成“无警告退出”。SDK full-reconnect 首轮技术脚本曾保留旧 reader，任务 15→23；这是验收脚本所有权问题，已在后续脚本中对替换/退订 reader 执行 cancel，不能当成产品持续泄漏结论，也不将它隐藏。
- 本轮新“停流时禁止保存冻结 snapshot”guard 已覆盖带 camera/snapshot 授权的单元场景，**浏览器本轮为 audio-only，未完成这条新 guard 的真实摄像头/视频抓帧 QA**。主 Native 长测保留的 JPEG 是真实网络解码帧，但未以它冒充本轮新的 B3/B4 业务抓帧全链路；历史完整抓帧链路仍以前节证据为准。
- 浏览器 fixture 首次邀请因操作超过 45 秒而过期；后续 helper 的“连接前读取 local_participant”和 video 参数被 VideoSource 变量遮蔽两项脚本问题已修正，仅终止了自己的失败 helper 进程。相关 `b5-browser-setup*.stdout.log` 与所有 fixture 行保留，不把这些失败改写成服务端通过记录。
- `b5-long-7e311492c9/` 是本轮唯一 31 分钟长测 proof。浏览器 fixture helper 导入公共脚本时产生的其他 `b5-long-*` 技术目录不代表另一次长通话完成，不能按目录名或其初始 running 字段累计验收次数。
- 以上验证不是真人听感、物理麦克风/摄像头/扬声器、移动端、Wi-Fi/蜂窝切换或外部业务 API 验收；本轮也不证明线上生产网络的具体恢复时限。
