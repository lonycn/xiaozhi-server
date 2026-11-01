# 媒体弱网与断线隔离实验

`dev/tests/media_fault_proxy.py` 是本机 IPv4 loopback TCP 字节流代理，用于有明确授权的隔离测试链路，例如浏览器到实时 worker 的 WebSocket，或 worker 到本机测试管理服务的 HTTP。它不修改系统路由、网络接口、代理环境变量或运行中的服务。**不得把现有业务数据库清空、删除或重新初始化**。先确认代理入口只被实验客户端使用、上游为隔离服务，再运行；从代理获得的结果不能替代物理设备、真实客户网络或生产验收。

代理只接受数值 IPv4 loopback 地址作为 listen 和固定 upstream，拒绝通配、外部 IP、域名及未知配置字段；无远程控制接口，也不执行配置中的命令。默认无损伤，最多 16 个连接（可配置 1–64），每方向读取块最多 16 KiB，使用异步写入背压。JSON 场景只配置已列出的损伤动作；日志只保存累计连接数、转发字节、应用内丢弃字节和计划延迟等统计，不记录消息正文、凭据、票据或 URL。操作系统 socket 缓冲和关闭期间尚未被程序读取的字节不计入应用内丢弃数，因此该指标不是网络丢包统计。

## 运行示例

以下仅是隔离实验示例。先核对 18909 空闲、18903 是**单独启动且已确认的隔离测试上游**，并使实验客户端的 WebSocket 入口经适当的**测试专用配置**指向代理；不要将原 Dev 的 18899 作为示例上游。协议、Origin、TLS/WSS 终止位置、连接票据和现有鉴权必须由实验环境正确处理，不更改全局网络配置。不能仅打开代理就声称真实客户端经过该代理。不要把带票据的 URL、Authorization 或会话内容写入证据目录。`schedule_start: "first_accept"` 在第一个连接成功到达上游后启动整个脚本时间轴；不是为每个连接分别重启。默认 `startup` 以代理监听成功为起点。相同时间事件按文件顺序执行；未指定的策略字段保留之前值。

```json
{
  "listen": {"host": "127.0.0.1", "port": 18909},
  "upstream": {"host": "127.0.0.1", "port": 18903},
  "max_connections": 8,
  "schedule_start": "first_accept",
  "events": [
    {"at": 5, "up": {"delay_ms": 150, "jitter_ms": 40, "rate_bps": 32000}},
    {"at": 15, "up": {"blackhole": true}, "down": {"blackhole": true}},
    {"at": 20, "up": {"blackhole": false, "delay_ms": 0, "jitter_ms": 0, "rate_bps": 0}, "down": {"blackhole": false}},
    {"at": 30, "close_existing": true, "reject_new": true},
    {"at": 35, "reject_new": false}
  ]
}
```

保存为仅供实验使用的 JSON 文件，放在仓库外，然后运行：

```sh
mkdir -p /Users/lengwang/code/esp32/work/media-resilience-20261101
python3 /Users/lengwang/code/esp32/backend/xiaozhi-server/repo/dev/tests/media_fault_proxy.py \
  --config /Users/lengwang/code/esp32/work/media-resilience-20261101/scenario.json \
  --metrics /Users/lengwang/code/esp32/work/media-resilience-20261101/proxy-metrics.jsonl
```

用 `Ctrl-C` 或 `SIGTERM` 停止，程序关闭监听、现有连接与任务，输出 `stopped` 汇总。配置文件上限 64 KiB，JSON 不接受 NaN/Infinity；metrics 文件达到 5 MiB 后停止追加而不影响转发，使用新的证据文件可继续记录。代理进程退出不代表被测客户端已经停止采集或恢复；需分别检查客户端和服务端状态。`rate_bps` 单位为**字节/秒**，0 表示不限速；每块先等待配置延迟、抖动和对应速率延迟，再转发，调整对应策略可唤醒正在等待的块。`delay_ms` 和 `jitter_ms` 分别为每个读取块的基准与均匀随机抖动，负的总延迟截为零；网络实际形态还依赖 TCP 分块。`blackhole` 暂停读取/转发并由有界缓冲施加背压，关闭后继续；这模拟不可达期间的停滞，不随机删除字节。`close_existing` 只在该事件执行时关闭当前连接；`reject_new` 保持到显式恢复为 `false`。阻断、断开和恢复前后用 metrics 时间戳与被测系统状态交叉核对。

本代理**不处理 UDP，不模拟 WebRTC/LiveKit 媒体路径的丢包、抖动或拥塞**；即使对其 WebSocket 信令使用 TCP 代理，也不能据此声称 RTP 媒体弱网通过。实时 TCP 和真实 WebRTC 需分别安排隔离环境验证。

## P3 实时 AI 的恢复与授权

当前 worker 在 `ready` 中协商应用层心跳：浏览器每 2 秒发送带序号的 `ping`，匹配的 `pong` 需在该次发送后的 6 秒内到达。worker 发送 `pong` 前重验 Java 授权和租约；与 WebSocket 握手或 TCP 保活不是同一项证据。旧 worker 未声明心跳参数时，浏览器保留兼容路径，不能声称这条检测已启用。6 秒从发送未获回应的 ping 开始；按正常定时器调度，从最后一次成功 pong 到检测超时约最多 8 秒，浏览器后台限速或调度延迟可能更久，因此不承诺精确 6 秒断流或固定 SLA。

浏览器收到 `offline`，或已协商心跳超时、连接断开时，会关闭本机采集与播放并标记连接失败；`online` 仅更新网络可用提示，不自动重连、重放 PCM 或重新打开麦克风。用户恢复时必须先得到旧会话结束的权威结果，再重新同意并创建新会话，最后由用户主动开始录音或听音。授权失败、epoch／租约变化和终态应按服务端实际结果处理，不能仅凭页面或旧连接判断恢复。

REST 状态和注入轮询不可重叠。活跃 transport 上，暂时性失败可进入降级状态；连续三次失败停止本机媒体，成功完整轮询后重置计数；401、403、404、409、410 等权威拒绝立即失败关闭。轮询成功只能证明 REST 查询成功，不能把已关闭的 WebSocket 标成已连接，也不能把离线后的手动恢复替换成自动采集。已完成短样本可参照 [P3 上游故障证据](/Users/lengwang/code/esp32/work/media-resilience-20261101/p3-upstream-run-20261101T125113/proof.json) 和 [P3 传输断开证据](/Users/lengwang/code/esp32/work/media-resilience-20261101/p3-fault-run-20261101T125600/proof.json)；后者明确为 Python 端测试客户端，不是浏览器或物理设备验收。

## B5 人工媒体的恢复与清理

LiveKit SDK 在同一房间连接仍可恢复时负责原生重连；界面应反映本机连接状态，不以服务端会话仍为 `active` 断言双向 RTP 可用。最终断开时本机 track 被停止。需要重新加入时，客户端先重新读取当前会话、成员授权与房间代次，并由用户按界面选择重新连接；不能用旧权限或其他 Bearer 接管会话。服务端对 `connecting`／`reconnecting`／`active` 的成员最后出现时间执行超时检查，`support-media.reconnect-seconds` 默认 60 秒（允许 15–300 秒），超时由维护任务推进结束；这是状态检查阈值，不保证恰好 60 秒清理完毕。

用户挂断时先停止本地媒体，再请求服务端持久结束；即便 HTTP 不通，本机也应立即停采集。若结束请求未被权威确认，界面不能宣称服务端会话或 LiveKit 房间已经清理。已完成短样本中，[SDK 恢复证据](/Users/lengwang/code/esp32/work/media-resilience-20261101/b5-sdk-recovery-e7c3c67dfc/proof.json) 使用合成媒体；[浏览器离线挂断证据](/Users/lengwang/code/esp32/work/media-resilience-20261101/b5-browser-offline-hangup-proof.json) 记录了本机停止先于 HTTP 的检查，不能替代真人设备通话。

## UDP 网损与重启补偿证据

需要测试 RTP 弱网时，使用独立测试机或专用 network namespace／容器和专用 veth，仅对实验链路实施 `tc netem`；先确认目标接口、路由、隔离 LiveKit 和实验进程，保存改动前后的 qdisc、过滤条件和统计，结束后恢复并再次核对。禁止在原 Dev 主机全局接口上直接施加网损。分别记录方向、时长、延迟／抖动／丢包参数、故障开始和恢复时刻，并用两端实际 RTP 接收字节或帧增量、播放/解码结果和应用状态交叉验证。TCP 信令代理的指标不能代替 UDP 网损或 RTP 恢复证据；仅有连接成功、订阅事件、累计计数或合成媒体也不能证明真人现场体验。

声称 outbox **跨进程重启恢复**，至少同时保留三类按时间排序的证据：① 故障在清理调用前已生效，并有对应失败或不可达证据；② 故障期间持久 outbox 有未完成意图（例如 queued／重试记录，且尚未完成）；③ 实际进程停止和新进程启动的可核验 marker（如 PID、启动时间和对应日志），其后观察同一意图被处理及真实远端房间/成员清理。最终还需核对持久状态和 `media-drain`；仅重试次数增加、脚本打印“恢复”或普通成功清理不足以证明跨重启恢复。缺任一项标记为未完成或 inconclusive，不得把故障注入过晚的样本当作通过。

## 建议验证矩阵

| 场景 | 记录与验收边界 |
| --- | --- |
| 无损伤基线 | 确认真实会话链路和录音/播放状态，记录代理字节计数及会话时间。 |
| 上行、下行分别延迟/限速 | 分别观察语音与状态变化；记录具体配置、时间和恢复，不能以浏览器仍显示 active 代替媒体成功。 |
| 短黑洞后恢复 | 核对采集/播放停止或安全处理、服务端状态、明确恢复操作；区分 TCP 背压与物理丢包。 |
| 断开连接与短时拒绝新连接 | 核对旧资源关闭、状态提示和新会话授权；网络恢复不应悄悄开麦。 |
| 授权撤销/过期与恢复交错 | 单独记录授权和网络事件顺序、清理结果与已知异步延迟；不能把原 Bearer 替换成新 Bearer 接管旧会话。 |
| 持续 30 分钟通话 | 真实经过至少 30 分钟墙钟时间并保存开始/结束证据；关注会话上限、资源、音视频与用户可见状态。短跑、倍速时钟、合成输入或只看监听端口不能算 30 分钟验收。 |

运行代理自测：`python3 -m unittest discover -s dev/tests -p test_media_fault_proxy.py -v`。这些是本机真实 TCP 代理机制测试，不证明业务会话、真实网络或设备体验；正式实验记录应另附经脱敏的场景、时间、状态与限制。长时间通话期间还应分别记录权限、麦克风/扬声器和真人交互；技术样本不等于现场设备验收。
