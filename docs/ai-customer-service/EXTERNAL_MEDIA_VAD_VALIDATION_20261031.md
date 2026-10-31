# 外部媒体来源授权与自动语音检测验证

日期：2026-10-31。Server、worker 和 Vue 3 已整合到 Dev；原 Dev 开启自动听音，外部来源身份和媒体入口仍默认关闭。外部协议使用独立测试 issuer、密钥及新建隔离数据库；没有调用真实业务 API，没有修改 Laravel、aimeet 或外部 App。

## 实现边界

- B5 人工媒体与 P3 AI 实时会话由服务端捕获认证 principal，持久绑定来源会话及具体 Bearer。相同本地 owner 的其他来源、同来源其他 Bearer 和 local customer token 不能接管绑定媒体。外部身份不能成为客服或转接目标。
- 人工邀请在客户同意前可被查询、拒绝，首次接受持久绑定；转接的新房客户成员继承绑定。媒体票据、worker claim/heartbeat/event、LiveKit token/ready 和客户转接审批持续重验来源。Bearer 默认 300 秒，媒体不会通过重签 token 延长来源权限。
- 登出只撤销该 Bearer，签名 revoke 撤销其来源会话；过期、账号或 issuer 禁用、能力关闭及授权依赖故障均失败关闭。维护按授权检查时间轮转，关闭开关后仍收敛已有通话；媒体结束保留聊天和人工 assignment。
- 授权读使用独立当前快照，避免原 REPEATABLE READ 快照继续允许已撤销权限。真实满池测试发现 Druid 1.2.20 会忽略 checkout 等待上限；现用最多 4 个 daemon 线程、零等待队列、1.5 秒借连接截止、取消与迟到连接关闭、2 秒 SQL 查询上限。繁忙时拒绝，不静默放行。
- 新建 AI 会话模式不可变，缺省 manual；server_vad 由实际 provider 事件提交轮次和生成回复。语音打断清本地播放，并等待 provider 实际取消终态。监听期间文字注入排队，暂停清缓冲 ACK 后派发。暂停丢弃未提交语音；静音不计单次 60 秒发言上限，仍计 30 分钟会话上限。
- 旧三字段 Create 在 manual 模式下保留严格旧 canonical hash 重试兼容；新建始终保存规范四字段 hash，不放宽 consent、会话、client ID 或模式检查。

配置、API 白名单及故障限制见 [运维说明](EXTERNAL_MEDIA_VAD_OPERATIONS.md)。

## 最终自动检查

| 检查 | 结果 |
| --- | --- |
| Java 全量，真实 MySQL，全新隔离 schema，含旧版本行迁移后幂等重试 | 508 通过，0 failure/error/skipped |
| Vue 3 单元和组件 | 415 通过 |
| Vue 契约 / snapshot | 10 / 13 通过 |
| i18n / 生产构建 | 6 语言各 1853 key；构建通过 |
| Python realtime worker 网络测试 | 38 通过 |
| 普通 AI worker | 48 通过 |
| Dev 管理与配置保护 | 45 通过 |

Java 前一轮全量 507 通过；新增旧版本迁移重试测试后，使用另一整套全新 schema 重新全量构建，最终 508。定向 100 项是全量的子集，不重复相加。构建保留已有动态/静态 import 同模块的分包提示，不影响运行。

## 真实百炼与外部身份协议

独立 Java API 18902、Python worker 18903、新建 MySQL schema `xiaozhi_media_test_extvad_b372ed7768ba`、独立 Redis 34778。使用已授权 `qwen3.8-omni-flash-realtime`，输入为 OS 合成语音，无真人麦克风或摄像头。隔离 worker 使用纯透传审计包装记录真实 provider 事件；原 Dev 浏览器使用标准 worker 入口。

核心 9 项与扩展 3 项通过：

1. 签名 issue、单次 exchange、同 owner 不同 source/Bearer；其他来源和同来源其他 Bearer 的媒体访问均被拒绝。
2. 连续两轮自动语音，真实 provider speech/committed/response、ASR、usage、持久账本一致。两轮 total_tokens 分别 520、635；自动音频没有额外手动 commit/response.create。暂停后的文字注入另有一次正常 response.create，不与音频轮次混同。
3. 暂停获得 provider 实际 `input_audio_buffer.cleared`；文字注入获得实际 item ID 与 ACK。
4. Logout 到运行 WSS 关闭 1.293 秒，source revoke 到关闭 2.105 秒；其他 Bearer/source 仍有效。使用相应真实旧 lease 发迟到 callback 均 HTTP 409，账本不变。
5. 连续发送 65 秒静音，无误造 speech/response 或错误，会话继续可用。
6. 长回答中真实语音打断，收到 `response.done status=cancelled`，客户端清播放，接续新轮完成。provider done 比 speech_started 早约 8 毫秒的顺序也通过。
7. 未提交语音片段后暂停，实际 clear ACK 且账本不变。

证据摘要：`/Users/lengwang/code/esp32/work/external-vad-20261031/p3-acceptance-summary.json`；两个 run 的原始证据、音频、事件与私有 task 均保留。私有 task 含 lease，不应分享或提交。

## 真实 LiveKit

独立 room prefix，来源客户与本地客服使用 native SDK 发送、实收非零技术正弦音频。staff 邀请在外部客户同意前可见，接受后其他 source 和 local customer token 的读取/发票请求均 403。JWT exp 不超过绑定 Bearer 到期时间。

签名 source revoke 后两端断开，单次实测 2.299 秒；房间清理完成。旧 JWT 重连只进入旧空房，没有对端或音频；来源 B 仍可继续文字，原人工 request 保持 active。SDK 1.1.20 退出时出现既有 `FfiHandle.__del__ AssertionError` 忽略提示；功能断言和脚本 exit 0 通过，日志保留。

首轮测试客户端遗漏 HMAC path 中的 `/xiaozhi` context，正确被拒绝 `SIGNATURE_INVALID`；修正客户端后通过。首次失败证据保留，没有放宽服务端签名检查。原始成功文件为同目录 `b5-proof-*.json`，日志 `b5-probe-r2.log`。

## 原 Dev Vue 3

地址 `http://127.0.0.1:18894/#/conversations`，API 18893，标准 realtime worker 18899。Browser plugin 未提供，依环境要求使用 ego-browser 35 号 TaskSpace，未使用其他浏览器替代。

实际登录、选择自动模式、明确同意、连接、开始听音、两轮语音、两次暂停、结束均验证。连接前 getUserMedia 调用数为 0；开始后为技术 MediaStream，未采集真实麦克风。两轮实际模型回复“自动听音正常”，usage total_tokens 为 510、572，收到实际音频 chunk；没有客户端 audio.commit。暂停后 track ended；结束后两条技术音轨均 ended、audio/video DOM 为 0，文字草稿仍保留。草稿验证限于通话过程，不宣称浏览器 reload 后持久保存。

桌面与 390×844 手机视口检查，无横向溢出；无框架错误遮罩或 window error/unhandled rejection。曾因测试等待器使用错误的标题/状态文案超时，调整观察条件后验证，不属于应用错误。Ego 的 AudioWorklet 自动暂停仍需同页 CDP auto-attach 无 filter workaround（既有工具问题）；已移除全部技术输入/观察脚本并 reload，恢复真实设备入口。

原始 `browser-proof.json` 和截图位于同一 work 目录；技术浏览器结果不等于真实终端声学、噪声、回声或现场验收。

## 数据保留与待验收

原 Dev 迁移前一致性 SQL：`/Users/lengwang/code/esp32/work/external-vad-20261031/dev-before-20261031T021924Z.sql`，506315 字节，SHA256 `8966e513c3009457fbd075f793e44d6e1a76bf1f237d7dc7e12abe1b33d63c8a`。原库 4 用户、25 旧设备历史、29 消息、117 changeset；只追加 600/601/602 三个新 changeset，迁移至 120。原 changeset 未修改，未删库、删卷、清表或重新初始化。浏览器另追加一个技术会话、两轮用户/AI 消息；测试成功/失败 schema 均保留。 最终原Dev核对为4用户、25旧历史、33消息、120迁移，P3/B5 active均为0，原MySQL卷挂载一致。迁移后备份 `dev-after-20261031T023433Z.sql` 为521467字节，SHA256 `1ded97f2ffce352860fa687f2fc85c600e74ab30f87de6d7b76479ffbd3c1960`，目录同迁移前。独立API/worker/Redis已正常停止且保留全部数据，原Dev继续运行。

真实来源服务尚无业务委托查询 API。真实 App 来源签名/撤销投递、物理终端麦克风/喇叭/摄像头、噪声/回声、HTTPS/WSS、TURN、防火墙、弱网和长通话仍需独立验收。断流秒数是健康技术环境的单次观察，不承诺故障或积压下的 SLA；LiveKit 清理失败时不能声称旧媒体已瞬时停止。原规划未完成范围继续见 [剩余工作](REMAINING_WORK.md)。
