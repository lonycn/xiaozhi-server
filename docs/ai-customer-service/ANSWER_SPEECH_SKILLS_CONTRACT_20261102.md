# A6 普通回答朗读与固定只读技能契约

日期：2026-11-02。公开接口见 [独立 OpenAPI](./ANSWER_SPEECH_SKILLS_CONTRACT_20261102.yaml)。两项均默认关闭，能力发现分别为 `features.answer_speech` / `interaction.capabilities.answer_tts` 和 `features.ai_skills` / `interaction.capabilities.readonly_skills`。`implemented` 与 `verification_required` 或 `configured` 仅反映配置，不证明 provider 健康。会话功能开启但相应功能关闭时，控制器由服务返回真实 HTTP 404（而非旧式 200 错误体）。

## 普通回答朗读

只读取目标会话中已持久化、状态为 `completed` 的 assistant 消息：按原顺序用换行连接所有 text part，拒绝空白、非法 UTF-8 或超过 8000 个 UTF-16 代码单元的文本。客户端不提交文字、模型、音色或 URL。Java 把 `message_version` 固定为 SHA-256（消息 ID + 换行 + 数据库中原始 `parts_json`），`text_sha256` 是实际合成文本 UTF-8 SHA-256；两者是不同的 opaque 64 位小写十六进制摘要，客户端不应根据重排后的 JSON 自行重算。

| 方法与路径（前缀 `/api/v1/ai/conversations/{conversation_id}`） | 请求 | 返回 |
| --- | --- | --- |
| POST `/messages/{message_id}/speech` | `{client_request_id}` | Job |
| GET `/speech/{job_id}` | 无正文 | Job |
| POST `/speech/{job_id}/cancel` | `{}` | Job |
| GET `/speech/{job_id}/audio` | 无正文 | 私有 `audio/wav` |

成功 JSON 为 `{code:0,msg,data}`。全部路径拒绝 query；POST 为严格 JSON，最大 16384 字节，拒绝重复键、未知字段、尾随 JSON 和标量强制转换。请求 ID 1–64 字符、匹配 `[A-Za-z0-9_.:-]+`；同一 owner 重试相同 ID 时须保持会话、消息、版本和来源绑定，冲突返回 409。每 owner 最多一个排队/合成任务，最近一小时最多创建 10 个；分别返回 409、429。请求失败不能伪装完成，也不能自动换新 ID 无限重试。

Job 为 `{id,conversation_id,message_id,message_version,text_sha256,status,error_code,audio_mime,audio_bytes,duration_ms,result_access,playback_authorized_until,created_at}`。状态 `queued|synthesizing|completed|failed|canceled`；音频元数据、错误码和授权截止可以为 null。所有标识为字符串，`audio_bytes` 和 `duration_ms` 是 JSON number，时间为 ISO-8601。结果 `restricted` 表示当前来源、身份或消息版本未通过复核，不授播放授权；历史状态不代表仍有访问权限。

服务对每次元数据/音频访问重新检查 owner、原始消息和来源授权，外部身份须保留精确来源及 token 绑定；同一 token 续期可延续，换 token 不继承原访问权。completed 且授权有效时，`playback_authorized_until` 不晚于当前时间后 5 秒，也不晚于外部 token 到期。下载必须使用 Bearer header、不可把 token 放在 query；下载后再次 GET 新鲜授权，再由用户动作播放。播放时每 2 秒刷新授权，授权未知、过期、受限、请求失败、切换身份/会话/消息版本或页面隐藏应立即停止并释放 Blob；全局只允许一个朗读 owner。音频读取时服务还逐块重新授权。取消只终止活动任务，不删除既有音频或记录。

客户端将正数远端授权按本地最多 5 秒截断，容忍服务器/浏览器的毫秒级时钟差；不增加服务端授权、不会把过期或未知授权当可播放。断网立即停止，联网不自动重播。实时连接/通话或人工媒体占用播放时停止朗读，须再次显式播放。

服务端固定私有配置使用 Bailian `cosyvoice-v2` / `longxiaochun_v2`，任务不携带 provider URL、密钥或客户端自选音色。独立 worker 将完整 PCM 16-bit 24 kHz 单声道封装为 WAV，限制 5 MiB、120 秒，不发布部分音频或将其当作实时通话 PCM、ESP32 TTS。私有存储目录和文件分别限制为 0700/0600，使用路径边界及 NOFOLLOW/符号链接防护。provider 已观察费用按原 attempt 单独记录 `characters` 和可选 request ID；未知为 null，不推算为文本长度或 tokens，取消后产生的已观察费用也可记录，但不能恢复播放权。

## 固定只读技能

| 方法与路径 | 请求 | 返回 |
| --- | --- | --- |
| GET `/api/v1/ai/skills` | 无正文 | `{items:[Definition]}` |
| GET `/api/v1/ai/conversations/{conversation_id}/skill` | 无正文 | `{selection: Selection|null}` |
| POST `/api/v1/ai/conversations/{conversation_id}/skill` | `{client_request_id,skill_id,version}` | `{selection: Selection|null}` |

Definition 为 `{id,version,title,description,allowed_tools,source_policy}`，当前固定 `materials_guidance` / `evidence_summary`、版本 1，来源策略为 `distinguish_user_material_tool_and_inference`，工具只可能包含 `read_conversation_history` 和 `read_conversation_evidence`。客户端从服务端目录选择，不传自由 prompt、脚本、工具、URL 或业务写入。清除选择时 `skill_id` 和 `version` 同为 null；同一会话的同 ID 相同请求幂等，不同内容冲突 409。技能选定后仅在后续创建的普通 run 中捕获服务端固定版本、模板和允许工具的不可变快照；不会修改现有 run 或实时会话，也不授予工具、来源或业务权限。来源失效应如实报告，区分用户陈述、材料观察、工具结果与推断。

## 已有证据与边界

已完成独立真实技术链路：普通朗读真 provider 样本为 186764 字节、3890 ms、35 个 provider characters；只读技能真 qwen-flash 两轮共 1823 tokens，历史读取工具调用 1 次。分别记录于 `work/ai-first-20261102/python-a6-answer-speech-summary.json` 和 `python-a6-skills-summary.json`；它们是两条独立证据，不是一开始就完成了页面全流程。原生失败路径及严格 noCloud 授权、flag-off 补验均已通过。

后续最终修复已在 Dev 部署。独立任务浏览器已实际完成真实模型的 history 工具追问及公开引用、目标消息朗读、用户停止、显式重播与原生 kEnded；并修复毫秒时钟差误拒、断网/迟到播放清理及结束浮窗遮挡。证据为 `work/ai-a5-a8-20261102/browser-proof.json`，完整批次和构建见 [交付记录](AI_FIRST_DELIVERY_20261102.md)。浏览器音频图/媒体状态不证明真实扬声器听感、发音质量、手机或真实业务验收通过。
