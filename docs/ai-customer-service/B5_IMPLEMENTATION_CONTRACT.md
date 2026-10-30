# B5 真人媒体会话与专家转接实施契约

日期：2026-10-30。本文冻结本批设计，不是已实现或验收声明。原 [完整规划](./AIMEET_INTEGRATION_PLAN.md) 保留；其中同物理房间转接的早期设想由本契约的房间代次切换取代，以保证旧房间 JWT 不能加入新房间。Conversation 和业务 media session 在转接前后保持连续，AI 实时连接和真人媒体连接分开；Omni 是独立 PoC。

## 范围与发布

`features` 的 B5 开关默认关闭，需显式启用并依赖已有 conversation/support。用户明确同意后才申请并传输媒体；拒绝、未接入或挂断均不妨碍继续文字。媒体结束不等于结束人工、解除人工分配或恢复 AI；恢复 AI 沿用既有明确操作。未有真实真人媒体接入证据不得标为 active。客户端控制麦克风/摄像头，按用户明确选择授予最小 publish source，不凭“接受”自动开启摄像头。

不修改共享 LiveKit 全局配置、其他应用房间及已有业务数据；房间只能使用本模块服务端生成、不可猜的独立前缀和随机代次。LiveKit 承载媒体而非业务授权权威；Conversation、分配、代次和历史保留在小智服务端。LiveKit 用户 token、内部服务凭据及用户 Bearer 各司其职，不互换、不进入日志或普通事件。SQL 只做新增增量 changeset；不更改历史 checksum，不初始化、清空、迁移现有数据或卷，保留历史行及清理结果。

## 代次与转接安全

一个业务 media session 保持同一 ID。每次真人转接为目标创建不可猜的新物理 `room_generation`，旧专家的 JWT 只允许旧房，不能加入新房；只向经过服务端认证、当前账号有效的客户和目标专家签发新房 token。当前旧专家与客户在旧房继续服务，直至目标接听且客户、目标在新房均有真实、当前有效的媒体 ready 证据。准备中拒绝、超时、失联、撤权、容量失效或外部服务故障时取消目标准备，释放预留并清理新房；保留原业务负责人和旧房，若原服务自身已经中断则如实显示中断，不伪造连通。

最终切换前在同一事务持锁重查当前会话、业务请求与负责人、会话/房间代次、双方同意和新鲜 ready、客户与目标账号、专家资格和容量预留。成功时原 assignment 截止消息/事件序号固定为切换点；新 assignment 与媒体代次成为当前，媒体会话 ID 与 Conversation ID 不变。离任专家只能按原 B1 会话历史截止读取消息及附件，不能继续写入、签发新房 token 或收到截止后的事件。迟到回调、旧版本请求、旧接入证据及旧 token 均不得恢复旧代次或覆盖新状态。

原房删除/旧参与者移除是外部副作用，不能作为数据库事务回滚的假设。由持久 saga 在切换前定向移除旧参与者，提交切换后通过 outbox 关闭本模块旧房；失败记录可观测并重试；只对本模块持有的精确房间操作。当前代次切换的隔离来自新房与新房限权 token，不能声称自托管 LiveKit 的踢人会撤销已签发 token，也不能把 webhook 的事后踢人描述成无窗口隔离。旧房删除后旧 token 即使尚未过期，也不能授权访问新房；外部清理未完成应如实保留 pending。

## 公开接口与持久状态

路径前缀 `/api/v1/support`，沿用 Bearer、Result、严格字段校验与服务端身份，现为实施契约而非已开放接口。所有幂等键相同但参数不同必须冲突，客户端不得指定房名或身份。`expected_epoch` 与 `room_generation` 是服务端版本和代次 fence，过期调用不得改变当前状态。

- `POST /media-sessions`：`{request_id,client_session_id,mode:"audio"|"video",consent:{microphone:true,camera:boolean}}`；`GET /media-sessions/current?conversation_id=`、`GET /media-invitations?after=0&limit=50`、`GET /media-sessions/{id}` 查询授权范围内状态。
- `POST /media-sessions/{id}/accept`：`{expected_epoch,consent}`；`/reject`：`{expected_epoch}`；`/token`：`{expected_epoch,room_generation}`；`/ready`：`{expected_epoch,room_generation,participant_sid,peer_sid,transport_ready,receiving_audio,receiving_video}`；`/end`：`{expected_epoch,reason?}`。ready 是客户端证据，必须结合服务端 ListParticipants 的 SID 和 ACTIVE 状态以及权限与时效复核，不能仅凭客户端布尔值标 active。
- `POST /media-sessions/{id}/transfers`：`{expected_epoch,client_transfer_id,target_agent_user_id}`；`GET /media-transfers/{id}`；`POST /media-transfers/{id}/approve`（客户）及 `/accept`（目标）：`{expected_epoch,consent}`；`/reject` 和 `/cancel`：`{expected_epoch}`。任何一方未显式同意或受邀方未接受，不签发其发布 token。

增量六类持久数据为 media session、room generation、member（含媒体容量 reservation）、transfer、清理 outbox、webhook receipt；行与代次保留。session 状态 `invited -> connecting -> active <-> reconnecting -> ending -> ended`，另有 `rejected/canceled/expired/failed` 终态；transfer 状态 `invited -> preparing -> ready -> committing -> completed`，失败/拒绝/取消/超时保留可追溯终态并释放目标预留。数据库唯一约束保证每 Conversation 最多一个活动 session、每 session 最多一个待处理 transfer、每专家最多一个媒体预留；invited、preparing、active、cleanup_pending 均占媒体容量。目标文字容量也计入 B1 claim 判断，账户 profile 锁串行化。

锁顺序统一为相关 profile/account 按 actor ID 排序、conversation、request、session/transfer、member/outbox；切换持锁校验 control_epoch 与当前 generation 并 CAS。目标准备就绪且双方 SID/ACTIVE 确认后，按持久 saga 先移除旧专家和旧客户，再 CAS 切 assignment 和新 generation；LiveKit 网络 I/O 在短数据库事务外。外部移除已成功而数据库提交未完成时不能假称旧媒体仍可用：记录中间状态、阻止重复/过时代次写入，按持久 saga 对账和恢复，向用户如实显示连接状态；不得宣称外部 I/O 可随数据库回滚。每个状态变化以当前代次与授权重验为前提。

## 授权与媒体适配

所有用户 API 从 Bearer 会话解析身份，不信任客户端传入的用户 ID、角色、房间、participant identity 或授权范围。签发 token 前及媒体相关状态变更时重查账号、请求/assignment、同意、当前或有效准备代次；终态、撤权或离任不再签发。JWT 只用于指定 room 和指定 identity，短期有效，允许的 source 仅为获同意的 microphone/camera；不授予 roomCreate、roomList、roomAdmin 或录制等管理权限。管理调用使用独立服务凭据，最小范围并限制调用时长，凭据不回传前端。

服务端适配器提供签发受限 token、查询本模块房间参与者、移除指定参与者及删除指定房间；`not_found` 对清理按幂等成功处理，其他失败保留待重试状态，不吞掉业务失败。Twirp `POST /twirp/livekit.RoomService/RemoveParticipant` 以 `{room,identity}` 和具 roomAdmin 的服务 token 调用；DeleteRoom 需 roomCreate。参考 [LiveKit token grants](https://docs.livekit.io/frontends/reference/tokens-grants/) 和 [RoomService API](https://docs.livekit.io/reference/other/roomservice-api/)。

签名 webhook 如配置：使用官方 receiver 对原始请求体和 Authorization JWT 验签，核对 body SHA-256 后才解析，按 event ID 持久去重；按当前代次和状态处理，迟到事件不能复活终态。LiveKit webhook 重试并不保证送达，且不得修改共享 webhook 配置作为本批前提；未配置时仍通过受限的本模块自有房间轮询核验参与者和清理，无法确认即保持未就绪或失败。参考 [LiveKit webhooks](https://docs.livekit.io/intro/basics/rooms-participants-tracks/webhooks-events/)。

## 适配器冻结边界

媒体适配器约定 `OwnedRoom(sessionId, generation, roomName)`，调用前必须经过数据库归属及代次授权 `RoomOwnership.allowed(room, operation, identity)`，房间前缀只作为额外限制，不能代替归属校验。仅允许精确 CreateRoom、ListParticipants、GetParticipant、RemoveParticipant、DeleteRoom；不提供全局列房间。房间初始上限 2 人，empty timeout 120 秒、departure timeout 30 秒。参与者 JWT 默认 60 秒、最长 120 秒，仅限房间加入/订阅和已同意的 camera/microphone；不授予发布数据、管理、录制、ingress 或 SIP 权限。管理 JWT 默认 60 秒且仅具对应操作所需 grant。

HTTP 请求总超时默认 5 秒、连接超时不超过 3 秒、响应最大 1 MiB，不重定向或在适配器内自动重试；认证、限流、超时、不可用、协议错误和 not_found 分开归类。仅 Remove/Delete 的精确 Twirp `not_found` 可按清理成功，其余错误交给上层持久 saga 处理。旧 JWT 在自托管部署下可能重建空旧房；仅能清理数据库确认属于本模块的精确旧房，不能依靠旧房删除实现新房准入安全。

Webhook 如启用，接收原始字节并检查 JWT HS256、issuer、有效期及 not-before，将 SHA-256 原始字节摘要与 JWT 中标准 Base64 解码结果恒时比较，通过后才解析事件；不记录请求体、JWT 或密钥。适配器 DTO 和配置字段随 Java 代理最终冻结，不能据此推断接口已经可用。

## 容量、并发与故障恢复

转接准备时为目标预留容量；预留与 B1 claim 使用相同账号容量锁及一致锁顺序，防止并发超卖。释放、超时、撤销和成功切换均幂等地处理预留，持久化状态而不依赖 Redis 在线状态作为唯一权威。所有异步通知、轮询、worker 或清理执行使用持久状态、代次 fencing 和条件更新；每次外部操作的成功与业务提交之间允许重试并处理重复/乱序。源与目标账号撤权时阻止新 token 和写入，停止或撤销对应准备并发起有界清理；不得凭过期 token 或超时的 ready 恢复权限。

## 待执行验收

本契约、单元测试、mock、构建成功或房间创建成功均不证明真实媒体已连通。独立测试数据库须覆盖旧数据保留、幂等、并发 claim/预留、代次 fencing、历史 cutoff、授权撤销、过期证据、异常与进程重启后 saga/outbox 恢复。隔离环境须另行验证真实 LiveKit token/grants、webhook 原始体篡改/重放、未配置 webhook 的轮询、目标准备失败保持原服务、故障时的定向清理，以及切换后旧 JWT 无法进入新房。

真实验收需两个独立浏览器/身份验证双方音频、摄像头可选与明确同意、接听/拒绝/挂断、同一业务会话转接及权限撤销；再单列 30 分钟、Wi-Fi/蜂窝切换、断线恢复与现场终端麦克风/扬声器和网络测试。不得将模拟参与者或单元测试当作双向真人通话或现场验收。真实手机号外呼不在本批。
