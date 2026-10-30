# B4 精确截图与静态标注实施契约

日期：2026-10-30。基于B3附件/媒体能力；功能默认 `features.annotations-enabled=false`。B4在独立工作目录实现，父代理完成B3真实验收后整合到Dev，不改旧迁移/checksum，不删除或清空业务数据。

采用不可变Frame快照、不可变annotation-v1场景和服务端成图。源只允许同会话**已发送且ready**的image/video；创建者必须为本人或当前可写客服。离任客服不能创建/续租处理/重试/发送新结果。照片使用去EXIF后的display；视频必须从原件真实解码指定时间点，不能把B3六张采样帧伪装为精确截图。

## 公开API

均沿用Bearer、Result和严格未知字段拒绝。ID为正long十进制字符串。未发送快照仅作者读取；发送后通过关联消息seq使用ConversationAccessService历史cutoff授权。管理员无跨客户旁路。详情、scene、背景和成图票据均重新检查权限。

- `POST /api/v1/support/frames` `{conversation_id,client_frame_id,source_attachment_id,time_ms?}`。
- `GET /api/v1/support/frames/{id}`。
- `POST /api/v1/support/frames/{id}/retry` `{}`，最多三次claim，幂等来源不变。
- `POST /api/v1/support/annotations` `{conversation_id,client_annotation_id,frame_id,label,scene}`。
- `GET /api/v1/support/annotations/{id}`。
- `GET /api/v1/support/annotations?conversation_id=&after=0&limit=50`，作者草稿及当前有权读取的已发送记录，稳定创建id游标，最多100。
- `POST /api/v1/support/annotations/{id}/retry` `{}`，最多三次claim，不修改scene。

Frame返回 `id,conversation_id,source_attachment_id,frame_attachment_id,requested_time_ms,actual_time_ms,capture_policy,width,height,status,error_code,created_at`。image的时间为null。video须整数 `0<=time_ms<duration_ms`，capture_policy=first_at_or_after，记录归一化视频原点后的第一个实际PTS>=请求点；无合格帧明确失败。不能捏造actual_time_ms等于请求。

Annotation返回 `id,conversation_id,frame_id,render_attachment_id,label,scene,status,error_code,created_at`。背景/成图使用B3附件access短票及动态授权，不返回外部URL或object key。

同作者+client_frame_id/client_annotation_id幂等；相同规范化请求返回原ID，不同内容409。编辑后保存使用新编号，永不覆盖旧scene、原帧或成图。保存草稿不发公开消息/事件；成图ready才可加入composer，作者明确发送后才对客户可见。

## annotation-v1

根仅 `{format:"annotation-v1",frame_id,width,height,elements}`；width/height必须匹配服务端Frame实际尺寸。scene最多256KiB、200元素，总20000点，id唯一且短的ASCII本地标识。颜色为#RRGGBB。坐标有限且在[0,1]，所有图形须位于画布内。

| type | 允许字段 |
| --- | --- |
| arrow | id,type,color,stroke_width,points（恰两个{x,y}） |
| rect | id,type,color,stroke_width,x,y,width,height |
| circle | id,type,color,stroke_width,cx,cy,radius |
| freehand | id,type,color,stroke_width,points（2..2000个{x,y}） |
| text | id,type,color,x,y,text,font_size |

线宽0.001..0.02、圆半径和字号均相对min(width,height)；circle是真圆，按像素半径检查完整边界；字号0.005..0.1，文字top-left，固定服务端字体和1.25行高。文字单元素最多1000、总8000 Unicode码点。无URL、图片/base64、SVG/HTML资源、脚本、外部字体、files或扩展字段。文字仅作为普通字符串绘制。服务端再次校验worker处理参数，不相信客户端渲染图。

## 数据和B3扩展

仅新changeset `202610302000` 增加support_frame/support_annotation两表；不修改202610301800及历史SQL。每个Frame和Annotation分配新的服务端image附件，复用ai_media_job的capture_frame/annotation_preview类型与现有唯一键。

support_frame：id、conversation_id、creator_user_id、client_frame_id、request_hash、source_attachment_id、frame_attachment_id、requested_time_ms、actual_time_ms、capture_policy、created_at。作者+client id唯一，输出附件唯一。

support_annotation：id、conversation_id、creator_user_id、client_annotation_id、request_hash、frame_id、label、format、scene_json、scene_sha256、render_attachment_id、created_at。作者+client id唯一，输出附件唯一，scene不可变。

B3 AttachmentService增加受控服务器生成附件入口，客户端不能直接上传生成附件。生成前size=0/sha256占位，processing状态不可发送；完成时由真实JPEG display对象填入size/hash/original_object_id及实际尺寸，ready后才能关联消息。每种任务必需display+thumbnail。生成任务重试检查真实源对象，不要求尚不存在的output.original。

claim/input：capture_frame的真实输入为源display或video original；annotation_preview为固定Frame display。Task的mime/size/hash必须描述实际输入。metadata增加job类型专属白名单：capture参数或经过Java校验的完整scene、expected width/height与固定frame/source IDs。请求字段命名由Java/Python直接协调后同步文档；不得使用任意存储key或外部URL。

lease/回调在B3已有account/attempt/token/expiry之外重查当前写权限及来源可读状态。保留有限恢复/对象日志/补偿回收。生成失败仅状态变化，不覆盖原件。

bind成图时，同消息事务校验Annotation及Frame来源，并把未关联Frame附件绑定到同一message/seq作为隐藏证据；已关联Frame保持其最早关联不覆盖。发送失败全部回滚，原client_message_id重试保持幂等。这使已发送标注的背景不被临时附件回收，收件人可追溯。

输出附件的可信metadata含annotation_id/frame_id/source_attachment_id/requested_time_ms/actual_time_ms；由Java补充，worker/客户端不得注入或修改来源关系。公开Conversation事件仍不得泄露未发送草稿。

## Worker和前端

Python独立media_worker增加capture_frame和annotation_preview，不连接DB。视频真实解析帧PTS并选择first_at_or_after，再提取该帧，返回actual_time_ms；限制视频时长/尺寸、子进程时限、禁网络、无shell。图片/Frame原件不修改。Pillow服务端成图，固定可配置的本地中文字体（优先复用仓库已有noto_qwen.ttf）；只输出JPEG display和thumbnail，不接受前端上传render。

Vue原生Canvas2D+Pointer Events+ResizeObserver，无新框架依赖。共享AnnotationEditor用于用户和客服工作台；图像来自短票据下载的私有Blob，跨会话/身份/assignment或卸载取消IO并释放资源。支持箭头/圈/框/文字/手绘、撤销/重做/可撤销重置、IME输入、比例缩放；保存快照与发送分离。失败保留草稿和相同请求编号；编辑内容改变才换新编号。视频显示请求点与实际帧时间，不把前端video.currentTime当服务端证据。

## 验收

独立新MySQL：幂等/并发、草稿隔离、跨会话/离任拒绝、原图及场景不覆盖、发送事务回滚和隐藏背景保活、旧业务sentinel保留。Worker：真实指定时间视频帧、五种图形渲染、中文、有限/非有限/越界场景、来源和坏文件拒绝、lease撤销。Vue：坐标缩放、草稿/幂等、快照轮询、撤权和晚响应隔离。父代理完成HTTP+实际存储+浏览器完整路径；不把合成技术样本当现场验收。
