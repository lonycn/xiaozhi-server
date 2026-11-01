# 媒体弱网、断线恢复与长通话验收

日期：2026-11-01，时区 Asia/Shanghai。基线 Dev `6d3c41e`；本批没有修改 Java 或数据库迁移。测试为本机隔离技术验收，使用真实百炼、LiveKit、TCP/UDP 传输与合成音视频，不调用真实业务 API，不采集真人麦克风或摄像头。

## 环境与数据边界

- 原 Dev：Vue 3 `18894`、API `18893`、实时 worker `18899`，原 MySQL/Redis 容器和卷继续使用。部署前确认 P3/B5 均无活动通话，只重启实时 worker，未重启原 API 或数据库。
- 主测试 API `18902`、Redis `34778`，新建数据库 `xiaozhi_media_test_resilience_8e4702642371`；长测使用独立 worker `18914`、`18918`，短故障测试与浏览器使用其他专属进程，互不重启。
- 重启测试 API `18904`、Redis `34779`，另一全新数据库 `xiaozhi_media_test_restart_caa21bfb8abb`。原数据和所有成功、失败、未闭合试验样本均保留，没有 DROP、TRUNCATE、清库或覆盖现有数据库。
- 新建 LiveKit 17980/RTC TCP 17981/UDP 17982 与独立网络命名空间。NET_ADMIN 只授予该命名空间的测试工具容器；不改变宿主机路由、全局代理、共享 17880 服务或其他项目网络。
- 唯一浏览器 TaskSpace 36，客户 `18906`、客服 `18907` 使用不同 origin 正常登录，未注入浏览器 Bearer。P3 最终使用生产构建预览，排除开发 HMR 重载对草稿和选中会话的影响。

私有配置、认证、原始统计和脚本均在 `/Users/lengwang/code/esp32/work/media-resilience-20261101`，不提交凭据、lease 或完整信令内容。

## 已修复的问题

1. P3 只依赖连接事件时不能及时识别半开或黑洞连接。新增 ready 协商的应用心跳：每 2 秒探测，发送后等待匹配 ACK 最多 6 秒；旧 worker 不协商时保持兼容。从最后成功 ACK 到判失联通常可约 8 秒，调度、后台冻结和网络故障下不承诺固定 SLA。心跳只做授权与状态确认，不写 provider 内容或对话账本。
2. 浏览器 offline 立即停止采集和播放，online 只提示恢复；原会话须权威终止后再开新会话，不重播未确认语音，不自动重连或开设备。恢复入口和已确认终态后的正常新建入口均重新确认设备；不确定 create 仍保持原参数和幂等编号。
3. REST 轮询单飞且有限容忍暂时失败。认证、权限、资源失效仍立即失败；已经断开的 socket 不会因后续 REST 查询成功而误报 connected 或清掉原错误。
4. B5 转接成功后同时清 client ID 和参数签名；同目标的新转接生成新 UUID，丢失响应后的同一请求重试保留 UUID。
5. B5 信令连接正常但远端 RTP 停止时，显示等待媒体恢复并停止提供冻结截图。只观察已订阅、启用、未 mute、未 paused 的有效媒体，视频另排除后台、自适应暂停；计数回退重置观察窗口。默认 6 秒观察阈值叠加统计轮询，不是固定断流 SLA。
6. AI runs 功能关闭时，旧框架可能返回 HTTP 200 + 应用码 404。仅该列表接口将其归类为功能不可用，保留正常工作区与媒体；其他接口、认证、权限、500 和网络错误不被吞掉。
7. 新增有界 loopback TCP 故障代理及真实 socket 自测；限制配置、输出大小、非有限值、连接数与关闭竞态，不记录 payload。黑洞采用背压暂停字节流，不把 TCP 停滞称为 RTP 随机丢包。

## P3 真实弱网与恢复

真实百炼 `qwen3.8-omni-flash-realtime`，本轮只使用有限合成短语。TCP 代理实际位于被测连接中，所有旧票据、lease 和历史检查使用真实 API/SQL，不靠 mock 判成功。

| 场景 | 结果与证据边界 |
| --- | --- |
| 双向延迟 120±70ms、160KB/s 限速 | 真实音频、回复、usage 完成。 |
| 1.2 秒双向黑洞 | 同 socket 恢复；持续黑洞后关闭旧连接。 |
| 强制 TCP 断开 | 实测 close code 1006；原 conversation 中显式结束旧会话、新 session 恢复，原历史保留，旧票据被拒绝，无未确认 PCM 重播。 |
| 仅专属 worker 的 manager 延迟/断流 | 约 600ms 往返仍可工作；持续不可达会关闭旧 WSS，恢复后不自动连接。 |
| 仅专属 provider TLS 隧道断开 | 真实连接失败关闭；旧 lease 回调 HTTP 409，账本不被迟到结果改写。 |
| 浏览器 offline→online | 音轨结束、草稿保留；恢复时输入设备数量不增加，重新确认勾选均为 false。 |
| 浏览器仍 online、实际 TCP 黑洞 | 收到真实 ready/pong 后失去 ACK，页面判失联、音轨结束；显式恢复须确认旧会话结束，设备不偷开。 |

恢复后的真实浏览器再输入一段合成语音，回复“自动听音正常。”，usage 512 token；结束后两条技术音轨均 ended、audio/video DOM 为 0、草稿保留，window error/unhandled rejection 为 0。390×844 手机宽度无横向溢出。技术输入和观察脚本在结束后移除并 reload，恢复正常设备入口。

主索引 `p3-acceptance-summary.json`，故障 run、浏览器 `p3-browser-*-proof.json`、截图与代理指标均保留。早期浏览器回合遭遇 Vite HMR 重载，因此未将其当最终草稿恢复结论；生产构建重新完成同一流程。旧 TCP 试验先在新 conversation 恢复，随后另补同 conversation 和有历史样本，分别保存，不偷换验收范围。

## 真实长通话

### 原语音账本基线

原 worker Session 主链实钟通过：会话创建到 WSS 关闭 1800.295 秒，provider 就绪后实际连接 1795.877 秒，握手差异单列；数据库创建到结束 1800.811 秒，终态为 expired / SESSION_LIMIT。6/6 真实短语完成，ASR、committed、response.done 和账本一致，无多余 response.create。RSS 30.2–44.2MB、任务 8–9、事件队列峰值 1/32；结束后 connections 0、任务 2、RSS 约 21MB。

此 worker 在产品新增心跳代码前启动，没有独立的启动字节码指纹；不能把它冒充最终浏览器心跳版本的 30 分钟验收。其 provider/control/server/config 与最终源码一致，精确差异和时间保存在 `p3-source-audit.json`。

### 最终应用心跳版本

待最终 18918 实钟样本结束后填写。启动前已保存源文件 SHA 和实际加载函数 marshal/bytecode 指纹，测试客户端持续发送匹配心跳，并有独立实钟/SQL 上限观察器。不得用前一轮或缩短时钟替代本条结果。

### B5 人工音视频

实收双向音视频 1860.872 秒，含 60 秒 120±40ms 延迟、3% 丢包、1500Kbit/s 限速，8 秒全 egress 黑洞及 12 秒仅 UDP 黑洞。双侧音频解码各 186170 帧，视频 27639 / 27547 帧。303 个统计样本，RSS 75.84–101.07MB、最后 81.49MB，任务始终 15。最长真实视频解码间隙 15.467 / 15.064 秒，不能称全程无损；音频 PLC 会补帧，解码数不能作为无丢包证据。结束后 cleanup complete，pending session/operation 为 0，人工 request 仍 active。

内核与 RTP 证据分开核对：moderate 阶段 tc 实际 dropped 321，8 秒黑洞 dropped 1215；Native SDK 的 selected candidate 为 UDP。浏览器 selected candidate 为 TCP，因此 UDP 黑洞没有被称为浏览器媒体受损。随后单独对 RTC TCP 源端口 17981 丢弃 12 秒，17980 信令/RPC HTTP 仍 200；浏览器 inbound bytes 停止后恢复，05:14:07.355Z 显示等待媒体，05:14:12.354Z 恢复正常，localState 保持 connected，无新建房或重新采集。

原始数据在 `b5-long-7e311492c9`、`netem-*-events.jsonl`、`b5-browser-rtp-tcp-proof.json`；更多细节见 [B5 本轮追加记录](B5_RUNTIME_VALIDATION.md)。瞬态无 RTP 提示由持续 DOM observer 和原始 RTP 统计覆盖，截图未赶上瞬态不被伪称已拍到。

## B5 断线、重启和隔离

- SDK signal/full 重连、客户离线超过 60 秒后 MEDIA_CONNECTION_TIMEOUT、原人工文字继续、旧 epoch token 409 均完成真实样本。
- 客服浏览器对相同专家两次创建转接均 POST 200、非空且不同 UUID；同请求丢响应的重试幂等由定向测试覆盖。
- 仅使本次 HTTP end 失败时，本机音轨在 HTTP 尝试前 stop，页面已断开；服务端最终超时清理，原人工 request 保留。
- 独立 18904 的 RPC 不可达时，end 已留下真实持久化 delete_room queued；正常重启进程 PID 67646→69644，恢复 RPC 后第 6 次尝试成功，双方 SDK 被 server 断开、cleanup complete、人工服务保留。故障、pending 和重启 PID marker 均存在，不能仅凭健康接口认定恢复。
- 第一轮重启脚本在故障施加前已完成清理，记录为 inconclusive 并保留；第二轮加 probe ID / 实测故障 / 重启 marker 三重 gate 后才通过。
- 尚余 48 秒有效的旧 JWT 只能进旧空房，后续新会话实收音视频且旧 peer 收包为 0；不宣称 JWT 瞬时失效。cleanup 后 outbox 可能保留 15 分钟的幂等墓碑补偿，room 已 cleaned、drain pending 0 与之不矛盾。

SDK 1.1.20 退出有已知 ignored FFI 析构 warning；功能断言、正常清理和进程 exit 0 均通过。full reconnect 测试脚本曾重复保留旧读流任务，已修复脚本并保留旧失败现场，不能归为产品泄漏。

## 检查与发布

本批最终：Vue 443 单元/组件、10 contract、13 snapshot，6 语言各 1861 key，生产构建通过；实时 worker 41、Dev/故障代理 60 项通过。定向集合都是子集，不重复累计。Java/迁移未改，运行 JAR 沿用上一批全量 508 项通过的基线，未伪称本批重跑 Java 全量。源码空白检查通过，最终 CI 与数据保留结果在本轮结束后追加。

本轮不替代真实 Android/ESP32/真人麦克风扬声器摄像头、真实噪声回声、Wi-Fi/蜂窝切换、企业防火墙、HTTPS/WSS/TURN 公网部署验收。新增冻结截图 guard 只有单元验证，本次浏览器媒体提示样本为 audio-only，不声称补了真实摄像截图链路。真实业务 API 仍未提供，相关委托查询、工单提交与派单保持原未完成状态。
