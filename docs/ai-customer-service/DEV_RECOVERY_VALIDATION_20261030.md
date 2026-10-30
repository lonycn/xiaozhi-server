# 2026-10-30 Dev 恢复与补充验收

本记录补充 [整合记录](./INTEGRATION_20261030.md)，记录本机原 Dev 恢复后的实际结果。原18节规划保持完整。Docker/S3恢复、实际提供方链路、浏览器技术媒体及真人现场验收分别判断；本轮浏览器技术链路及最终收尾已完成，实体设备和外部网络边界保留。

## 原 Dev 与数据保留

用户明确授权后，`colima` 和 `smartmaster` 两个 Docker profile 已完成正常 restart。22:47前后曾生成并核验原OS/data磁盘的APFS克隆和恢复快照；当前这些克隆/快照路径无法找到，尚在排查，不能写成“当前备份仍保留”。原两个profile磁盘和所有原卷目前仍在，20:39 SQL备份文件仍在；恢复后另建一致性只读SQL备份并验证现存，见下文。原 `--rm` 技术测试容器随重启自动消失，其匿名卷仍保留，并创建只读 preserve 容器保护引用；没有以删除容器、清空卷或重置数据库作为恢复方法。

原 Dev 前端 `127.0.0.1:18894`、API `127.0.0.1:18893` 已恢复，连接原有 Docker MySQL/Redis。正常迁移从115增加至116，恢复前核对基线有记录；不以当前无法定位的恢复快照作可用备份承诺。原始25条设备历史、1个用户、15条统一消息是恢复前的核对基线。随后为浏览器验收新增3个明确的 QA 用户和技术测试行；不能把这些授权新增行误报为“恢复后全库行数不变”。本轮未执行DELETE、DROP、TRUNCATE、清库或以旧备份覆盖原库。恢复克隆/快照当前无法定位是独立的未闭合问题，不能用“没有主动删除”推断它们仍可用。

故障期间使用过的 `18895`/API `18901` 是20:39备份的隔离恢复副本；其进程现已停止，数据、配置和manifest保留，可按对应manifest/私有配置恢复。副本不能视为原库最新状态或覆盖回原库。较早整合前备份的11条消息/112条迁移与恢复前原库的15条消息/115条迁移属于不同时间点。

Docker与存储恢复证据位于本机 `work/docker-recovery-current.json`、`work/storage-recovery-recovered.json`、`work/storage-recovery-after-proof.json`。其中旧目录指针属于当时运行记录，不能仅凭该指针推定当前备份位置或所有其他项目服务的持续可用性。

恢复后通过只读consistent快照新建并验证现存的SQL备份：`dev/.runtime/backups/verified-post-recovery-20261030T153851Z.sql`，470657字节，SHA-256 `b2d7d6cdf08dc748e3869e1414ccd01da5c4a0a5cab093abb1cabc861875a1ab`。证据 `/Users/lengwang/code/esp32/work/dev-post-recovery-backup-proof.json`。现存20:39备份和该恢复后备份均保留；新备份不能解释恢复前APFS克隆/快照为何缺失，也不把该缺失项标为解决。

## 原 S3 与非实时 Omni

原 `18333` S3 端点及原 bucket 已恢复，2个既有对象的 HEAD/GET 均为200，实际读取字节和 SHA-256 与原记录匹配。没有新建 bucket、替换原件或改用文件系统结果冒充 S3。原 Dev 的真实上传、媒体派生、百炼 Omni、SSE 和持久回复在恢复后重新通过：

| 技术样本 | input tokens | output tokens | total tokens | input audio tokens |
| --- | ---: | ---: | ---: | ---: |
| 录音 | 183 | 81 | 264 | 44 |
| 有声视频（采样帧和原音轨） | 830 | 79 | 909 | 16 |

两例 run 均为 `completed`，证据 `/Users/lengwang/code/esp32/work/omni-dev-e2e-proof-r3.json`。样本是明确的技术素材，未使用客户业务数据或现场机械声。

失败尝试没有改写为成功：故障前/恢复早期旧视频 run 已耗尽重试；新 r2 在 MySQL/应用时钟回退约68ms时失败。`work/omni-dev-e2e-proof.json`、`work/omni-dev-e2e-proof-r2.json`、`work/omni-dev-e2e-proof-r2-clock-failure.json` 和相关日志均保留；r3是本次恢复后 Dev S3 录音与有声视频的成功依据。独立文件系统的早期成功记录仍保留，但不再作为“S3当前不可用”的理由。

## B5 浏览器阶段结果

已用独立浏览器身份完成首轮实际双端 WebRTC 音视频接收，视频实收192帧；输入是明确的本地合成语音和 canvas 技术画面，通过实际 MediaStream/浏览器 RTP 传输。没有物理麦克风、摄像头、扬声器或两名真人参与。实际截图上传原 S3 并生成 B5 frame 已通过。

首轮抓帧后的编辑器被客服工作台后台poll关闭，失败现象保留；对应poll/invite修复已完成定向验证。第二通实际active，抓帧 `2116191817604976642` 后，p2正常挂断complete且人工服务仍active；编辑器跨多轮poll和挂断保持。箭头/circle共2个元素保存为annotation `2116191926321336322`，服务端ready且可视预览已核对，加入回复草稿成功，客服文字草稿仍保留。实际发送已完成：客服消息 `e0cb32f2e6ca491084e1f518f51bf2ae` 在客户UI可见文字及image附件；只读核对source/frame/render三引用均关联同条staff消息，scene含2元素，三个实际S3 GET的SHA均匹配。证明 `/Users/lengwang/code/esp32/work/browser-backend-proof.json`。浏览器换房转接已完成下述技术链路，45秒旧邀请过期的尝试记录保留。首轮证据为 `work/browser-dual-media-proof.json`；较早native SDK R3的转接/三证据结果见 [B5记录](./B5_RUNTIME_VALIDATION.md)。

### 浏览器独立新房转接

客户明确同意、目标专家B明确接听后，真正进入独立新房generation2并达到服务端active。实际浏览器接收如下：

| 接收端 | 媒体 | bytesReceived | packetsReceived | framesDecoded |
| --- | --- | ---: | ---: | ---: |
| B（p4） | audio | 409773 | 1659 | — |
| B（p4） | video | 100392 | 147 | 105 |
| 客户（p2） | audio | 408378 | 1653 | — |

以上是浏览器RTP接收统计；技术输入仍为明确的合成语音/canvas画面。原专家A（p3）的旧PeerConnection已断开、UI回复禁用；旧身份实际POST写入返回403 `CURRENT_ASSIGNMENT_REQUIRED`。B自动采纳原request，历史可见AI内容和已发标注，通过UI回复后客户可见。证据 `work/browser-transfer-proof.json`、`work/browser-stale-expert-proof.json` 和 `work/browser-expert-transferred.png`。该浏览器结果补充较早native SDK的隔离证据，不把原JWT在自托管LiveKit的即时撤销扩写为已验证能力。

最终媒体 `2116193127137988610` 已由p2挂断并经只读后端核对为ended/complete、generation2；transfer `2116193234969350145` 为completed，generation1/2房间均cleaned，12个member均reservation released且busy_slot为空。人工request仍active、对话为HUMAN_ACTIVE且当前assignment为B；B消息 `a4fe6ed8394549229aab3bc193cfcc41` completed。挂断媒体没有结束人工服务或自动恢复AI。

仍有5项delete_room queued、没有running lease。这是MediaMaintenance.poll对retired_at后15分钟内cleaned墓碑的repeat补偿，防止迟到token重新建房；drainStatus按契约排除已cleaned/complete的这类queued。房间已清理和席位已释放已证实；不将补偿窗口写成失败，也不声称全部outbox已done。本轮未修改Java该逻辑。

转接中另发现LiveKit 2.23在`emitUnsubscribed`前先`track.detach()`，第二次detach无法取得旧DOM元素，可能留下空video；最小修复定向6/6与build通过。修复后实际新通话active时只有1个video（宽320、readyState4），挂断complete后video/audio元素均为0、全部技术track ended，证据 `work/browser-dom-cleanup-proof.json`。

## P3 浏览器启动与有序结束修复

同一浏览器页面的无 import 最小 blob AudioWorklet probe 证实：Ego 工具的 `Target.setAutoAttach` filter 会使 worklet target 暂停；取消该 filter 后同一 probe ready，关闭自动附加后仍 pending。该现象与 [ego-lite issue #468](https://github.com/citrolabs/ego-lite/issues/468) 一致。它是本轮浏览器录音启动挂起的工具原因，不能记为 provider 未 ready、getUserMedia 未授权或 Vite classic worker 错误。只读核对实际 Vite dev URL 已是 `type=module`、没有 `importScripts`；生产 worklet 为独立1.45KB脚本，有 `registerProcessor` 且无外部 import。未为此改动 Vite 或 worklet 导入。

同时修复了产品自身的两类资源与结束边界：

- 麦克风权限 prompt 保持无强制超时，原有授权重验继续工作；权限返回后的 worklet 加载、AudioContext resume、video.play 各有10秒期限，取消或超时立即释放轨道、音频图、视频和上下文。权限迟到返回仅停止轨道，不重建采集。
- 明确结束先停止本地采集和播放、fence旧异步回调，保留 WebSocket 至 HTTP返回匹配会话的明确终态后再关闭。单独 socket close 不再视为结束成功；HTTP失败保留失败与不确定性。身份 dispose立即关闭保留的连接，迟到 ACK 不写回；迟到 playback.resume 也不能继续创建会话。

5个 realtime 定向套件47项通过，生产 build通过；这是源码回归结果，不能代替完整浏览器录音、采样、provider回复、正常结束和结束后恢复文字的验收。2026-10-30 23:33，实际浏览器手动录音39秒并采样本地技术画面，模型答复“我看到一个青绿色的正方形，背景是深蓝色的。”；用户转写为“请描述你现在看到的画面中有哪些形状和颜色。如果看不到画面，请直接说明。”，持久历史可见，原17字未发送文字草稿保留。随后会话 `0c7730888c60403788242156f9f1aacb` 正常达到ended，UI显示“会话结束已保存，本机设备已关闭”，8条技术audio/video track均为ended，17字未发送草稿仍保留。随后p1 reload恢复原生输入，确认无qaHook/captureHook；通过正常UI发送“技术验收：实时通话已正常结束，请仅回复‘文字恢复正常’。”，实际B2回答“文字恢复正常”。实际浏览器录音、采样、真实模型回复、持久历史、正常结束及恢复B2文字的本轮技术链路已通过；恢复原生输入不等于已经使用物理麦克风/摄像头验收。该录音/图像来自明确的技术MediaStream输入，没有物理麦克风或摄像头。

## 恢复后前端最终检查

最新逻辑全量为Vue单元/组件387项、契约10项、snapshot辅助13项通过，6种语言各1840 key一致，生产build通过。之后仅移动CSS断点修复并再次build通过，未把旧逻辑全量写成CSS后重复执行。上述47项realtime、6项DOM清理均为定向集合，不与全量计数相加。

390×844手机宽度实际发现历史仅50px高；移动断点CSS改为message-list最小200px及workspace纵向滚动，实测历史200px，document.scrollWidth与innerWidth均390。发送按钮通过wheel进入视口y740–781，composer可达，桌面行为保持；p1移动realtime overlay右边378，小于390，无横向溢出。截图 `work/browser-mobile-annotation-final.png`、`work/browser-mobile-composer-final.png` 仅作本机证据，不提交。

最终健康证据 `work/browser-final-health.json`：页面URL为原Dev目标、runtimeExceptions0、error logs0、无Vite错误overlay。TaskSpace33只finish一次，保留原管理员p1，关闭3个测试tab；专家A/B“接受新单”均通过UI置false。7个本轮拥有的临时进程（备用API、3个worker、备用Vite及2个QA Vite）均已正常SIGTERM停止，证据 `work/recovery-temporary-stop-proof.json`；所有数据文件、DB、volumes和对应manifest/私有配置保留，可按manifest恢复。原Dev18894/API18893及其AI/Media/realtime worker状态均为running，未随备用进程停止；QA记录保留。

## 保留的验收边界

- 两名真人及手机/现场终端的麦克风、摄像头、扬声器、回声与真实机械声对照。
- HTTPS/WSS、TURN、企业防火墙、Wi-Fi/蜂窝切换、弱网/断线恢复与30分钟持续通话。
- 本轮技术MediaStream不替代真人通话、手机物理输入/输出和现场设备声学验收；浏览器双端通话、转接、标注发送、实际挂断DOM清理、手机布局及P3结束后恢复B2文字已有上述证据。
- 恢复时已生成并核验的APFS克隆/恢复快照当前无法定位，须查明位置及可恢复性；现存20:39及新post-recovery SQL备份不解释恢复前完整克隆/快照的缺失原因。
- ESP32 OTA、WebSocket、MCP、唤醒、麦克风、喇叭、打断及重复唤醒的实体回归。
- 商用RTC服务质量、容量和长时恢复；本机自托管 LiveKit 技术样本不代表商用RTC环境验收。
- B6真实来源身份/撤销和业务工具、外部身份RTC fencing及真实业务上游联调；默认关闭边界保持。

其余范围以 [剩余验收清单](./REMAINING_WORK.md) 为准。新增QA数据、失败记录及原始对象保留，后续成功不覆盖既有失败证据；恢复克隆/快照的当前可用性按上述未闭合项记录。
