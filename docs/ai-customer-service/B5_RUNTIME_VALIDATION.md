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
