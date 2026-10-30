# B3 私有附件与媒体任务实施契约

日期：2026-10-30。基线 `Dev` 的 B2 提交 `b2df1b4`。本文冻结本轮跨代理接口；只有通过测试并在验证记录中标明的能力才算交付。

## 数据和范围

不删除、清空、初始化或覆盖现有 Dev、原智控台、aimeet 数据库与卷。追加新的附件、媒体任务与对象日志表；历史 SQL/checksum 不改。原 ESP32/OTA/MCP 不动。业务行和原始证据保留，媒体回收只允许本模块自己创建、未关联消息且超过保留期的暂存对象；数据库行保留 expired/deleted 状态，不执行 DELETE/DROP/TRUNCATE。

本轮包含私有图片、PDF/UTF-8 文本、短视频、录音的授权流式上传、异步检查和派生物，以及用户/客服的混合消息与私有查看。图片原件与去元数据的展示图分开保存。Office/ZIP/SVG/HTML/GIF 暂不开放。

默认发布开关 `features.attachments-enabled=false`。开启须同时启用 conversation；media worker 使用独立密钥 `media.worker.key`，不共用用户 Bearer 或 AI worker key。Dev 显式启用后使用新的私有目录或 S3 前缀，不复用任何已有业务对象路径。

| 类别 | MIME | 原件上限 | 检查 |
| --- | --- | --- | --- |
| 图片 | image/jpeg, image/png, image/webp | 20 MiB | 解码成功、单帧、24MP、单边8192；EXIF旋转后生成展示图与缩略图并去元数据 |
| 文件 | application/pdf, text/plain | 20 MiB | PDF真正解析、最多200页；文本UTF-8且无NUL/伪装HTML；提取文本最多60000字符并标明截断 |
| 视频 | video/mp4, video/webm | 100 MiB | 实际容器/编解码可读、最长180s、最大3840×2160；生成poster与最多6张带时间点采样帧 |
| 音频 | audio/wav, audio/mpeg, audio/mp4, audio/ogg | 50 MiB | 实际音轨可读、最长600s；规范化单声道16k WAV供后续转写 |

服务端计算SHA-256；客户端声明必须一致。文件扩展名、MIME、魔数及实际解码格式必须一致。ffmpeg/ffprobe仅处理本地临时文件，禁网络协议，命令无shell，输入/进程时间及输出大小有上限。解析失败不得标 ready。

## 公开接口

均沿用 Result `{code,msg,data}`、用户 Bearer、十进制字符串ID/long。body拒绝未知字段。附件的conversation_id由上传初始化固定，客户端不能指定owner、sender、storage_key或外部URL。

1. `POST /api/v1/ai/uploads/init`
   body `{conversation_id,client_upload_id,filename,mime_type,size,sha256}`。本人或当前可写客服可上传。按真实上传者+client_upload_id幂等，同编号不同内容/会话409。返回附件公开元数据及 `upload:{method:"PUT",url,expires_at,max_bytes}`。URL为本API的受限流式代理，仍发送Bearer；不暴露存储密钥。
2. `PUT /api/v1/ai/uploads/{id}/content`
   原始二进制、声明的Content-Type。先鉴权，再按声明和类别上限流式读取到私有暂存文件，计算hash，禁止直接使用filename作为路径。原件不可变。重复相同上传可返回原状态，不覆盖已存在字节；内容变化409。对象存储成功不等于ready。
3. `POST /api/v1/ai/uploads/{id}/complete` body `{}`
   校验真实原件存在，幂等排队inspect_media。返回同一附件元数据。未完成上传409。
4. `GET /api/v1/ai/attachments/{id}`
   返回 `{id,conversation_id,filename,mime_type,kind,size,sha256,status,error_code,metadata,jobs,created_at}`。status为 pending_upload/uploaded/processing/ready/failed/expired。无原始object key、lease、内部凭据。metadata只含白名单媒体属性与可用派生物名称。
5. `POST /api/v1/ai/attachments/{id}/retry` body `{}`
   原件存在、同上传者仍可写、失败可重试任务且少于3次claim才允许。无重复活动任务。
6. `GET /api/v1/ai/attachments/{id}/access?variant=display|thumbnail|original|poster|text|audio|transcript|frame_0..5`
   返回 `{url,expires_at,mime_type,filename}`，短期签名能力最多120秒。签名URL中的短票据不等于用户长期Bearer；内容端每次重新检查票据actor的账号和附件资源授权。
7. `GET /api/v1/ai/attachments/{id}/content?ticket=...`
   限定附件/variant/actor/有效期；content-type、nosniff、private/no-store和安全Content-Disposition。原件/PDF/文本默认下载，展示图可inline。视频/音频支持单Range/206/416；不支持任意路径或外部重定向。该精确内容路由可不带Bearer，但签名和动态资源授权不可省。

未关联附件只允许上传者读取；已关联附件使用ConversationAccessService，离任客服还须 linked_seq<=read_through_seq。管理员没有跨客户附件旁路。已关联附件不能跨会话或另一次消息复用。

## 混合消息与事务

在原MessagePart追加可选 `attachment_id`，保留旧8参数Java构造兼容。支持part `{type:"image"|"video"|"audio"|"file",attachment_id}`，不接受客户端补充URL、MIME、宽高、转写或分析。普通文字/位置校验保持原样。

用户或当前客服发送时，在现有消息事务内锁会话、验证所有附件ready、同会话、属于该发送者且未关联或属于本次幂等消息，然后写统一ai_message并关联附件与seq；失败全部回滚。幂等重发不产生第二个消息/关联。单消息最多10个附件、最多1视频与1音频，文字+媒体+位置保持顺序。不改写旧消息与原始对象。

## 内部媒体worker协议

仅 `/internal/media-worker/**` 使用常量时间专用 `X-Media-Worker-Key`，用户Bearer不能调用。每个任务最多3次claim，默认lease90秒；每次续租/读输入/写派生物/完成/失败重新验证当前lease、attempt和账号状态。媒体检查与AI回答不同，不因正常转人工而丢弃原始证据。

- `POST /claim` `{worker_id}` -> null或 `{job_id,attachment_id,job_type,attempt,lease_token,lease_seconds,filename,mime_type,size,sha256,metadata}`。
- `POST /jobs/{id}/heartbeat` `{lease_token}` -> `{active:true}`。
- `GET /jobs/{id}/input`，header `X-Media-Lease`，返回受限原件；转写任务可返回规范化audio派生物。
- `PUT /jobs/{id}/artifacts/{kind}`，header `X-Media-Lease`，二进制。允许kind display/thumbnail/text/poster/audio/transcript/frame_0..frame_5；每个kind固定允许MIME与大小，服务端生成对象key，重复字节幂等，改变内容409。
- `POST /jobs/{id}/complete` `{lease_token,metadata}`。metadata白名单：mime_type,width,height,duration_ms,has_audio,text_available,text_truncated,frames(artifact,time_ms)，转写可附model及真实usage。只有需要的派生物已成功持久化才ready。
- `POST /jobs/{id}/fail` `{lease_token,error_code}`。错误码白名单 INVALID_MEDIA/TYPE_MISMATCH/PIXEL_LIMIT/DURATION_LIMIT/PAGE_LIMIT/PROCESSING_TIMEOUT/PROCESSING_FAILED/STORAGE_UNAVAILABLE/TRANSCRIPTION_NOT_CONFIGURED/PROVIDER_AUTH/PROVIDER_RATE_LIMIT/PROVIDER_TIMEOUT/PROVIDER_ERROR。不得回传或记录提供方原始错误/用户内容/凭据。

inspect_media必需；audio_transcription为独立可选任务，在有效ASR配置启用后创建。转写失败不抹掉已检查合格的原件，页面分别显示其状态，不编造转写或用量。转写文本是媒体派生证据，不作为人工或AI冒名消息。模型适配和真实提供方验证单列。

事件写入同一事务，使用现有conversation事件游标：attachment.processing/attachment.ready/attachment.failed、media.queued/media.started/media.completed/media.failed。临时未发送的客服附件不应通过公共conversation事件向客户泄露草稿文件名/内容；公开事件仅对已关联附件发出。

## 存储与补偿

存储接口至少支持私有filesystem和显式配置的S3。S3沿用基础设施但使用新前缀；不初始化或改桶策略。读取与写入不使用公开桶。S3不可达时请求报错，不悄悄降级到另一个存储并标ready。

对象日志先记录服务器生成的唯一key，再写对象，再用带状态/lease的CAS确认。网络写入放在数据库长事务之外，落库失败、进程崩溃、失败attempt和过期未引用对象都有补偿回收路径。回收只能删除本模块日志中记录、位于配置前缀/根目录内且仍未引用的对象，数据库记录保留。绝不遍历清空整个桶、旧上传目录或任何表。

## 责任和验收

Java代理负责附件/对象/任务模块、增量迁移、专用filter、消息事务集成和真实MySQL定向测试。Python代理负责独立media_worker、真实解码/FFmpeg处理、有限资源与HTTP worker协议测试。前端代理负责公共附件client与共享展示/附件草稿，用于统一对话和客服工作台，保留B1/B2布局和发送幂等。

父代理负责Dev配置、模型能力接入、后端整套隔离回归、真实HTTP和浏览器验证、保留证据、文档与提交。任何代理不得重启服务/修改业务库/提交凭据；共享仓库中不回滚他人改动。
